package parsing.diplobn;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GamePhase;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.retreats.RetreatOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Parses a DiploBN Game JSON document into JReferee board snapshots and typed
 * phase orders.
 *
 * <p>This parser intentionally has no network behavior. Callers are
 * responsible for downloading game JSON from DiploBN, then supplying its
 * contents to {@link #parse(String)}.</p>
 */
public final class DiploBNParser {


    /**
     * Parses one complete DiploBN game JSON document.
     */
    public DiploBNGame parse(String source) {

        Object parsed = JsonReader.read(
                Objects.requireNonNull(source, "source"));

        Map<String, Object> game = object(parsed, "$");

        List<Object> rawPhases = array(
                required(game, "GamePhases", "$"),
                "$.GamePhases");

        List<DiploBNPhase> phases = new ArrayList<>();

        for (int index = 0; index < rawPhases.size(); index++) {
            phases.add(parsePhase(
                    object(rawPhases.get(index),
                            "$.GamePhases[" + index + "]"),
                    "$.GamePhases[" + index + "]"
            ));
        }

        return new DiploBNGame(
                optionalString(game, "Competition", "$"),
                optionalString(game, "GameLabel", "$"),
                optionalString(game, "URL", "$"),
                phases
        );

    }


    private DiploBNPhase parsePhase(
            Map<String, Object> rawPhase,
            String path
    ) {

        int sourcePhase = integer(
                required(rawPhase, "Phase", path),
                path + ".Phase");

        String status = string(
                required(rawPhase, "Status", path),
                path + ".Status");

        GamePhase gamePhase = toGamePhase(
                sourcePhase,
                status,
                path);

        Map<UnitId, Province> units = parseUnits(
                optionalObject(rawPhase, "Units", path),
                path + ".Units");

        Map<Province, Nation> owners = parseSupplyCenters(
                optionalObject(rawPhase, "SupplyCenters", path),
                path + ".SupplyCenters");

        BoardState board = new BoardState(units, owners);

        ParsedOrders submittedOrders = parseSubmittedOrders(
                optionalObject(rawPhase, "Orders", path),
                units,
                board,
                gamePhase,
                false,
                path + ".Orders");

        ParsedOrders submittedRetreatOrders = parseSubmittedOrders(
                optionalObject(rawPhase, "RetreatOrders", path),
                units,
                board,
                gamePhase,
                true,
                path + ".RetreatOrders");

        return new DiploBNPhase(
                sourcePhase,
                status,
                gamePhase,
                board,
                submittedOrders.sourceMovementOrders,
                submittedOrders.movementOrders,
                submittedRetreatOrders.retreatOrders,
                submittedOrders.adjustmentOrders,
                submittedOrders.resolutions,
                submittedRetreatOrders.resolutions
        );

    }

    private Map<UnitId, Province> parseUnits(
            Map<String, Object> rawUnits,
            String path
    ) {

        Map<UnitId, Province> units = new LinkedHashMap<>();

        if (rawUnits == null)
            return units;

        for (Map.Entry<String, Object> entry : rawUnits.entrySet()) {

            Nation nation = nation(entry.getKey(), path + "." + entry.getKey());

            List<Object> rawNationUnits = array(
                    entry.getValue(),
                    path + "." + entry.getKey());

            for (int index = 0; index < rawNationUnits.size(); index++) {

                String unitPath = path
                        + "." + entry.getKey()
                        + "[" + index + "]";

                List<Object> rawUnit = array(
                        rawNationUnits.get(index),
                        unitPath);

                requireSize(rawUnit, 2, unitPath);

                UnitType unitType = unitType(
                        string(rawUnit.get(0), unitPath + "[0]"),
                        unitPath + "[0]");

                Province location = location(
                        rawUnit.get(1),
                        unitPath + "[1]");

                UnitId unit = new UnitId(
                        nation,
                        unitType,
                        location);

                if (units.putIfAbsent(unit, location) != null)
                    throw new ParseException(
                            unitPath,
                            "Duplicate unit identity: " + unit);

            }

        }

        return units;

    }

    private Map<Province, Nation> parseSupplyCenters(
            Map<String, Object> rawCenters,
            String path
    ) {

        Map<Province, Nation> owners = new LinkedHashMap<>();

        if (rawCenters == null)
            return owners;

        for (Map.Entry<String, Object> entry : rawCenters.entrySet()) {

            Nation nation = nation(entry.getKey(), path + "." + entry.getKey());

            List<Object> rawNationCenters = array(
                    entry.getValue(),
                    path + "." + entry.getKey());

            for (int index = 0; index < rawNationCenters.size(); index++) {
                Province center = province(
                        string(rawNationCenters.get(index),
                                path + "." + entry.getKey()
                                        + "[" + index + "]"),
                        path + "." + entry.getKey()
                                + "[" + index + "]");

                Province canonicalCenter = Province.canonical(center);

                Nation previousOwner = owners.putIfAbsent(
                        canonicalCenter,
                        nation);

                if (previousOwner != null && previousOwner != nation)
                    throw new ParseException(
                            path,
                            "Supply center has conflicting owners: "
                                    + canonicalCenter);

            }

        }

        return owners;

    }

    private ParsedOrders parseSubmittedOrders(
            Map<String, Object> rawOrders,
            Map<UnitId, Province> units,
            BoardState board,
            GamePhase gamePhase,
            boolean retreatOrders,
            String path
    ) {

        ParsedOrders parsed = new ParsedOrders();

        if (rawOrders == null)
            return parsed;

        DiploBNOrderTranslator movementOrderTranslator =
                new DiploBNOrderTranslator();

        for (Map.Entry<String, Object> entry : rawOrders.entrySet()) {

            Nation nation = nation(entry.getKey(), path + "." + entry.getKey());

            List<Object> rawNationOrders = array(
                    entry.getValue(),
                    path + "." + entry.getKey());

            for (int index = 0; index < rawNationOrders.size(); index++) {

                String orderPath = path
                        + "." + entry.getKey()
                        + "[" + index + "]";

                List<Object> rawOrderAndResult = array(
                        rawNationOrders.get(index),
                        orderPath);

                if (rawOrderAndResult.size() < 2
                        || rawOrderAndResult.size() > 3)
                    throw new ParseException(
                            orderPath,
                            "OrderAndResolution must contain two or three values");

                Province issuingProvince = province(
                        string(rawOrderAndResult.get(0),
                                orderPath + "[0]"),
                        orderPath + "[0]");

                Object rawOrder = rawOrderAndResult.get(1);

                if (retreatOrders) {
                    RetreatOrder retreatOrder = parseRetreatOrder(
                            nation,
                            issuingProvince,
                            rawOrder,
                            units,
                            orderPath + "[1]"
                    );

                    /*
                     * JReferee represents a retreat disband as the absence of
                     * a RetreatOrder. RetreatProcessor then destroys the
                     * dislodged unit during resolution.
                     */
                    if (retreatOrder != null)
                        parsed.retreatOrders.add(retreatOrder);
                } else if (gamePhase.isAdjustment()) {
                    parsed.adjustmentOrders.add(parseAdjustmentOrder(
                            nation,
                            issuingProvince,
                            rawOrder,
                            units,
                            orderPath + "[1]"
                    ));
                } else {
                    DiploBNOrder sourceMovementOrder =
                            parseMovementOrder(
                                    nation,
                                    issuingProvince,
                                    rawOrder,
                                    units,
                                    orderPath + "[1]");

                    parsed.sourceMovementOrders.add(
                            sourceMovementOrder);

                    parsed.movementOrders.add(
                            movementOrderTranslator.translate(
                                    sourceMovementOrder,
                                    board));
                }

                if (rawOrderAndResult.size() == 3) {
                    parsed.resolutions.add(parseResolution(
                            nation,
                            issuingProvince,
                            retreatOrders,
                            rawOrderAndResult.get(2),
                            orderPath + "[2]"
                    ));
                }

            }

        }

        return parsed;

    }

    private DiploBNOrder parseMovementOrder(
            Nation nation,
            Province issuingProvince,
            Object rawOrder,
            Map<UnitId, Province> units,
            String path
    ) {

        UnitId unit = unitAt(
                nation,
                issuingProvince,
                units,
                path);

        if (rawOrder instanceof String orderText) {
            if (!orderText.equals("h"))
                throw new ParseException(
                        path,
                        "Expected movement order tuple or hold 'h'");

            return new DiploBNOrder(
                    unit,
                    OrderType.HOLD,
                    null,
                    null);
        }

        List<Object> order = array(rawOrder, path);

        if (order.isEmpty())
            throw new ParseException(
                    path,
                    "Order tuple must not be empty");

        String tag = string(order.getFirst(), path + "[0]");

        return switch (tag) {
            case "h" -> {
                requireSize(order, 1, path);

                yield new DiploBNOrder(
                        unit,
                        OrderType.HOLD,
                        null,
                        null);
            }
            case "m" -> {
                requireSize(order, 2, path);

                yield new DiploBNOrder(
                        unit,
                        OrderType.MOVE,
                        location(order.get(1), path + "[1]"),
                        null);
            }
            case "sh" -> {
                requireSize(order, 2, path);

                yield new DiploBNOrder(
                        unit,
                        OrderType.SUPPORT,
                        supportedUnitLocation(
                                location(order.get(1), path + "[1]"),
                                units,
                                path + "[1]"),
                        null);
            }
            case "sm" -> {
                requireSize(order, 3, path);

                yield new DiploBNOrder(
                        unit,
                        OrderType.SUPPORT,
                        supportedUnitLocation(
                                location(order.get(1), path + "[1]"),
                                units,
                                path + "[1]"),
                        location(order.get(2), path + "[2]"));
            }
            case "c" -> {
                requireSize(order, 3, path);

                yield new DiploBNOrder(
                        unit,
                        OrderType.CONVOY,
                        location(order.get(1), path + "[1]"),
                        location(order.get(2), path + "[2]"));
            }
            default -> throw new ParseException(
                    path + "[0]",
                    "Unsupported movement order tag: " + tag);
        };

    }

    private RetreatOrder parseRetreatOrder(
            Nation nation,
            Province issuingProvince,
            Object rawOrder,
            Map<UnitId, Province> units,
            String path
    ) {

        UnitId unit = unitAt(
                nation,
                issuingProvince,
                units,
                path);

        if (rawOrder instanceof String orderText) {
            if (orderText.equals("d"))
                return null;

            throw new ParseException(
                    path,
                    "Retreat order must be move ['m', Location]"
                            + " or disband 'd'");
        }

        List<Object> order = array(rawOrder, path);

        if (order.isEmpty())
            throw new ParseException(
                    path,
                    "Retreat order tuple must not be empty");

        String tag = string(order.getFirst(), path + "[0]");

        if (tag.equals("d")) {
            requireSize(order, 1, path);
            return null;
        }

        if (!tag.equals("m"))
            throw new ParseException(
                    path + "[0]",
                    "Retreat order must use move tag 'm'"
                            + " or disband tag 'd'");

        requireSize(order, 2, path);

        return new RetreatOrder(
                unit,
                location(order.get(1), path + "[1]"));

    }

    private AdjustmentOrder parseAdjustmentOrder(
            Nation nation,
            Province issuingProvince,
            Object rawOrder,
            Map<UnitId, Province> units,
            String path
    ) {

        if (rawOrder instanceof String orderText) {
            if (!orderText.equals("d"))
                throw new ParseException(
                        path,
                        "Winter string order must be disband 'd'");

            return new DisbandOrder(
                    nation,
                    unitAt(nation, issuingProvince, units, path));
        }

        List<Object> order = array(rawOrder, path);

        if (order.isEmpty())
            throw new ParseException(
                    path,
                    "Order tuple must not be empty");

        String tag = string(order.getFirst(), path + "[0]");

        if (tag.equals("d")) {
            requireSize(order, 1, path);

            return new DisbandOrder(
                    nation,
                    unitAt(nation, issuingProvince, units, path));
        }

        if (!tag.equals("b"))
            throw new ParseException(
                    path + "[0]",
                    "Unsupported winter order tag: " + tag);

        requireSize(order, 2, path);

        List<Object> rawUnit = array(order.get(1), path + "[1]");

        requireSize(rawUnit, 2, path + "[1]");

        return new BuildOrder(
                nation,
                unitType(
                        string(rawUnit.get(0), path + "[1][0]"),
                        path + "[1][0]"),
                location(rawUnit.get(1), path + "[1][1]"));

    }

    private DiploBNOrderResolution parseResolution(
            Nation nation,
            Province issuingProvince,
            boolean retreatOrder,
            Object rawResolution,
            String path
    ) {

        if (rawResolution instanceof String result)
            return resultResolution(
                    nation,
                    issuingProvince,
                    retreatOrder,
                    result,
                    null,
                    path);

        List<Object> result = array(rawResolution, path);

        if (result.isEmpty() || result.size() > 2)
            throw new ParseException(
                    path,
                    "OrderResult tuple must contain one or two values");

        String resultCode = string(result.getFirst(), path + "[0]");

        String reason = null;

        if (result.size() == 2)
            reason = string(result.get(1), path + "[1]");

        return resultResolution(
                nation,
                issuingProvince,
                retreatOrder,
                resultCode,
                reason,
                path);

    }

    private DiploBNOrderResolution resultResolution(
            Nation nation,
            Province issuingProvince,
            boolean retreatOrder,
            String resultCode,
            String reason,
            String path
    ) {

        boolean successful;

        if (resultCode.equals("s"))
            successful = true;
        else if (resultCode.equals("f"))
            successful = false;
        else
            throw new ParseException(
                    path,
                    "Order result must be 's' or 'f'");

        return new DiploBNOrderResolution(
                nation,
                issuingProvince,
                retreatOrder,
                successful,
                reason);

    }

    private UnitId unitAt(
            Nation nation,
            Province province,
            Map<UnitId, Province> units,
            String path
    ) {

        UnitId found = null;

        for (Map.Entry<UnitId, Province> entry : units.entrySet()) {

            UnitId candidate = entry.getKey();

            if (candidate.owner() != nation)
                continue;

            if (!Province.equalsIgnoreCoast(
                    entry.getValue(),
                    province))
                continue;

            if (found != null)
                throw new ParseException(
                        path,
                        "Multiple " + nation
                                + " units match "
                                + province);

            found = candidate;

        }

        if (found == null)
            throw new ParseException(
                    path,
                    "No " + nation
                            + " unit exists at "
                            + province);

        return found;

    }

    private Province supportedUnitLocation(
            Province sourceLocation,
            Map<UnitId, Province> units,
            String path
    ) {

        if (!isGenericSplitCoast(sourceLocation))
            return sourceLocation;

        Province resolvedLocation = null;

        for (Map.Entry<UnitId, Province> entry : units.entrySet()) {

            UnitId unit = entry.getKey();
            Province location = entry.getValue();

            if (unit.unitType() != UnitType.FLEET)
                continue;

            if (!Province.equalsIgnoreCoast(
                    location,
                    sourceLocation))
                continue;

            if (resolvedLocation != null)
                throw new ParseException(
                        path,
                        "Multiple fleets match supported location: "
                                + sourceLocation);

            resolvedLocation = location;

        }

        return resolvedLocation == null
                ? sourceLocation
                : resolvedLocation;

    }

    private boolean isGenericSplitCoast(
            Province province
    ) {

        return province == Province.Stp
                || province == Province.Spa
                || province == Province.Bul;

    }

    private GamePhase toGamePhase(
            int sourcePhase,
            String status,
            String path
    ) {

        if (sourcePhase < 10000 || sourcePhase > 99999)
            throw new ParseException(
                    path + ".Phase",
                    "Phase must be a five-digit integer");

        int phaseCode = sourcePhase % 10;

        if (phaseCode == 1) {
            return status.equals("AwaitingRetreats")
                    ? GamePhase.SPRING_RETREAT
                    : GamePhase.SPRING_MOVEMENT;
        }

        if (phaseCode == 2) {
            return status.equals("AwaitingRetreats")
                    ? GamePhase.FALL_RETREAT
                    : GamePhase.FALL_MOVEMENT;
        }

        if (phaseCode == 3)
            return GamePhase.WINTER_ADJUSTMENT;

        throw new ParseException(
                path + ".Phase",
                "Phase suffix must be 1, 2, or 3");

    }

    private Province location(Object rawLocation, String path) {

        if (rawLocation instanceof String provinceName)
            return province(provinceName, path);

        List<Object> location = array(rawLocation, path);

        if (location.isEmpty() || location.size() > 2)
            throw new ParseException(
                    path,
                    "Location must contain one or two values");

        Province base = province(
                string(location.getFirst(), path + "[0]"),
                path + "[0]");

        if (location.size() == 1)
            return base;

        String coast = string(location.get(1), path + "[1]");

        return coastProvince(base, coast, path);

    }

    private Province coastProvince(
            Province base,
            String coast,
            String path
    ) {

        return switch (base) {
            case Stp -> switch (coast) {
                case "nc" -> Province.StpNC;
                case "sc" -> Province.StpSC;
                default -> throw unsupportedCoast(base, coast, path);
            };
            case Spa -> switch (coast) {
                case "nc" -> Province.SpaNC;
                case "sc" -> Province.SpaSC;
                default -> throw unsupportedCoast(base, coast, path);
            };
            case Bul -> switch (coast) {
                case "ec" -> Province.BulEC;
                case "sc" -> Province.BulSC;
                default -> throw unsupportedCoast(base, coast, path);
            };
            default -> throw unsupportedCoast(base, coast, path);
        };

    }

    private ParseException unsupportedCoast(
            Province province,
            String coast,
            String path
    ) {
        return new ParseException(
                path,
                "Unsupported coast "
                        + coast
                        + " for "
                        + province);
    }

    private Province province(String value, String path) {
        try {
            return Province.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ParseException(
                    path,
                    "Unknown standard-map province: " + value);
        }
    }

    private Nation nation(String value, String path) {
        return switch (value) {
            case "Austria" -> Nation.AUSTRIA;
            case "England" -> Nation.ENGLAND;
            case "France" -> Nation.FRANCE;
            case "Germany" -> Nation.GERMANY;
            case "Italy" -> Nation.ITALY;
            case "Russia" -> Nation.RUSSIA;
            case "Turkey" -> Nation.TURKEY;
            default -> throw new ParseException(
                    path,
                    "Unknown country: " + value);
        };
    }

    private UnitType unitType(String value, String path) {
        return switch (value) {
            case "A" -> UnitType.ARMY;
            case "F" -> UnitType.FLEET;
            default -> throw new ParseException(
                    path,
                    "Unit type must be 'A' or 'F'");
        };
    }

    private static Object required(
            Map<String, Object> object,
            String key,
            String path
    ) {
        if (!object.containsKey(key))
            throw new ParseException(
                    path,
                    "Missing required property: " + key);

        return object.get(key);
    }

    private static Map<String, Object> optionalObject(
            Map<String, Object> object,
            String key,
            String path
    ) {
        if (!object.containsKey(key) || object.get(key) == null)
            return null;

        return object(object.get(key), path + "." + key);
    }

    private static String optionalString(
            Map<String, Object> object,
            String key,
            String path
    ) {
        if (!object.containsKey(key) || object.get(key) == null)
            return null;

        return string(object.get(key), path + "." + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(
            Object value,
            String path
    ) {
        if (!(value instanceof Map<?, ?>))
            throw new ParseException(path, "Expected object");

        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(
            Object value,
            String path
    ) {
        if (!(value instanceof List<?>))
            throw new ParseException(path, "Expected array");

        return (List<Object>) value;
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text))
            throw new ParseException(path, "Expected string");

        return text;
    }

    private static int integer(Object value, String path) {

        if (!(value instanceof Number number))
            throw new ParseException(path, "Expected integer");

        long converted = number.longValue();

        if (number.doubleValue() != converted)
            throw new ParseException(path, "Expected integer");

        if (converted < Integer.MIN_VALUE
                || converted > Integer.MAX_VALUE)
            throw new ParseException(
                    path,
                    "Integer is outside Java int range");

        return (int) converted;

    }

    private static void requireSize(
            List<Object> values,
            int expectedSize,
            String path
    ) {
        if (values.size() != expectedSize)
            throw new ParseException(
                    path,
                    "Expected "
                            + expectedSize
                            + " values but found "
                            + values.size());
    }


    private static final class ParsedOrders {

        private final List<DiploBNOrder> sourceMovementOrders =
                new ArrayList<>();

        private final List<phase.Order> movementOrders =
                new ArrayList<>();

        private final List<RetreatOrder> retreatOrders = new ArrayList<>();

        private final List<AdjustmentOrder> adjustmentOrders =
                new ArrayList<>();

        private final List<DiploBNOrderResolution> resolutions =
                new ArrayList<>();

    }

}