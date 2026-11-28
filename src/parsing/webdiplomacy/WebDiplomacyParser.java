package parsing.webdiplomacy;

import domain.*;
import game.*;
import game.record.GameHistory;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;
import parsing.ParseException;
import phase.UnitId;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Decodes the raw Classic bundle acquired by WebDiplomacyGameClient.
 * Source snapshots and verdicts are retained without running a processor.
 */
public final class WebDiplomacyParser {

    private static final JsonAccess JSON = new JsonAccess(ParseException::new);

    public GameHistory parse(String source) {
        Map<String, Object> bundle = JSON.object(JsonReader.read(source), "$");
        require(text(bundle, "schema").equals("jreferee-webdiplomacy-raw-v1"), "$.schema",
                "Unsupported webDiplomacy bundle schema");
        int gameId = integer(bundle, "gameID");
        require(gameId > 0, "$.gameID", "Expected a positive game ID");
        Map<String, Object> game = document(bundle, "game");
        Map<String, Object> history = document(bundle, "history");
        Map<String, Object> variant = document(bundle, "variant");
        for (var entry : Map.of("game", game, "history", history).entrySet()) {
            require(integer(entry.getValue(), "gameID") == gameId
                            && text(entry.getValue(), "file").equals(entry.getKey()),
                    entry.getKey(), "Game document identity mismatch");
        }
        require(integer(variant, "variantID") == 1 && integer(variant, "mapID") == 1
                        && text(variant, "name").equals("Classic"),
                "variant", "Only Classic is supported");
        Map<String, Object> gameVariant = JSON.object(game.get("variant"), "game.variant");
        require(integer(gameVariant, "id") == 1 && text(gameVariant, "name").equals("Classic"),
                "game.variant", "Game does not use Classic");
        require(text(game, "phase").equals("Finished"), "game.phase", "Expected a completed game");
        Map<Integer, Nation> countries = countries(variant);
        Map<Integer, Province> territories = territories(variant);
        List<Object> raw = historyPhases(history, integer(game, "turn"));
        List<GameHistory.Position> positions = new ArrayList<>();
        GameMoment previousMoment = null;
        for (int index = 0; index < raw.size(); index++) {
            String path = "history.phases[" + index + "]";
            Map<String, Object> phase = JSON.object(raw.get(index), path);
            int turn = integer(phase, "turn");
            String name = text(phase, "phase");
            boolean terminal = name.equals("Finished");
            GameMoment moment = terminal ? previousMoment : moment(turn, name, path);
            Map<UnitId, Province> units = new LinkedHashMap<>();
            Map<UnitId, Province> dislodged = new LinkedHashMap<>();
            for (Object value : JSON.array(phase.get("units"), path + ".units")) {
                Map<String, Object> unit = JSON.object(value, path + ".units[]");
                Nation nation = lookup(countries, integer(unit, "countryID"), path);
                UnitType type = unitType(text(unit, "type"), path);
                Province location = lookup(territories, integer(unit, "terrID"), path);
                boolean retreating = bool(unit, "retreating");
                String identity = gameId + ":" + index + ":" + nation + ":" + type + ":" + location + ":" + retreating;
                UnitId id = new UnitId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                        nation, type, location);
                Map<UnitId, Province> target = retreating ? dislodged : units;
                require(target.putIfAbsent(id, location) == null, path, "Duplicate unit");
            }
            Map<Province, Nation> centers = new LinkedHashMap<>();
            Set<Province> seenCenters = new HashSet<>();
            for (Object value : JSON.array(phase.get("centers"), path + ".centers")) {
                Map<String, Object> center = JSON.object(value, path + ".centers[]");
                Province province = Province.canonical(lookup(territories, integer(center, "terrID"), path));
                require(province.supplyCenter && seenCenters.add(province), path, "Invalid or duplicate center");
                int country = integer(center, "countryID");
                if (country != 0)
                    centers.put(province, lookup(countries, country, path));
            }
            List<GameHistory.Submission> orders = new ArrayList<>();
            List<GameHistory.Annotation> annotations = new ArrayList<>();
            for (Object value : JSON.array(phase.get("orders"), path + ".orders")) {
                Map<String, Object> order = JSON.object(value, path + ".orders[]");
                GameHistory.Submission submission = order(order, countries, territories, path);
                require(orderPhase(submission.orderType()).equals(name), path, "Order belongs to another phase");
                orders.add(submission);
                annotations.add(new GameHistory.Annotation(submission.owner(), submission.origin(),
                        name.equals("Retreats"), bool(order, "success"),
                        text(order, "type") + (bool(order, "dislodged") ? "; DISLODGED" : "")));
            }
            positions.add(new GameHistory.Position(turn, name, moment, new BoardState(units, centers),
                    orders, annotations, dislodged, true, true, terminal));
            previousMoment = moment;
        }
        return new GameHistory(null, positions);
    }

    static List<Object> historyPhases(Map<String, Object> history, int gameTurn) {
        require(gameTurn >= 0, "game.turn", "Invalid game turn");
        List<Object> phases = JSON.array(history.get("phases"), "history.phases");
        require(phases.size() >= 2, "history.phases", "Expected initial and final positions");
        long previous = -1;
        int previousTurn = -1;
        for (int index = 0; index < phases.size(); index++) {
            String path = "history.phases[" + index + "]";
            Map<String, Object> phase = JSON.object(phases.get(index), path);
            int turn = integer(phase, "turn");
            String name = text(phase, "phase");
            int rank = switch (name) {
                case "Diplomacy" -> 0;
                case "Retreats" -> 1;
                case "Builds" -> 2;
                case "Finished" -> 3;
                default -> throw new ParseException(path, "Unknown phase: " + name);
            };
            require(turn >= 0 && turn <= gameTurn && 4L * turn + rank > previous,
                    path, "Invalid or unordered turn");
            require(index != 0 || turn == 0 && rank == 0, path, "Expected Spring 1901 first");
            require(name.equals("Finished") == (index == phases.size() - 1),
                    path, "Expected exactly one final Finished position");
            JSON.array(phase.get("units"), path + ".units");
            JSON.array(phase.get("centers"), path + ".centers");
            List<Object> orders = JSON.array(phase.get("orders"), path + ".orders");
            if (name.equals("Finished")) {
                // Finished shares the turn of the preceding archived phase.
                require(turn == previousTurn && orders.isEmpty(), path,
                        "Finished must retain the preceding turn and contain no orders");
            }
            previous = 4L * turn + rank;
            previousTurn = turn;
        }
        return phases;
    }

    private static GameHistory.Submission order(Map<String, Object> raw, Map<Integer, Nation> countries,
                                                Map<Integer, Province> territories, String path) {
        String type = text(raw, "type");
        OrderType orderType = switch (type) {
            case "Hold" -> OrderType.HOLD;
            case "Move" -> OrderType.MOVE;
            case "Support hold", "Support move" -> OrderType.SUPPORT;
            case "Convoy" -> OrderType.CONVOY;
            case "Retreat", "Disband" -> OrderType.RETREAT;
            case "Build Army", "Build Fleet" -> OrderType.BUILD;
            case "Destroy" -> OrderType.DESTROY;
            case "Wait" -> OrderType.WAIVE;
            default -> throw new ParseException(path, "Unknown order type: " + type);
        };
        Province origin = territory(raw, "terrID", territories, path);
        Province to = territory(raw, "toTerrID", territories, path);
        Province from = territory(raw, "fromTerrID", territories, path);
        String rawUnit = JSON.optionalString(raw, "unitType", path);
        UnitType unit = type.equals("Build Army") ? UnitType.ARMY
                : type.equals("Build Fleet") ? UnitType.FLEET
                : rawUnit == null ? null : unitType(rawUnit, path);
        if (rawUnit != null && unit != null)
            require(unitType(rawUnit, path) == unit, path, "Build unit type mismatch");
        require(orderType == OrderType.WAIVE || origin != null && unit != null,
                path, "Missing issuing province or unit type");
        Province target = switch (type) {
            case "Move", "Retreat", "Support hold" -> to;
            case "Support move", "Convoy" -> from;
            default -> null;
        };
        Province auxiliary = type.equals("Support move") || type.equals("Convoy") ? to : null;
        require(!List.of("Move", "Retreat", "Support hold", "Support move", "Convoy").contains(type)
                        || target != null, path, "Missing order target");
        require(!List.of("Support move", "Convoy").contains(type) || auxiliary != null,
                path, "Missing auxiliary target");
        return new GameHistory.Submission(lookup(countries, integer(raw, "countryID"), path),
                unit, origin, orderType, target, auxiliary, bool(raw, "viaConvoy"));
    }

    private static String orderPhase(OrderType type) {
        return switch (type) {
            case RETREAT -> "Retreats";
            case BUILD, DESTROY, WAIVE -> "Builds";
            default -> "Diplomacy";
        };
    }

    private static GameMoment moment(int turn, String phase, String path) {
        boolean spring = turn % 2 == 0;
        require(!phase.equals("Builds") || !spring, path, "Builds must follow Fall");
        GamePhase gamePhase = switch (phase) {
            case "Diplomacy" -> spring ? GamePhase.SPRING_MOVEMENT : GamePhase.FALL_MOVEMENT;
            case "Retreats" -> spring ? GamePhase.SPRING_RETREAT : GamePhase.FALL_RETREAT;
            case "Builds" -> GamePhase.WINTER_ADJUSTMENT;
            default -> throw new ParseException(path, "Unknown phase");
        };
        return new GameMoment(Math.addExact(Constants.STARTING_YEAR, turn / 2), gamePhase);
    }

    private static Map<Integer, Nation> countries(Map<String, Object> variant) {
        Map<Integer, Nation> countries = new LinkedHashMap<>();
        for (Object value : JSON.array(variant.get("countries"), "variant.countries")) {
            Map<String, Object> country = JSON.object(value, "variant.countries[]");
            Nation nation = Nation.valueOf(text(country, "name").toUpperCase(Locale.ROOT));
            require(countries.putIfAbsent(integer(country, "countryID"), nation) == null,
                    "variant.countries", "Duplicate country ID");
        }
        require(new HashSet<>(countries.values()).size() == 7 && countries.size() == 7,
                "variant.countries", "Expected seven Classic countries");
        return countries;
    }

    private static Map<Integer, Province> territories(Map<String, Object> variant) {
        Map<String, Province> names = new HashMap<>();
        for (Province province : Province.values())
            names.put(province.fullName, province);
        names.put("St. Petersburg", Province.Stp);
        names.put("Skagerrack", Province.SKA);
        names.put("Heligoland Bight", Province.HEL);
        names.put("Gulf of Lyons", Province.LYO);
        names.put("Spain (North Coast)", Province.SpaNC);
        names.put("Spain (South Coast)", Province.SpaSC);
        names.put("St. Petersburg (North Coast)", Province.StpNC);
        names.put("St. Petersburg (South Coast)", Province.StpSC);
        names.put("Bulgaria (North Coast)", Province.BulEC);
        names.put("Bulgaria (South Coast)", Province.BulSC);
        Map<Integer, Province> territories = new LinkedHashMap<>();
        for (Object value : JSON.array(variant.get("territories"), "variant.territories")) {
            Map<String, Object> territory = JSON.object(value, "variant.territories[]");
            Province province = names.get(text(territory, "name"));
            require(province != null, "variant.territories", "Unknown Classic territory: " + territory.get("name"));
            require(territories.putIfAbsent(integer(territory, "id"), province) == null,
                    "variant.territories", "Duplicate territory ID");
        }
        require(!territories.isEmpty(), "variant.territories", "Missing map");
        return territories;
    }

    private static Map<String, Object> document(Map<String, Object> bundle, String name) {
        Map<String, Object> envelope = JSON.object(bundle.get(name), name);
        String json = text(envelope, "json");
        require(SourceFingerprints.sha256Hex(json).equals(text(envelope, "sha256")),
                name, "Document fingerprint mismatch");
        Map<String, Object> document = JSON.object(JsonReader.read(json), name);
        require(integer(document, "schema") == 1, name, "Unsupported document schema");
        return document;
    }

    private static Province territory(Map<String, Object> raw, String key, Map<Integer, Province> map, String path) {
        Object value = JSON.required(raw, key, path);
        return value == null ? null : lookup(map, JSON.integer(value, path + "." + key), path + "." + key);
    }

    private static UnitType unitType(String type, String path) {
        return switch (type) {
            case "Army" -> UnitType.ARMY;
            case "Fleet" -> UnitType.FLEET;
            default -> throw new ParseException(path, "Unknown unit type: " + type);
        };
    }

    private static <T> T lookup(Map<Integer, T> values, int id, String path) {
        T value = values.get(id);
        require(value != null, path, "Unknown source ID: " + id);
        return value;
    }

    private static String text(Map<String, Object> value, String key) {
        return JSON.string(value.get(key), key);
    }

    private static int integer(Map<String, Object> value, String key) {
        return JSON.integer(value.get(key), key);
    }

    private static boolean bool(Map<String, Object> value, String key) {
        return JSON.bool(value.get(key), key);
    }

    private static void require(boolean condition, String path, String message) {
        if (!condition)
            throw new ParseException(path, message);
    }
}
