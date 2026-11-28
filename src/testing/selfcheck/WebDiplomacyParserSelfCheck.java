package testing.selfcheck;

import domain.*;
import contracts.OrderForms;
import game.GamePhase;
import game.StandardGameFactory;
import game.record.GameHistory;
import io.GameHistoryJson;
import io.catalog.SourceFingerprints;
import parsing.webdiplomacy.WebDiplomacyParser;
import parsing.webdiplomacy.WebDiplomacyAdapter;
import _app.WebDiplomacyReplayApp;

import java.util.*;

import static io.json.JsonEscaper.appendString;

public final class WebDiplomacyParserSelfCheck {

    private static int checks;

    private WebDiplomacyParserSelfCheck() { }

    public static void main(String[] args) {
        GameHistory history = new WebDiplomacyParser().parse(bundle());
        var positions = history.positions();
        require(positions.size() == 5 && positions.getLast().terminal(), "Preserve all source positions");
        require(positions.getFirst().moment().year() == 1901
                && positions.get(1).moment().gamePhase() == GamePhase.SPRING_RETREAT
                && positions.get(2).moment().gamePhase() == GamePhase.FALL_MOVEMENT
                && positions.get(3).moment().gamePhase() == GamePhase.WINTER_ADJUSTMENT,
                "Translate turns and phases");
        require(positions.getLast().moment().equals(positions.get(3).moment()),
                "Finished must not invent a following turn");
        var orders = positions.getFirst().orders();
        require(orders.get(0).orderType() == OrderType.MOVE
                && orders.get(0).target() == Province.Bel && Boolean.TRUE.equals(orders.get(0).viaConvoy()),
                "Retain explicit convoy move");
        require(orders.get(1).orderType() == OrderType.CONVOY
                && orders.get(1).target() == Province.Lon && orders.get(1).auxiliaryTarget() == Province.Bel,
                "Translate convoy endpoints");
        require(orders.get(2).orderType() == OrderType.SUPPORT
                && orders.get(2).target() == Province.Lon && orders.get(2).auxiliaryTarget() == Province.Bel,
                "Translate support-move endpoints");
        require(orders.get(3).target() == Province.Par && orders.get(3).auxiliaryTarget() == null,
                "Translate support-hold target");
        require(!positions.getFirst().annotations().getFirst().successful()
                && positions.getFirst().annotations().get(2).reason().contains("DISLODGED"),
                "Preserve source results independently of the next board");
        var retreat = positions.get(1);
        require(retreat.board().locations().containsValue(Province.Par)
                && retreat.dislodged().containsValue(Province.Par), "Keep occupier and dislodged unit separately");
        require(retreat.orders().getFirst().orderType() == OrderType.RETREAT
                && retreat.orders().getFirst().target() == null, "Retain explicit retreat disband");
        require(retreat.orders().get(1).origin() == Province.StpSC
                && retreat.orders().get(1).target() == Province.BOT, "Keep exact retreat coast");
        require(positions.get(2).orders().isEmpty(), "Do not manufacture holds");
        require(positions.get(3).orders().getFirst().origin() == Province.BulEC
                && positions.get(3).orders().getFirst().unitType() == UnitType.FLEET,
                "webDiplomacy Bulgaria North Coast is the internal east coast");
        require(positions.get(3).orders().get(2).orderType() == OrderType.DESTROY
                && positions.get(3).orders().get(3).orderType() == OrderType.WAIVE,
                "Translate adjustments");
        require(positions.get(3).orders().get(2).unitType() == UnitType.ARMY,
                "Infer a Destroy's omitted unit type using the existing source adapter");
        require(positions.getFirst().reportedCenters().equals(Set.of(Province.Par, Province.Bel))
                && positions.getFirst().board().owners().equals(Map.of(Province.Par, Nation.FRANCE)),
                "Only parent supply-center rows contribute ownership and reported visibility");
        require(history.equals(GameHistoryJson.read(GameHistoryJson.write(history))),
                "Snapshot archive round-trip lost information");
        GameHistory overlapping = new WebDiplomacyParser().parse(overlappingBundle());
        require(overlapping.positions().getLast().board() == null
                && overlapping.positions().getLast().boardError().contains("Multiple units")
                && overlapping.positions().getFirst().board() != null,
                "Overlapping terminal units must remain an explicit unavailable position");
        require(overlapping.equals(GameHistoryJson.read(GameHistoryJson.write(overlapping))),
                "Unavailable boards and sparse center visibility must survive archiving");
        var adjacent = new WebDiplomacyParser().parse(bundle(historyJson().replace(
                "\"type\":\"Move\",\"toTerrID\":" + id(Province.Bel),
                "\"type\":\"Move\",\"toTerrID\":" + id(Province.Wal)))).positions().getFirst().orders().getFirst();
        require(adjacent.target() == Province.Wal && Boolean.TRUE.equals(adjacent.viaConvoy()),
                "Display must preserve convoy choices that the replay model cannot represent");
        expectInvalid(bundle().replace("\"gameID\":17", "\"gameID\":18"), "Mismatched envelope identity");
        expectInvalid(bundle().replace("\"sha256\":\"", "\"sha256\":\"x"), "Bad document fingerprint");
        expectInvalid(bundle(historyJson().replace("\"Retreats\"", "\"Bogus\"")), "Unknown phase");
        expectInvalid(bundle(historyJson().replace("\"turn\":1", "\"turn\":0")), "Repeated or misplaced phase");
        expectInvalid(bundle(historyJson().replace("\"toTerrID\":" + id(Province.Bel), "\"toTerrID\":99999")),
                "Unknown territory ID");
        expectInvalid(bundle(historyJson().replace("\"type\":\"Move\"", "\"type\":\"Teleport\"")), "Unknown order");
        expectInvalid(bundle(historyJson().replace("\"viaConvoy\":true", "\"viaConvoy\":\"Yes\"")), "Invalid flag");
        expectInvalid(bundle(historyJson().replace("\"terrID\":" + id(Province.Lon), "\"terrID\":null")),
                "Missing issuing province");
        expectInvalid(bundle(historyJson(),
                variant().replace("\"buildAnywhere\":false", "\"buildAnywhere\":true")), "Non-Classic build rule");
        expectInvalid(bundle(historyJson(), variant().replace("\"coastParentID\":" + id(Province.Bul),
                "\"coastParentID\":" + id(Province.Spa))), "Mismatched coast parent");
        mechanics();
        System.out.println("WebDiplomacyParserSelfCheck passed: " + checks + " checks");
    }

    private static void mechanics() {
        String fixture = mechanicsFixture(false);
        var source = WebDiplomacyAdapter.readFixture(fixture);
        var opening = source.frames().getFirst();
        require(WebDiplomacyAdapter.movement(opening, opening.board()).size() == 22,
                "Reviewed fixture translation must retain complete movement inputs");
        var replay = WebDiplomacyReplayApp.replay(source);
        require(replay.findings().isEmpty() && replay.counts().get("movements_resolved") == 1,
                "Shared decoding must preserve the existing mechanics replay path");
        try {
            WebDiplomacyAdapter.readFixture(fixture.replace("\"split\":\"TRAIN\"", "\"split\":\"TEST\""));
            throw new AssertionError("Fixture TRAIN gate was relaxed");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        var convoy = WebDiplomacyAdapter.readFixture(mechanicsFixture(true)).frames().getFirst();
        try {
            WebDiplomacyAdapter.movement(convoy, convoy.board());
            throw new AssertionError("Replay admitted an unrepresented adjacent convoy choice");
        } catch (WebDiplomacyAdapter.UnrepresentedConvoyException expected) {
            checks++;
        }
        // Remove a previously positive row from just the terminal snapshot.
        int terminal = historyJson().indexOf("\"phase\":\"Finished\"");
        String raw = historyJson();
        String tail = raw.substring(terminal).replace(
                "\"terrID\":" + id(Province.Par) + ",\"countryID\":" + country(Nation.FRANCE),
                "\"terrID\":" + id(Province.Par) + ",\"countryID\":0");
        expectInvalid(bundle(raw.substring(0, terminal) + tail), "Lost positive ownership at Finished");
    }

    private static String mechanicsFixture(boolean adjacentConvoy) {
        String key = "1";
        for (int candidate = 1; ; candidate++) {
            String hash = SourceFingerprints.sha256Hex("7\nWEBDIPLOMACY\n" + candidate);
            if (Long.remainderUnsigned(Long.parseUnsignedLong(hash.substring(0, 16), 16), 100) < 80) {
                key = Integer.toString(candidate);
                break;
            }
        }
        var board = StandardGameFactory.create1901().board();
        StringJoiner units = new StringJoiner(",");
        StringJoiner orders = new StringJoiner(",");
        StringJoiner centers = new StringJoiner(",");
        board.locations().forEach((unit, location) -> {
            units.add(unit(unit.owner(), unit.unitType(), location, false));
            boolean convoy = adjacentConvoy && location == Province.Par;
            orders.add(order(unit.owner(), unit.unitType() == UnitType.ARMY ? "Army" : "Fleet", location,
                    convoy ? "Move" : "Hold", convoy ? Province.Bur : null, null, convoy, true, false));
        });
        board.owners().forEach((location, nation) -> centers.add(
                "{\"terrID\":" + id(location) + ",\"countryID\":" + country(nation) + "}"));
        String positions = "{\"turn\":0,\"phase\":\"Diplomacy\",\"units\":[" + units
                + "],\"centers\":[" + centers + "],\"orders\":[" + orders + "]},"
                + "{\"turn\":0,\"phase\":\"Finished\",\"units\":[" + units
                + "],\"centers\":[" + centers + "],\"orders\":[]}";
        return "{\"schema\":\"jreferee-webdiplomacy-mechanics-fixture-v1\","
                + "\"source\":\"WEBDIPLOMACY\",\"split\":\"TRAIN\",\"game_key\":\"" + key
                + "\",\"source_payload_sha256\":\"" + SourceFingerprints.sha256Hex(positions)
                + "\",\"game\":{\"schema\":1,\"gameID\":" + key
                + ",\"file\":\"game\",\"phase\":\"Finished\",\"gameOver\":\"Drawn\",\"turn\":0,"
                + "\"playerTypes\":\"Members\",\"isSandbox\":false,\"pressType\":\"RegularPress\","
                + "\"variant\":{\"id\":1,\"name\":\"Classic\"}},"
                + "\"history\":{\"schema\":1,\"gameID\":" + key
                + ",\"file\":\"history\",\"phases\":[" + positions + "]},\"variant\":" + variant() + "}";
    }

    public static String bundle() {
        return bundle(historyJson());
    }

    public static String overlappingBundle() {
        return bundle(historyJson(true));
    }

    private static String bundle(String history) {
        return bundle(history, variant());
    }

    private static String bundle(String history, String variant) {
        String game = """
                {"schema":1,"gameID":17,"file":"game","phase":"Finished","turn":1,
                 "variant":{"id":1,"name":"Classic"}}
                """;
        return "{\"schema\":\"jreferee-webdiplomacy-raw-v1\",\"gameID\":17,"
                + document("game", game) + "," + document("history", history) + ","
                + document("variant", variant) + "}";
    }

    private static String document(String name, String json) {
        StringBuilder out = new StringBuilder("\"").append(name).append("\":{\"url\":\"https://example.invalid/")
                .append(name).append(".json\",\"sha256\":\"").append(SourceFingerprints.sha256Hex(json))
                .append("\",\"json\":");
        appendString(out, json);
        return out.append('}').toString();
    }

    private static String variant() {
        StringJoiner countries = new StringJoiner(",");
        for (Nation nation : Nation.values())
            countries.add("{\"countryID\":" + country(nation) + ",\"name\":\"" + nation.name() + "\"}");
        StringJoiner territories = new StringJoiner(",");
        for (Province province : Province.values()) {
            if (province == Province.Swi)
                continue;
            String name = switch (province) {
                case Stp -> "St. Petersburg";
                case SKA -> "Skagerrack";
                case HEL -> "Heligoland Bight";
                case LYO -> "Gulf of Lyons";
                case BulEC -> "Bulgaria (North Coast)";
                case BulSC -> "Bulgaria (South Coast)";
                case StpNC -> "St. Petersburg (North Coast)";
                case StpSC -> "St. Petersburg (South Coast)";
                case SpaNC -> "Spain (North Coast)";
                case SpaSC -> "Spain (South Coast)";
                default -> province.fullName;
            };
            boolean child = province.parent != null;
            boolean splitParent = !child && Arrays.stream(Province.values()).anyMatch(p -> p.parent == province);
            String terrain = province.geography == Geography.WATER ? "Sea"
                    : province.geography == Geography.COASTAL || splitParent ? "Coast" : "Land";
            territories.add("{\"id\":" + id(province) + ",\"name\":\"" + name
                    + "\",\"coastParentID\":" + id(Province.canonical(province))
                    + ",\"coast\":\"" + (child ? "Child" : splitParent ? "Parent" : "No")
                    + "\",\"type\":\"" + terrain + "\",\"supply\":" + (!child && province.supplyCenter)
                    + ",\"homeCountryID\":" + (province.homeowner == null ? "null" : country(province.homeowner))
                    + ",\"borders\":" + borders(province) + ",\"coastalBorders\":" + borders(province) + "}");
        }
        return "{\"schema\":1,\"variantID\":1,\"mapID\":1,\"name\":\"Classic\","
                + "\"supplyCenterCount\":34,\"supplyCenterTarget\":18,\"rules\":{\"buildAnywhere\":false},\"countries\":["
                + countries + "],\"territories\":[" + territories + "]}";
    }

    private static String borders(Province origin) {
        StringJoiner borders = new StringJoiner(",", "[", "]");
        for (Province destination : Province.values()) {
            if (!origin.isAdjacentTo(destination))
                continue;
            boolean army = OrderForms.isPotentialMove(UnitType.ARMY, origin, destination);
            boolean fleet = OrderForms.isPotentialMove(UnitType.FLEET, origin, destination);
            if (army || fleet)
                borders.add("{\"id\":" + id(destination) + ",\"army\":" + army + ",\"fleet\":" + fleet + "}");
        }
        return borders.toString();
    }

    private static String historyJson() {
        return historyJson(false);
    }

    private static String historyJson(boolean overlapping) {
        String movementUnits = String.join(",",
                unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon, false),
                unit(Nation.ENGLAND, UnitType.FLEET, Province.ENG, false),
                unit(Nation.FRANCE, UnitType.ARMY, Province.Par, false),
                unit(Nation.FRANCE, UnitType.FLEET, Province.Bre, false),
                unit(Nation.GERMANY, UnitType.ARMY, Province.Mun, false));
        String movementOrders = String.join(",",
                order(Nation.ENGLAND, "Army", Province.Lon, "Move", Province.Bel, null, true, false, false),
                order(Nation.ENGLAND, "Fleet", Province.ENG, "Convoy", Province.Bel, Province.Lon, false, true, false),
                order(Nation.FRANCE, "Army", Province.Par, "Support move", Province.Bel, Province.Lon, false, false, true),
                order(Nation.FRANCE, "Fleet", Province.Bre, "Support hold", Province.Par, null, false, true, false),
                order(Nation.GERMANY, "Army", Province.Mun, "Hold", null, null, false, true, false));
        String retreats = String.join(",",
                unit(Nation.GERMANY, UnitType.ARMY, Province.Par, false),
                unit(Nation.FRANCE, UnitType.ARMY, Province.Par, true),
                unit(Nation.RUSSIA, UnitType.FLEET, Province.StpSC, true));
        String retreatOrders = String.join(",",
                order(Nation.FRANCE, "Army", Province.Par, "Disband", null, null, false, true, false),
                order(Nation.RUSSIA, "Fleet", Province.StpSC, "Retreat", Province.BOT, null, false, false, false));
        String builds = String.join(",",
                order(Nation.TURKEY, null, Province.BulEC, "Build Fleet", null, null, false, true, false),
                order(Nation.FRANCE, null, Province.Par, "Build Army", null, null, false, false, false),
                order(Nation.GERMANY, null, Province.Mun, "Destroy", null, null, false, true, false),
                order(Nation.ITALY, null, null, "Wait", null, null, false, true, false));
        return "{\"schema\":1,\"gameID\":17,\"file\":\"history\",\"phases\":["
                + phase(0, "Diplomacy", movementUnits, movementOrders) + ","
                + phase(0, "Retreats", retreats, retreatOrders) + ","
                + phase(1, "Diplomacy", movementUnits, "") + ","
                + phase(1, "Builds", movementUnits, builds) + ","
                + phase(1, "Finished", movementUnits + (overlapping
                    ? "," + unit(Nation.FRANCE, UnitType.ARMY, Province.Mun, false) : ""), "") + "]}";
    }

    private static String phase(int turn, String phase, String units, String orders) {
        return "{\"turn\":" + turn + ",\"phase\":\"" + phase + "\",\"units\":[" + units
                + "],\"centers\":[{\"terrID\":" + id(Province.Par) + ",\"countryID\":" + country(Nation.FRANCE)
                + "},{\"terrID\":" + id(Province.Bel) + ",\"countryID\":0},"
                + "{\"terrID\":" + id(Province.Gas) + ",\"countryID\":" + country(Nation.FRANCE) + "},"
                + "{\"terrID\":" + id(Province.SpaSC) + ",\"countryID\":" + country(Nation.FRANCE) + "}],"
                + "\"orders\":[" + orders + "]}";
    }

    private static String unit(Nation nation, UnitType type, Province province, boolean retreating) {
        return "{\"countryID\":" + country(nation) + ",\"type\":\"" + (type == UnitType.ARMY ? "Army" : "Fleet")
                + "\",\"terrID\":" + id(province) + ",\"retreating\":" + retreating + "}";
    }

    private static String order(Nation nation, String unit, Province origin, String type, Province to,
                                Province from, boolean convoy, boolean success, boolean dislodged) {
        return "{\"countryID\":" + country(nation) + ",\"unitType\":" + (unit == null ? "null" : "\"" + unit + "\"")
                + ",\"terrID\":" + id(origin) + ",\"type\":\"" + type + "\",\"toTerrID\":" + id(to)
                + ",\"fromTerrID\":" + id(from) + ",\"viaConvoy\":" + convoy + ",\"success\":" + success
                + ",\"dislodged\":" + dislodged + "}";
    }

    private static Integer id(Province province) {
        return province == null ? null : 100 + province.ordinal();
    }

    private static int country(Nation nation) {
        return 20 - nation.ordinal();
    }

    private static void expectInvalid(String source, String message) {
        try {
            new WebDiplomacyParser().parse(source);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
        checks++;
    }
}
