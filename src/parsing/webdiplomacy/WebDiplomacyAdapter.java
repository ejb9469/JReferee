package parsing.webdiplomacy;

import contracts.OrderForms;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;
import parsing.ParseException;
import phase.Order;
import phase.UnitId;
import phase.UnitLookup;
import phase.adjustments.*;
import phase.retreats.RetreatOrder;

import java.util.*;


/**
 * Decodes Classic mechanics fixtures for source-admission diagnostics.
 * Source result flags remain separate from submitted phase orders.
 */
public final class WebDiplomacyAdapter {


    private static final JsonAccess JSON = new JsonAccess(ParseException::new);

    private final Map<Integer, Nation>      countries   = new LinkedHashMap<>();
    private final Map<Integer, Province>    territories = new LinkedHashMap<>();


    private WebDiplomacyAdapter(Map<String, Object> variant) {

        require(number(variant, "schema") == 1 && number(variant, "variantID") == 1
                        && number(variant, "mapID") == 1 && text(variant, "name").equals("Classic"),
                "Expected schema-1 Classic map 1");
        require(number(variant, "supplyCenterCount") == 34
                && number(variant, "supplyCenterTarget") == 18, "Unexpected Classic center counts");
        require(!flag(object(required(variant, "rules")), "buildAnywhere"),
                "Classic buildAnywhere must be explicitly false");

        for (Object value : array(required(variant, "countries"))) {
            Map<String, Object> raw = object(value);
            Nation nation = Nation.valueOf(text(raw, "name").toUpperCase(Locale.ROOT));
            int id = number(raw, "countryID");
            require(id > 0 && countries.putIfAbsent(id, nation) == null,
                    "Duplicate country ID");
        }
        require(countries.size() == 7 && new HashSet<>(countries.values()).size() == 7,
                "Expected seven distinct Classic countries");

        List<Object> rawTerritories = array(required(variant, "territories"));
        for (Object value : rawTerritories) {
            Map<String, Object> raw = object(value);
            int id = number(raw, "id");
            require(id > 0 && territories.putIfAbsent(id, province(text(raw, "name"))) == null,
                    "Duplicate territory ID");
        }
        Set<Province> expected = EnumSet.allOf(Province.class);
        expected.remove(Province.Swi);
        require(territories.size() == 81 && new HashSet<>(territories.values()).equals(expected),
                "Map must contain exactly the 81 playable Classic locations");

        for (Object value : rawTerritories) {
            Map<String, Object> raw = object(value);
            Province location = territory(required(raw, "id"));
            Province parent = Province.canonical(location);
            require(territory(required(raw, "coastParentID")) == parent,
                    "Coast parent differs for " + location);
            boolean child = location.parent != null;
            boolean splitParent = !child && Arrays.stream(Province.values()).anyMatch(p -> p.parent == location);
            require(text(raw, "coast").equals(child ? "Child" : splitParent ? "Parent" : "No"),
                    "Coast kind differs for " + location);
            String terrain = location.geography == Geography.WATER ? "Sea"
                    : location.geography == Geography.COASTAL || splitParent ? "Coast" : "Land";
            require(text(raw, "type").equals(terrain), "Terrain differs for " + location);
            require(flag(raw, "supply") == (!child && location.supplyCenter),
                    "Supply-center flag differs for " + location);
            Object home = required(raw, "homeCountryID");
            Nation homeNation = home == null ? null : country(home);
            if (!child && location.supplyCenter)
                require(homeNation == location.homeowner, "Home center differs for " + location);
            checkBorders(raw, location);
        }

    }

    public static SourceGame readFixture(String source) {

        Map<String, Object> raw = object(JsonReader.read(Objects.requireNonNull(source, "source")));
        require(text(raw, "schema").equals("jreferee-webdiplomacy-mechanics-fixture-v1")
                        && text(raw, "source").equals("WEBDIPLOMACY") && text(raw, "split").equals("TRAIN"),
                "Expected a webDiplomacy TRAIN mechanics fixture");
        String key = text(raw, "game_key");
        require(key.matches("[1-9][0-9]*"), "Invalid game key");
        String splitHash = SourceFingerprints.sha256Hex("7\nWEBDIPLOMACY\n" + key);
        require(Long.remainderUnsigned(Long.parseUnsignedLong(splitHash.substring(0, 16), 16), 100) < 80,
                "Fixture belongs to a held-out split");
        String sourceHash = text(raw, "source_payload_sha256");
        require(sourceHash.matches("[0-9a-f]{64}"), "Invalid source receipt");
        Map<String, Object> game = object(required(raw, "game"));
        Map<String, Object> history = object(required(raw, "history"));
        for (var entry : Map.of("game", game, "history", history).entrySet()) {
            require(number(entry.getValue(), "schema") == 1
                    && Integer.toString(number(entry.getValue(), "gameID")).equals(key)
                    && text(entry.getValue(), "file").equals(entry.getKey()), "Document identity differs");
        }
        Map<String, Object> selection = object(required(game, "variant"));
        require(number(selection, "id") == 1 && text(selection, "name").equals("Classic")
                        && text(game, "phase").equals("Finished")
                        && Set.of("Won", "Drawn").contains(text(game, "gameOver"))
                        && text(game, "playerTypes").equals("Members") && !flag(game, "isSandbox"),
                "Game differs from the reviewed source selection");

        WebDiplomacyAdapter adapter = new WebDiplomacyAdapter(object(required(raw, "variant")));
        List<Frame> frames = new ArrayList<>();
        int previousRank = -1;
        int lastMovement = -1;
        for (Object value : array(required(history, "phases"))) {
            Frame frame = adapter.frame(object(value));
            require(frame.turn() >= 0, "Negative source turn");
            if (frame.name().equals("Finished")) {
                require(!frames.isEmpty() && frame.turn() == frames.getLast().turn(),
                        "Finished turn must equal the preceding retained turn");
            } else {
                int offset = switch (frame.name()) {
                    case "Diplomacy" -> 0;
                    case "Retreats" -> 1;
                    case "Builds" -> 2;
                    default -> throw new ParseException("Unknown source phase: " + frame.name());
                };
                int rank = Math.addExact(Math.multiplyExact(frame.turn(), 3), offset);
                require(rank > previousRank, "Source phases are duplicated or out of order");
                previousRank = rank;
                if (frame.name().equals("Diplomacy")) {
                    require(frame.turn() == lastMovement + 1, "Missing source movement turn");
                    lastMovement = frame.turn();
                } else {
                    require(frame.turn() == lastMovement, "Phase has no preceding movement turn");
                    require(!frame.name().equals("Builds") || frame.turn() % 2 == 1,
                            "Builds recorded in Spring");
                }
                frame.board();
            }
            require(frames.isEmpty() || !frames.getLast().name().equals("Finished"),
                    "A phase follows Finished");
            frames.add(frame);
        }
        require(frames.size() >= 2 && frames.getFirst().name().equals("Diplomacy")
                        && frames.getFirst().turn() == 0 && frames.getLast().name().equals("Finished"),
                "History must run from Spring 1901 through Finished");
        require(frames.getFirst().board().equals(StandardGameFactory.create1901().board()),
                "Opening board differs from standard Classic");
        verifyOwnership(frames);
        require(frames.getLast().owners().equals(frames.get(frames.size() - 2).owners()),
                "Finished must retain the preceding source ownership snapshot");
        require(frames.getLast().reportedCenters().equals(frames.get(frames.size() - 2).reportedCenters()),
                "Finished must retain the preceding source center visibility");
        int finishedTurn = frames.getLast().turn();
        require(number(game, "turn") == finishedTurn || number(game, "turn") == Math.addExact(finishedTurn, 1),
                "Game header turn differs from the retained history boundary");
        return new SourceGame(key, sourceHash, text(game, "pressType"), frames);

    }

    /**
     * Sparse archive rows can omit neutral centers. Once owned, a center must
     * remain explicitly owned in every later snapshot, including Finished.
     */
    public static void verifyOwnership(List<Frame> frames) {

        Set<Province> previouslyOwned = EnumSet.noneOf(Province.class);
        for (Frame frame : frames) {
            require(frame.reportedCenters().containsAll(frame.owners().keySet()),
                    "Positive ownership lacks a source row at turn " + frame.turn());
            Set<Province> lost = EnumSet.copyOf(previouslyOwned);
            lost.removeAll(frame.owners().keySet());
            require(lost.isEmpty(), "Previously owned centers are missing or neutral at turn "
                    + frame.turn() + " " + frame.name() + ": " + lost);
            previouslyOwned.addAll(frame.owners().keySet());
        }

    }

    private Frame frame(Map<String, Object> raw) {

        List<PlacedUnit> units = new ArrayList<>();
        for (Object value : array(required(raw, "units"))) {
            Map<String, Object> unit = object(value);
            UnitType type = unitType(required(unit, "type"));
            Province location = territory(required(unit, "terrID"));
            require(OrderForms.isValidUnitLocation(type, location), "Invalid unit location: " + location);
            units.add(new PlacedUnit(country(required(unit, "countryID")), type, location, flag(unit, "retreating")));
        }
        Map<Province, Nation> owners = new LinkedHashMap<>();
        Set<Province> reportedCenters = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        for (Object value : array(required(raw, "centers"))) {
            Map<String, Object> center = object(value);
            int id = number(center, "terrID");
            require(seen.add(id), "Duplicate territory ownership entry");
            Province location = territory(id);
            int owner = number(center, "countryID");
            Nation nation = owner == 0 ? null : country(owner);
            if (location.parent == null && location.supplyCenter) {
                reportedCenters.add(location);
                if (nation != null)
                    owners.put(location, nation);
            }
        }
        List<Submission> orders = new ArrayList<>();
        String name = text(raw, "phase");
        for (Object value : array(required(raw, "orders"))) {
            Map<String, Object> order = object(value);
            String type = text(order, "type");
            String phase = switch (type) {
                case "Hold", "Move", "Support hold", "Support move", "Convoy" -> "Diplomacy";
                case "Retreat", "Disband" -> "Retreats";
                case "Build Army", "Build Fleet", "Destroy", "Wait" -> "Builds";
                default -> throw new ParseException("Unknown order type: " + type);
            };
            require(name.equals(phase), "Order type occurs in the wrong phase: " + type);
            Submission submission = new Submission(type, country(required(order, "countryID")),
                    nullableUnitType(required(order, "unitType")), nullableTerritory(required(order, "terrID")),
                    nullableTerritory(required(order, "toTerrID")), nullableTerritory(required(order, "fromTerrID")),
                    flag(order, "viaConvoy"), flag(order, "success"), flag(order, "dislodged"));
            require((submission.origin() == null) == type.equals("Wait"), "Unexpected issuing location for " + type);
            require((submission.to() != null) == Set.of("Move", "Support hold", "Support move", "Convoy", "Retreat").contains(type),
                    "Unexpected target field for " + type);
            require((submission.from() != null) == Set.of("Support move", "Convoy").contains(type),
                    "Unexpected support/convoy origin for " + type);
            if (!phase.equals("Builds"))
                require(submission.unitType() != null, "Missing issuing unit type for " + type);
            if (type.equals("Convoy"))
                require(submission.unitType() == UnitType.FLEET, "A convoy order requires a fleet");
            if (type.equals("Wait"))
                require(submission.unitType() == null, "Wait must not identify a unit");
            if (type.equals("Build Army") || type.equals("Build Fleet"))
                require(submission.unitType() == null || submission.unitType() ==
                        (type.equals("Build Army") ? UnitType.ARMY : UnitType.FLEET), "Conflicting build unit type");
            require(!submission.viaConvoy() || type.equals("Move") && submission.unitType() == UnitType.ARMY,
                    "Convoy intent must belong to an army move");
            orders.add(submission);
        }
        require(name.equals("Retreats") || units.stream().noneMatch(PlacedUnit::retreating),
                "Dislodged unit marked outside Retreats");
        return new Frame(number(raw, "turn"), name, units, owners, reportedCenters, orders);

    }

    public static List<Order> movement(Frame frame, BoardState board) {

        require(frame.name().equals("Diplomacy"), "Expected a movement frame");
        List<Order> orders = new ArrayList<>();
        Set<UnitId> seen = new HashSet<>();
        for (Submission source : frame.orders()) {
            UnitId unit = unitAt(board.locations(), source);
            require(seen.add(unit), "Duplicate movement submission");
            if (source.type().equals("Move") && unit.unitType() == UnitType.ARMY) {
                boolean adjacent = board.locationOf(unit).isAdjacentTo(need(source.to()));
                boolean matchingConvoy = frame.orders().stream().anyMatch(other ->
                        other.type().equals("Convoy") && Province.equalsIgnoreCoast(other.from(), source.origin())
                                && Province.equalsIgnoreCoast(other.to(), source.to()));
                if ((adjacent && (source.viaConvoy() || matchingConvoy)) || (!adjacent && !source.viaConvoy()))
                    throw new UnrepresentedConvoyException(source);
            }
            orders.add(switch (source.type()) {
                case "Hold" -> Order.hold(unit);
                case "Move" -> Order.move(unit, need(source.to()));
                case "Support hold" -> Order.supportHold(unit, need(source.to()));
                case "Support move" -> Order.supportMove(unit, need(source.from()), need(source.to()));
                case "Convoy" -> Order.convoy(unit, need(source.from()), need(source.to()));
                default -> throw new ParseException("Expected a movement submission");
            });
        }
        require(seen.equals(board.locations().keySet()), "Movement submissions do not cover the source board");
        return List.copyOf(orders);

    }

    public static List<RetreatOrder> retreats(Frame frame, GameState_Retreats state) {

        require(frame.name().equals("Retreats"), "Expected a retreat frame");
        Map<UnitId, Province> dislodged = new LinkedHashMap<>();
        state.movementResult().dislodgements().forEach((unit, result) -> dislodged.put(unit, result.displacedFrom()));
        List<RetreatOrder> orders = new ArrayList<>();
        Set<UnitId> seen = new HashSet<>();
        // Voluntary disbands use RetreatProcessor's omission path.
        for (Submission source : frame.orders()) {
            UnitId unit = unitAt(dislodged, source);
            require(seen.add(unit), "Duplicate retreat submission");
            if (source.type().equals("Retreat"))
                orders.add(new RetreatOrder(unit, need(source.to())));
            else
                require(source.type().equals("Disband"), "Expected a retreat or disband");
        }
        require(seen.equals(dislodged.keySet()), "Retreat submissions do not cover the dislodged units");
        return List.copyOf(orders);

    }

    public static List<AdjustmentOrder> adjustments(Frame frame, BoardState board) {

        require(frame.name().equals("Builds"), "Expected an adjustment frame");
        List<AdjustmentOrder> orders = new ArrayList<>();
        for (Submission source : frame.orders())
            orders.add(switch (source.type()) {
                case "Build Army" -> new BuildOrder(source.owner(), UnitType.ARMY, need(source.origin()));
                case "Build Fleet" -> new BuildOrder(source.owner(), UnitType.FLEET, need(source.origin()));
                case "Destroy" -> new DisbandOrder(source.owner(), unitAt(board.locations(), source));
                case "Wait" -> new WaiveOrder(source.owner());
                default -> throw new ParseException("Expected an adjustment submission");
            });
        return List.copyOf(orders);

    }

    public static UnitId unitAt(Map<UnitId, Province> locations, Submission source) {

        List<UnitId> matches = UnitLookup.matchingAt(locations, need(source.origin()), unit ->
                unit.owner() == source.owner() && (source.unitType() == null || unit.unitType() == source.unitType()));
        require(matches.size() == 1, "Expected one issuing unit: " + source);
        UnitId unit = matches.getFirst();
        require(source.origin().parent == null || locations.get(unit) == source.origin(),
                "Issuing coast differs from the current unit location");
        return unit;

    }

    private void checkBorders(Map<String, Object> raw, Province origin) {

        for (String field : List.of("borders", "coastalBorders")) {
            Set<Province> seen = new HashSet<>();
            Set<Province> army = new HashSet<>();
            Set<Province> fleet = new HashSet<>();
            for (Object value : array(required(raw, field))) {
                Map<String, Object> edge = object(value);
                Province destination = territory(required(edge, "id"));
                require(seen.add(destination), "Duplicate border at " + origin);
                if (flag(edge, "army"))
                    army.add(destination);
                if (flag(edge, "fleet"))
                    fleet.add(destination);
            }
            if (!field.equals("coastalBorders"))
                continue;
            for (UnitType type : UnitType.values()) {
                Set<Province> expected = new HashSet<>();
                if (OrderForms.isValidUnitLocation(type, origin))
                    for (Province destination : territories.values())
                        if (origin.isAdjacentTo(destination)
                                && OrderForms.isPotentialMove(type, origin, destination))
                            expected.add(destination);
                require(expected.equals(type == UnitType.ARMY ? army : fleet),
                        "Movement borders differ for " + type + " at " + origin);
            }
        }

    }

    private static Province province(String name) {

        return switch (name) {
            case "St. Petersburg" -> Province.Stp;
            case "St. Petersburg (North Coast)" -> Province.StpNC;
            case "St. Petersburg (South Coast)" -> Province.StpSC;
            case "Spain (North Coast)" -> Province.SpaNC;
            case "Spain (South Coast)" -> Province.SpaSC;
            case "Bulgaria (North Coast)" -> Province.BulEC;
            case "Bulgaria (South Coast)" -> Province.BulSC;
            case "Skagerrack" -> Province.SKA;
            case "Heligoland Bight" -> Province.HEL;
            case "Gulf of Lyons" -> Province.LYO;
            default -> Arrays.stream(Province.values()).filter(p -> p.fullName.equals(name))
                    .findFirst().orElseThrow(() -> new ParseException("Unknown Classic territory: " + name));
        };

    }

    private Nation country(Object value) {
        Nation nation = countries.get(JSON.integer(value, "countryID"));
        require(nation != null, "Unknown country ID: " + value);
        return nation;
    }

    private Province territory(Object value) {
        Province province = territories.get(JSON.integer(value, "terrID"));
        require(province != null, "Unknown territory ID: " + value);
        return province;
    }

    private Province nullableTerritory(Object value) {
        return value == null ? null : territory(value);
    }

    private static UnitType nullableUnitType(Object value) {
        return value == null ? null : unitType(value);
    }

    private static UnitType unitType(Object value) {
        return switch (JSON.string(value, "unitType")) {
            case "Army" -> UnitType.ARMY;
            case "Fleet" -> UnitType.FLEET;
            default -> throw new ParseException("Unknown unit type");
        };
    }

    private static Province need(Province value) {
        require(value != null, "Required order location is null");
        return value;
    }

    private static Object required(Map<String, Object> raw, String key) {
        return JSON.required(raw, key, "$");
    }

    private static Map<String, Object> object(Object value) {
        return JSON.object(value, "$");
    }

    private static List<Object> array(Object value) {
        return JSON.array(value, "$");
    }

    private static String text(Map<String, Object> raw, String key) {
        return JSON.string(required(raw, key), key);
    }

    private static int number(Map<String, Object> raw, String key) {
        return JSON.integer(required(raw, key), key);
    }

    private static boolean flag(Map<String, Object> raw, String key) {
        Object value = required(raw, key);
        require(value instanceof Boolean, "Expected boolean: " + key);
        return Boolean.TRUE.equals(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new ParseException(message);
    }


    public record SourceGame(String key, String sourceHash, String pressType, List<Frame> frames) {
        public SourceGame {
            frames = List.copyOf(frames);
        }
    }

    public record PlacedUnit(Nation owner, UnitType type, Province location, boolean retreating) { }

    public record Submission(String type, Nation owner, UnitType unitType, Province origin,
                             Province to, Province from, boolean viaConvoy, boolean success, boolean dislodged) { }

    public static final class UnrepresentedConvoyException extends UnsupportedOperationException {
        private UnrepresentedConvoyException(Submission source) {
            super("Unrepresented convoy choice: " + source);
        }
    }

    public record Frame(int turn, String name, List<PlacedUnit> units, Map<Province, Nation> owners,
                        Set<Province> reportedCenters, List<Submission> orders) {

        public Frame {
            units = List.copyOf(units);
            owners = Collections.unmodifiableMap(new LinkedHashMap<>(owners));
            reportedCenters = Set.copyOf(reportedCenters);
            orders = List.copyOf(orders);
        }

        public GameMoment moment() {
            GamePhase phase = switch (name) {
                case "Diplomacy" -> turn % 2 == 0 ? GamePhase.SPRING_MOVEMENT : GamePhase.FALL_MOVEMENT;
                case "Retreats" -> turn % 2 == 0 ? GamePhase.SPRING_RETREAT : GamePhase.FALL_RETREAT;
                case "Builds" -> GamePhase.WINTER_ADJUSTMENT;
                default -> throw new IllegalStateException("Finished has no playable moment");
            };
            return new GameMoment(Math.addExact(1901, turn / 2), phase);
        }

        /**
         * Projects active units and positive center owners. Unlisted centers
         * are neutral under the verified Classic sparse-archive contract;
         * verifyOwnership and replay enforce ownership continuity.
         */
        public BoardState board() {
            Map<UnitId, Province> locations = new LinkedHashMap<>();
            for (PlacedUnit unit : units)
                if (!unit.retreating())
                    require(locations.putIfAbsent(new UnitId(unit.owner(), unit.type(), unit.location()),
                            unit.location()) == null, "Duplicate source unit");
            return new BoardState(locations, owners);
        }

    }

}