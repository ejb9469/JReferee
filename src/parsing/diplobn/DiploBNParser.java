package parsing.diplobn;

import domain.*;
import game.BoardState;
import game.GamePhase;
import io.json.JsonAccess;
import phase.UnitId;
import phase.UnitLookup;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.retreats.RetreatOrder;

import java.util.*;


/**
 * Parses DiploBN game JSON into board snapshots and typed phase orders.
 * Network behavior belongs to the caller.
 */
public final class DiploBNParser {


    private static final JsonAccess JSON = new JsonAccess(ParseException::new);


    public DiploBNGame parse(String source) {

        Object parsed = JsonReader.read(Objects.requireNonNull(source, "source"));
        Map<String, Object> game = JSON.object(parsed, "$");

        List<Object> rawPhases =
                JSON.array(JSON.required(game, "GamePhases", "$"), "$.GamePhases");

        List<DiploBNPhase> phases = new ArrayList<>();

        for (int index = 0; index < rawPhases.size(); index++) {
            String path = "$.GamePhases[" + index + "]";
            phases.add(parsePhase(JSON.object(rawPhases.get(index), path), path));
        }

        return new DiploBNGame(
                JSON.optionalString(game, "Competition", "$"),
                JSON.optionalString(game, "GameLabel", "$"),
                JSON.optionalString(game, "URL", "$"),
                phases);

    }


    // Phase decoding \\

    private DiploBNPhase parsePhase(Map<String, Object> rawPhase, String path) {

        int sourcePhase =
                JSON.integer(JSON.required(rawPhase, "Phase", path), path + ".Phase");

        String status =
                JSON.string(JSON.required(rawPhase, "Status", path), path + ".Status");

        GamePhase gamePhase = toGamePhase(sourcePhase, status, path);

        Map<UnitId, Province> units = parseUnits(
                JSON.optionalObject(rawPhase, "Units", path), path + ".Units");

        Map<Province, Nation> owners = parseSupplyCenters(
                JSON.optionalObject(rawPhase, "SupplyCenters", path),
                path + ".SupplyCenters");

        BoardState board = new BoardState(units, owners);

        ParsedOrders submittedOrders = parseSubmittedOrders(
                JSON.optionalObject(rawPhase, "Orders", path),
                units, board, gamePhase, false, path + ".Orders");

        ParsedOrders submittedRetreatOrders = parseSubmittedOrders(
                JSON.optionalObject(rawPhase, "RetreatOrders", path),
                units, board, gamePhase, true, path + ".RetreatOrders");

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
                submittedRetreatOrders.resolutions);

    }

    private Map<UnitId, Province> parseUnits(Map<String, Object> rawUnits, String path) {

        Map<UnitId, Province> units = new LinkedHashMap<>();

        if (rawUnits == null)
            return units;

        for (Map.Entry<String, Object> entry : rawUnits.entrySet()) {

            String nationPath = path + "." + entry.getKey();
            Nation nation = nation(entry.getKey(), nationPath);
            List<Object> rawNationUnits = JSON.array(entry.getValue(), nationPath);

            for (int index = 0; index < rawNationUnits.size(); index++) {

                String unitPath = nationPath + "[" + index + "]";
                List<Object> rawUnit = JSON.array(rawNationUnits.get(index), unitPath);

                JSON.requireSize(rawUnit, 2, unitPath);

                UnitType unitType = unitType(
                        JSON.string(rawUnit.get(0), unitPath + "[0]"),
                        unitPath + "[0]");

                Province location = location(rawUnit.get(1), unitPath + "[1]");
                UnitId unit = new UnitId(nation, unitType, location);

                if (units.putIfAbsent(unit, location) != null)
                    throw new ParseException(unitPath, "Duplicate unit identity: " + unit);

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

            String nationPath = path + "." + entry.getKey();
            Nation nation = nation(entry.getKey(), nationPath);
            List<Object> rawNationCenters = JSON.array(entry.getValue(), nationPath);

            for (int index = 0; index < rawNationCenters.size(); index++) {

                String centerPath = nationPath + "[" + index + "]";
                Province center = province(
                        JSON.string(rawNationCenters.get(index), centerPath), centerPath);

                Province canonicalCenter = Province.canonical(center);
                Nation previousOwner = owners.putIfAbsent(canonicalCenter, nation);

                if (previousOwner != null && previousOwner != nation)
                    throw new ParseException(
                            path, "Supply center has conflicting owners: " + canonicalCenter);

            }

        }

        return owners;

    }


    // Order decoding \\

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

        DiploBNOrderTranslator translator = new DiploBNOrderTranslator();

        for (Map.Entry<String, Object> entry : rawOrders.entrySet()) {

            String nationPath = path + "." + entry.getKey();
            Nation nation = nation(entry.getKey(), nationPath);
            List<Object> rawNationOrders = JSON.array(entry.getValue(), nationPath);

            for (int index = 0; index < rawNationOrders.size(); index++) {

                String orderPath = nationPath + "[" + index + "]";
                List<Object> rawOrderAndResult =
                        JSON.array(rawNationOrders.get(index), orderPath);

                if (rawOrderAndResult.size() < 2 || rawOrderAndResult.size() > 3)
                    throw new ParseException(
                            orderPath, "OrderAndResolution must contain two or three values");

                Province issuingProvince = province(
                        JSON.string(rawOrderAndResult.get(0), orderPath + "[0]"),
                        orderPath + "[0]");

                Object rawOrder = rawOrderAndResult.get(1);

                if (retreatOrders) {

                    RetreatOrder retreatOrder = parseRetreatOrder(
                            nation, issuingProvince, rawOrder, units, orderPath + "[1]");

                    // A retreat disband is represented by the absence of a RetreatOrder.
                    if (retreatOrder != null)
                        parsed.retreatOrders.add(retreatOrder);

                } else if (gamePhase.isAdjustment()) {

                    parsed.adjustmentOrders.add(parseAdjustmentOrder(
                            nation, issuingProvince, rawOrder, units, orderPath + "[1]"));

                } else {

                    DiploBNOrder sourceOrder = parseMovementOrder(
                            nation, issuingProvince, rawOrder, units, orderPath + "[1]");

                    parsed.sourceMovementOrders.add(sourceOrder);
                    parsed.movementOrders.add(translator.translate(sourceOrder, board));

                }

                if (rawOrderAndResult.size() == 3)
                    parsed.resolutions.add(parseResolution(
                            nation, issuingProvince, retreatOrders,
                            rawOrderAndResult.get(2), orderPath + "[2]"));

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

        UnitId unit = unitAt(nation, issuingProvince, units, path);

        if (rawOrder instanceof String orderText) {

            if (!orderText.equals("h"))
                throw new ParseException(path, "Expected movement order tuple or hold 'h'");

            return new DiploBNOrder(unit, OrderType.HOLD, null, null);

        }

        List<Object> order = JSON.array(rawOrder, path);

        if (order.isEmpty())
            throw new ParseException(path, "Order tuple must not be empty");

        String tag = JSON.string(order.getFirst(), path + "[0]");

        return switch (tag) {

            case "h" -> {
                JSON.requireSize(order, 1, path);
                yield new DiploBNOrder(unit, OrderType.HOLD, null, null);
            }

            case "m" -> {
                JSON.requireSize(order, 2, path);
                yield new DiploBNOrder(
                        unit, OrderType.MOVE, location(order.get(1), path + "[1]"), null);
            }

            case "sh" -> {
                JSON.requireSize(order, 2, path);
                yield new DiploBNOrder(
                        unit,
                        OrderType.SUPPORT,
                        supportedUnitLocation(
                                location(order.get(1), path + "[1]"), units, path + "[1]"),
                        null);
            }

            case "sm" -> {
                JSON.requireSize(order, 3, path);
                yield new DiploBNOrder(
                        unit,
                        OrderType.SUPPORT,
                        supportedUnitLocation(
                                location(order.get(1), path + "[1]"), units, path + "[1]"),
                        location(order.get(2), path + "[2]"));
            }

            case "c" -> {
                JSON.requireSize(order, 3, path);
                yield new DiploBNOrder(
                        unit, OrderType.CONVOY,
                        location(order.get(1), path + "[1]"),
                        location(order.get(2), path + "[2]"));
            }

            default -> throw new ParseException(
                    path + "[0]", "Unsupported movement order tag: " + tag);

        };

    }

    private RetreatOrder parseRetreatOrder(
            Nation nation,
            Province issuingProvince,
            Object rawOrder,
            Map<UnitId, Province> units,
            String path
    ) {

        UnitId unit = unitAt(nation, issuingProvince, units, path);

        if (rawOrder instanceof String orderText) {

            if (orderText.equals("d"))
                return null;

            throw new ParseException(
                    path, "Retreat order must be move ['m', Location] or disband 'd'");

        }

        List<Object> order = JSON.array(rawOrder, path);

        if (order.isEmpty())
            throw new ParseException(path, "Order tuple must not be empty");

        String tag = JSON.string(order.getFirst(), path + "[0]");

        if (tag.equals("d")) {
            JSON.requireSize(order, 1, path);
            return null;
        }

        if (!tag.equals("m"))
            throw new ParseException(path + "[0]", "Retreat order must use move tag 'm' or disband 'd'");

        JSON.requireSize(order, 2, path);

        return new RetreatOrder(unit, location(order.get(1), path + "[1]"));

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
                throw new ParseException(path, "Winter string order must be disband 'd'");

            return new DisbandOrder(nation, unitAt(nation, issuingProvince, units, path));

        }

        List<Object> order = JSON.array(rawOrder, path);

        if (order.isEmpty())
            throw new ParseException(path, "Order tuple must not be empty");

        String tag = JSON.string(order.getFirst(), path + "[0]");

        if (tag.equals("d")) {
            JSON.requireSize(order, 1, path);
            return new DisbandOrder(nation, unitAt(nation, issuingProvince, units, path));
        }

        if (!tag.equals("b"))
            throw new ParseException(path + "[0]", "Unsupported winter order tag: " + tag);

        JSON.requireSize(order, 2, path);

        List<Object> rawUnit = JSON.array(order.get(1), path + "[1]");
        JSON.requireSize(rawUnit, 2, path + "[1]");

        return new BuildOrder(
                nation,
                unitType(JSON.string(rawUnit.get(0), path + "[1][0]"), path + "[1][0]"),
                location(rawUnit.get(1), path + "[1][1]"));

    }


    // Resolution decoding \\

    private DiploBNOrderResolution parseResolution(
            Nation nation,
            Province issuingProvince,
            boolean retreatOrder,
            Object rawResolution,
            String path
    ) {

        if (rawResolution instanceof String result)
            return resultResolution(nation, issuingProvince, retreatOrder, result, null, path);

        List<Object> result = JSON.array(rawResolution, path);

        if (result.isEmpty() || result.size() > 2)
            throw new ParseException(path, "OrderResult tuple must contain one or two values");

        String resultCode = JSON.string(result.getFirst(), path + "[0]");
        String reason = result.size() == 2
                ? JSON.string(result.get(1), path + "[1]")
                : null;

        return resultResolution(nation, issuingProvince, retreatOrder, resultCode, reason, path);

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
            throw new ParseException(path, "Order result must be 's' or 'f'");

        return new DiploBNOrderResolution(
                nation, issuingProvince, retreatOrder, successful, reason);

    }


    // Unit lookup policies \\

    private UnitId unitAt(
            Nation nation,
            Province province,
            Map<UnitId, Province> units,
            String path
    ) {

        List<UnitId> matches =
                UnitLookup.matchingAt(units, province, unit -> unit.owner() == nation);

        if (matches.size() > 1)
            throw new ParseException(path, "Multiple " + nation + " units match " + province);

        if (matches.isEmpty())
            throw new ParseException(path, "No " + nation + " unit exists at " + province);

        return matches.getFirst();

    }

    private Province supportedUnitLocation(
            Province sourceLocation,
            Map<UnitId, Province> units,
            String path
    ) {

        if (!isGenericSplitCoast(sourceLocation))
            return sourceLocation;

        List<UnitId> matches = UnitLookup.matchingAt(
                units, sourceLocation, unit -> unit.unitType() == UnitType.FLEET);

        if (matches.size() > 1)
            throw new ParseException(path, "Multiple fleets match supported location: " + sourceLocation);

        return matches.isEmpty() ? sourceLocation : units.get(matches.getFirst());

    }

    private boolean isGenericSplitCoast(Province province) {
        return province == Province.Stp || province == Province.Spa || province == Province.Bul;
    }


    // Domain decoding \\

    private GamePhase toGamePhase(int sourcePhase, String status, String path) {

        if (sourcePhase < 10000 || sourcePhase > 99999)
            throw new ParseException(path + ".Phase", "Phase must be a five-digit integer");

        int phaseCode = sourcePhase % 10;

        if (phaseCode == 1)
            return status.equals("AwaitingRetreats")
                    ? GamePhase.SPRING_RETREAT : GamePhase.SPRING_MOVEMENT;

        if (phaseCode == 2)
            return status.equals("AwaitingRetreats")
                    ? GamePhase.FALL_RETREAT : GamePhase.FALL_MOVEMENT;

        if (phaseCode == 3)
            return GamePhase.WINTER_ADJUSTMENT;

        throw new ParseException(path + ".Phase", "Phase suffix must be 1, 2, or 3");

    }

    private Province location(Object rawLocation, String path) {

        if (rawLocation instanceof String provinceName)
            return province(provinceName, path);

        List<Object> location = JSON.array(rawLocation, path);

        if (location.isEmpty() || location.size() > 2)
            throw new ParseException(path, "Location must contain one or two values");

        Province base = province(
                JSON.string(location.getFirst(), path + "[0]"), path + "[0]");

        if (location.size() == 1)
            return base;

        return coastProvince(base, JSON.string(location.get(1), path + "[1]"), path);

    }

    private Province coastProvince(Province base, String coast, String path) {

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

    private ParseException unsupportedCoast(Province province, String coast, String path) {
        return new ParseException(path, "Unsupported coast " + coast + " for " + province);
    }

    private Province province(String value, String path) {

        try {
            return Province.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new ParseException(path, "Unknown standard-map province: " + value);
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
            default -> throw new ParseException(path, "Unknown country: " + value);
        };

    }

    private UnitType unitType(String value, String path) {

        return switch (value) {
            case "A" -> UnitType.ARMY;
            case "F" -> UnitType.FLEET;
            default -> throw new ParseException(path, "Unit type must be 'A' or 'F'");
        };

    }


    private static final class ParsedOrders {

        private final List<DiploBNOrder> sourceMovementOrders = new ArrayList<>();
        private final List<phase.Order> movementOrders = new ArrayList<>();
        private final List<RetreatOrder> retreatOrders = new ArrayList<>();
        private final List<AdjustmentOrder> adjustmentOrders = new ArrayList<>();
        private final List<DiploBNOrderResolution> resolutions = new ArrayList<>();

    }


}