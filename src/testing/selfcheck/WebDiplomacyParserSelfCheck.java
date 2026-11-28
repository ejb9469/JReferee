package testing.selfcheck;

import domain.*;
import game.GamePhase;
import game.record.GameHistory;
import io.GameHistoryJson;
import io.catalog.SourceFingerprints;
import parsing.webdiplomacy.WebDiplomacyParser;

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
        require(history.equals(GameHistoryJson.read(GameHistoryJson.write(history))),
                "Snapshot archive round-trip lost information");
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
        System.out.println("WebDiplomacyParserSelfCheck passed: " + checks + " checks");
    }

    public static String bundle() {
        return bundle(historyJson());
    }

    private static String bundle(String history) {
        String game = """
                {"schema":1,"gameID":17,"file":"game","phase":"Finished","turn":1,
                 "variant":{"id":1,"name":"Classic"}}
                """;
        return "{\"schema\":\"jreferee-webdiplomacy-raw-v1\",\"gameID\":17,"
                + document("game", game) + "," + document("history", history) + ","
                + document("variant", variant()) + "}";
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
            territories.add("{\"id\":" + id(province) + ",\"name\":\"" + name + "\"}");
        }
        return "{\"schema\":1,\"variantID\":1,\"mapID\":1,\"name\":\"Classic\",\"countries\":["
                + countries + "],\"territories\":[" + territories + "]}";
    }

    private static String historyJson() {
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
                order(Nation.GERMANY, "Army", Province.Mun, "Destroy", null, null, false, true, false),
                order(Nation.ITALY, null, null, "Wait", null, null, false, true, false));
        return "{\"schema\":1,\"gameID\":17,\"file\":\"history\",\"phases\":["
                + phase(0, "Diplomacy", movementUnits, movementOrders) + ","
                + phase(0, "Retreats", retreats, retreatOrders) + ","
                + phase(1, "Diplomacy", movementUnits, "") + ","
                + phase(1, "Builds", movementUnits, builds) + ","
                + phase(1, "Finished", movementUnits, "") + "]}";
    }

    private static String phase(int turn, String phase, String units, String orders) {
        return "{\"turn\":" + turn + ",\"phase\":\"" + phase + "\",\"units\":[" + units
                + "],\"centers\":[{\"terrID\":" + id(Province.Par) + ",\"countryID\":" + country(Nation.FRANCE)
                + "},{\"terrID\":" + id(Province.Bel) + ",\"countryID\":0}],\"orders\":[" + orders + "]}";
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
