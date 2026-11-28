package io;

import domain.*;
import game.*;
import game.record.GameHistory;
import io.json.JsonAccess;
import io.json.JsonReader;
import parsing.ParseException;
import phase.UnitId;

import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Versioned snapshot archive. Preserves unit identities and source annotations;
 * processor results and private press remain in the original GameRecord.
 */
public final class GameHistoryJson {

    private static final String SCHEMA = "jreferee-game-history-v1";
    private static final JsonAccess JSON = new JsonAccess(ParseException::new);

    private GameHistoryJson() { }

    public static String write(GameHistory history) {
        StringBuilder out = new StringBuilder("{");
        field(out, "schema", SCHEMA);
        out.append(',');
        field(out, "rulesetId", history.rulesetId());
        out.append(",\"positions\":[");
        boolean first = true;
        for (var position : history.positions()) {
            if (!first)
                out.append(',');
            first = false;
            out.append('{');
            field(out, "sourcePhase", position.sourcePhase());
            out.append(',');
            field(out, "status", position.status());
            out.append(',');
            field(out, "year", position.moment().year());
            out.append(',');
            field(out, "phase", position.moment().gamePhase());
            out.append(',');
            field(out, "ordersKnown", position.ordersKnown());
            out.append(',');
            field(out, "dislodgementsKnown", position.dislodgementsKnown());
            out.append(',');
            field(out, "terminal", position.terminal());
            out.append(',');
            field(out, "boardError", position.boardError());
            out.append(",\"reportedCenters\":").append(reportedCenters(position.reportedCenters()));
            out.append(",\"units\":").append(position.board() == null ? "null" : units(position.board().locations()));
            out.append(",\"dislodged\":").append(units(position.dislodged()));
            out.append(",\"centers\":");
            if (position.board() == null) {
                out.append("null");
            } else {
                out.append('{');
                boolean firstCenter = true;
                for (var center : position.board().owners().entrySet()) {
                    if (!firstCenter)
                        out.append(',');
                    firstCenter = false;
                    field(out, center.getKey().name(), center.getValue());
                }
                out.append('}');
            }
            out.append(",\"orders\":[");
            boolean firstOrder = true;
            for (var order : position.orders()) {
                if (!firstOrder)
                    out.append(',');
                firstOrder = false;
                out.append('{');
                field(out, "owner", order.owner());
                out.append(',');
                field(out, "unitType", order.unitType());
                out.append(',');
                field(out, "origin", order.origin());
                out.append(',');
                field(out, "orderType", order.orderType());
                out.append(',');
                field(out, "target", order.target());
                out.append(',');
                field(out, "auxiliaryTarget", order.auxiliaryTarget());
                out.append(',');
                field(out, "viaConvoy", order.viaConvoy());
                out.append('}');
            }
            out.append("],\"annotations\":[");
            boolean firstAnnotation = true;
            for (var annotation : position.annotations()) {
                if (!firstAnnotation)
                    out.append(',');
                firstAnnotation = false;
                out.append('{');
                field(out, "nation", annotation.nation());
                out.append(',');
                field(out, "origin", annotation.origin());
                out.append(',');
                field(out, "retreatOrder", annotation.retreatOrder());
                out.append(',');
                field(out, "successful", annotation.successful());
                out.append(',');
                field(out, "reason", annotation.reason());
                out.append('}');
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    public static GameHistory read(String source) {
        Map<String, Object> root = JSON.object(JsonReader.read(source), "$");
        if (!SCHEMA.equals(root.get("schema")))
            throw new ParseException("$.schema", "Unsupported history schema");
        List<GameHistory.Position> positions = new ArrayList<>();
        for (Object value : JSON.array(root.get("positions"), "$.positions")) {
            Map<String, Object> position = JSON.object(value, "$.positions[]");
            Map<Province, Nation> centers = new LinkedHashMap<>();
            Object rawCenters = JSON.required(position, "centers", "position");
            Object rawUnits = JSON.required(position, "units", "position");
            if (rawCenters != null)
                JSON.object(rawCenters, "centers").forEach((province, nation) ->
                        centers.put(Province.valueOf(province), Nation.valueOf(JSON.string(nation, "centers." + province))));
            if ((rawCenters == null) != (rawUnits == null))
                throw new ParseException("position", "Unavailable boards must omit both units and center owners");
            Set<Province> reportedCenters = null;
            if (position.get("reportedCenters") != null) {
                reportedCenters = EnumSet.noneOf(Province.class);
                for (Object center : JSON.array(position.get("reportedCenters"), "reportedCenters"))
                    if (!reportedCenters.add(Province.valueOf(JSON.string(center, "reportedCenters[]"))))
                        throw new ParseException("reportedCenters", "Duplicate source center row");
            }
            List<GameHistory.Submission> orders = new ArrayList<>();
            for (Object orderValue : JSON.array(position.get("orders"), "orders")) {
                Map<String, Object> order = JSON.object(orderValue, "orders[]");
                orders.add(new GameHistory.Submission(
                        Nation.valueOf(text(order, "owner")), optionalEnum(order, "unitType", UnitType.class),
                        optionalEnum(order, "origin", Province.class), OrderType.valueOf(text(order, "orderType")),
                        optionalEnum(order, "target", Province.class), optionalEnum(order, "auxiliaryTarget", Province.class),
                        optionalBoolean(order, "viaConvoy")));
            }
            List<GameHistory.Annotation> annotations = new ArrayList<>();
            for (Object annotationValue : JSON.array(position.get("annotations"), "annotations")) {
                Map<String, Object> annotation = JSON.object(annotationValue, "annotations[]");
                annotations.add(new GameHistory.Annotation(Nation.valueOf(text(annotation, "nation")),
                        optionalEnum(annotation, "origin", Province.class), bool(annotation, "retreatOrder"),
                        optionalBoolean(annotation, "successful"), JSON.optionalString(annotation, "reason", "annotations[]")));
            }
            Object sourcePhase = JSON.required(position, "sourcePhase", "position");
            positions.add(new GameHistory.Position(
                    sourcePhase == null ? null : JSON.integer(sourcePhase, "sourcePhase"),
                    text(position, "status"),
                    new GameMoment(JSON.integer(position.get("year"), "year"), GamePhase.valueOf(text(position, "phase"))),
                    rawUnits == null ? null : new BoardState(readUnits(rawUnits), centers), orders, annotations,
                    readUnits(position.get("dislodged")), bool(position, "dislodgementsKnown"),
                    bool(position, "ordersKnown"), bool(position, "terminal"), reportedCenters,
                    JSON.optionalString(position, "boardError", "position")));
        }
        return new GameHistory(JSON.optionalString(root, "rulesetId", "$"), positions);
    }

    public static String reportedCenters(Set<Province> centers) {
        if (centers == null)
            return "null";
        StringJoiner out = new StringJoiner(",", "[", "]");
        centers.stream().sorted(Comparator.comparing(Enum::name))
                .forEach(center -> out.add("\"" + center.name() + "\""));
        return out.toString();
    }

    private static String units(Map<UnitId, Province> units) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (var entry : units.entrySet()) {
            if (!first)
                out.append(',');
            first = false;
            UnitId unit = entry.getKey();
            out.append('{');
            field(out, "id", unit.value());
            out.append(',');
            field(out, "nation", unit.owner());
            out.append(',');
            field(out, "type", unit.unitType());
            out.append(',');
            field(out, "origin", unit.origin());
            out.append(',');
            field(out, "location", entry.getValue());
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static Map<UnitId, Province> readUnits(Object value) {
        Map<UnitId, Province> units = new LinkedHashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (Object item : JSON.array(value, "units")) {
            Map<String, Object> unit = JSON.object(item, "units[]");
            UUID id = UUID.fromString(text(unit, "id"));
            if (!ids.add(id))
                throw new ParseException("units", "Duplicate unit ID");
            units.put(new UnitId(id, Nation.valueOf(text(unit, "nation")), UnitType.valueOf(text(unit, "type")),
                    Province.valueOf(text(unit, "origin"))), Province.valueOf(text(unit, "location")));
        }
        return units;
    }

    private static void field(StringBuilder out, String name, Object value) {
        appendString(out, name);
        out.append(':');
        switch (value) {
            case null -> out.append("null");
            case Boolean flag -> out.append(flag);
            case Integer number -> out.append(number);
            case Enum<?> constant -> appendString(out, constant.name());
            case String text -> appendString(out, text);
            case UUID id -> appendString(out, id.toString());
            default -> throw new IllegalArgumentException("Unsupported field: " + name);
        }
    }

    private static String text(Map<String, Object> value, String key) {
        return JSON.string(value.get(key), key);
    }

    private static boolean bool(Map<String, Object> value, String key) {
        return JSON.bool(value.get(key), key);
    }

    private static Boolean optionalBoolean(Map<String, Object> value, String key) {
        Object field = JSON.required(value, key, "$");
        return field == null ? null : JSON.bool(field, key);
    }

    private static <E extends Enum<E>> E optionalEnum(Map<String, Object> value, String key, Class<E> type) {
        Object field = JSON.required(value, key, "$");
        return field == null ? null : Enum.valueOf(type, JSON.string(field, key));
    }
}
