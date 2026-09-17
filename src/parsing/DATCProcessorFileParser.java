package parsing;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentInput;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.AdjustmentResult;
import phase.adjustments.Balance;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.adjustments.WaiveOrder;
import phase.movement.MovementInput;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatInput;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;
import testing.AdjustmentProcessorTestCase;
import testing.ProcessorTestCase;
import testing.RetreatProcessorTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads phase-aware DATC fixture files for retreat, build, and civil-disorder
 * processor tests.
 *
 * <p>Each file is divided into bracketed sections. Values within a section are
 * pipe-separated enum names, which keeps parsing deterministic and avoids
 * modifying the legacy DATC A-G parser.</p>
 */
public final class DATCProcessorFileParser {


    public static final String TESTGAMES_DIR_PATH =
            "src/resources/testgames_phase/";


    public Collection<ProcessorTestCase<?,?>> parseManyFiles() {

        Path directory = Paths.get(TESTGAMES_DIR_PATH);

        if (!Files.isDirectory(directory))
            return List.of();

        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .<ProcessorTestCase<?,?>>map(this::parse)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not read phase test-case directory: "
                            + directory,
                    ex);
        }

    }

    public ProcessorTestCase<?,?> parse(Path path) {

        try {
            return parse(
                    path.getFileName().toString(),
                    Files.readAllLines(path));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Could not read phase test case: " + path,
                    ex);
        }

    }

    private ProcessorTestCase<?,?> parse(
            String fileName,
            List<String> lines
    ) {
        Map<String, List<String>> sections = sectionsOf(lines);

        String phase = oneValue(sections, "PHASE");

        return switch (phase) {
            case "RETREAT" -> parseRetreat(fileName, sections);
            case "ADJUSTMENT" -> parseAdjustment(fileName, sections);
            default -> throw new IllegalArgumentException(
                    "Unsupported phase in "
                            + fileName
                            + ": "
                            + phase);
        };
    }

    private RetreatProcessorTestCase parseRetreat(
            String fileName,
            Map<String, List<String>> sections
    ) {
        Units units = parseUnits(sections);

        MovementResult movementResult =
                new MovementProcessor().process(
                        new MovementInput(
                                units.locations(),
                                parseMovementOrders(
                                        section(sections,
                                                "MOVEMENT_ORDERS"),
                                        units)));

        RetreatInput input = new RetreatInput(
                movementResult,
                parseRetreatOrders(
                        section(sections, "RETREAT_ORDERS"),
                        units));

        return new RetreatProcessorTestCase(
                fileName,
                input,
                parseRetreatOutcomes(
                        section(sections, "EXPECTED_OUTCOMES"),
                        units),
                parseLocations(
                        section(sections,
                                "EXPECTED_FINAL_LOCATIONS"),
                        units),
                parseUnitsOnly(
                        section(sections, "EXPECTED_DESTROYED"),
                        units));
    }

    private AdjustmentProcessorTestCase parseAdjustment(
            String fileName,
            Map<String, List<String>> sections
    ) {
        Units units = parseUnits(sections);

        RetreatResult retreatResult = bootstrapRetreatResult(
                units.locations());

        AdjustmentInput input = new AdjustmentInput(
                retreatResult,
                parseOwners(section(sections, "OWNERS")),
                parseAdjustmentOrders(
                        section(sections, "ADJUSTMENT_ORDERS"),
                        units));

        return new AdjustmentProcessorTestCase(
                fileName,
                input,
                parseAdjustmentOutcomes(
                        section(sections, "EXPECTED_ORDER_OUTCOMES")),
                parseBalances(
                        section(sections, "EXPECTED_BALANCES")),
                parseLocations(
                        section(sections,
                                "EXPECTED_FINAL_LOCATIONS"),
                        units),
                parseUnitsOnly(
                        section(sections, "EXPECTED_DISBANDED"),
                        units),
                parseExpectedBuilds(
                        section(sections, "EXPECTED_BUILDS")),
                Integer.parseInt(
                        oneValue(sections,
                                "EXPECTED_FINAL_UNIT_COUNT")));
    }

    private RetreatResult bootstrapRetreatResult(
            Map<UnitId, Province> startingLocations
    ) {
        MovementResult movementResult =
                new MovementProcessor().process(
                        new MovementInput(
                                startingLocations,
                                List.of()));

        return new RetreatProcessor().process(
                new RetreatInput(
                        movementResult,
                        List.of()));
    }

    private Units parseUnits(
            Map<String, List<String>> sections
    ) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        Map<UnitKey, UnitId> byKey = new LinkedHashMap<>();

        for (String line : section(sections, "UNITS")) {
            String[] values = values(line, 3);

            Nation nation = nation(values[0]);
            UnitType unitType = unitType(values[1]);
            Province location = province(values[2]);

            UnitId unit = new UnitId(
                    nation,
                    unitType,
                    location);

            UnitKey key = new UnitKey(
                    nation,
                    unitType,
                    location);

            if (byKey.putIfAbsent(key, unit) != null)
                throw new IllegalArgumentException(
                        "Duplicate unit in fixture: " + line);

            locations.put(unit, location);
        }

        return new Units(
                Map.copyOf(locations),
                Map.copyOf(byKey));
    }

    private List<Order> parseMovementOrders(
            List<String> lines,
            Units units
    ) {
        List<Order> orders = new ArrayList<>();

        for (String line : lines) {
            String[] values = line.split("\\|", -1);
            String kind = values[0].strip().toUpperCase(Locale.ROOT);

            switch (kind) {
                case "HOLD" -> {
                    requireValueCount(line, values, 4);

                    orders.add(
                            Order.hold(
                                    unitOf(
                                            units,
                                            values[1],
                                            values[2],
                                            values[3])));
                }

                case "MOVE" -> {
                    requireValueCount(line, values, 5);

                    orders.add(
                            Order.move(
                                    unitOf(
                                            units,
                                            values[1],
                                            values[2],
                                            values[3]),
                                    province(values[4])));
                }

                case "SUPPORT_HOLD" -> {
                    requireValueCount(line, values, 5);

                    orders.add(
                            Order.supportHold(
                                    unitOf(
                                            units,
                                            values[1],
                                            values[2],
                                            values[3]),
                                    province(values[4])));
                }

                case "SUPPORT_MOVE" -> {
                    requireValueCount(line, values, 6);

                    orders.add(
                            Order.supportMove(
                                    unitOf(
                                            units,
                                            values[1],
                                            values[2],
                                            values[3]),
                                    province(values[4]),
                                    province(values[5])));
                }

                case "CONVOY" -> {
                    requireValueCount(line, values, 6);

                    orders.add(
                            Order.convoy(
                                    unitOf(
                                            units,
                                            values[1],
                                            values[2],
                                            values[3]),
                                    province(values[4]),
                                    province(values[5])));
                }

                default -> throw new IllegalArgumentException(
                        "Unsupported movement order: " + line);
            }
        }

        return List.copyOf(orders);
    }

    private List<RetreatOrder> parseRetreatOrders(
            List<String> lines,
            Units units
    ) {
        List<RetreatOrder> orders = new ArrayList<>();

        for (String line : lines) {
            String[] values = values(line, 4);

            orders.add(
                    new RetreatOrder(
                            unitOf(
                                    units,
                                    values[0],
                                    values[1],
                                    values[2]),
                            province(values[3])));
        }

        return List.copyOf(orders);
    }

    private List<AdjustmentOrder> parseAdjustmentOrders(
            List<String> lines,
            Units units
    ) {
        List<AdjustmentOrder> orders = new ArrayList<>();

        for (String line : lines) {
            String[] values = line.split("\\|", -1);
            String kind = values[0].strip().toUpperCase(Locale.ROOT);

            switch (kind) {
                case "BUILD" -> {
                    requireValueCount(line, values, 4);

                    orders.add(
                            new BuildOrder(
                                    nation(values[1]),
                                    unitType(values[2]),
                                    province(values[3])));
                }

                case "DISBAND" -> {
                    requireValueCount(line, values, 4);

                    Nation nation = nation(values[1]);
                    UnitType unitType = unitType(values[2]);
                    Province origin = province(values[3]);

                    orders.add(
                            new DisbandOrder(
                                    nation,
                                    units.byKey().getOrDefault(
                                            new UnitKey(
                                                    nation,
                                                    unitType,
                                                    origin),
                                            new UnitId(
                                                    nation,
                                                    unitType,
                                                    origin))));
                }

                case "WAIVE" -> {
                    requireValueCount(line, values, 2);

                    orders.add(
                            new WaiveOrder(nation(values[1])));
                }

                default -> throw new IllegalArgumentException(
                        "Unsupported adjustment order: " + line);
            }
        }

        return List.copyOf(orders);
    }

    private Map<Province, Nation> parseOwners(
            List<String> lines
    ) {
        Map<Province, Nation> owners = new LinkedHashMap<>();

        for (String line : lines) {
            String[] values = values(line, 2);

            owners.put(
                    province(values[0]),
                    nation(values[1]));
        }

        return Map.copyOf(owners);
    }

    private Map<UnitId, RetreatResult.Outcome.Status> parseRetreatOutcomes(
            List<String> lines,
            Units units
    ) {
        Map<UnitId, RetreatResult.Outcome.Status> outcomes =
                new LinkedHashMap<>();

        for (String line : lines) {
            String[] values = values(line, 4);

            outcomes.put(
                    unitOf(
                            units,
                            values[0],
                            values[1],
                            values[2]),
                    RetreatResult.Outcome.Status.valueOf(
                            values[3].strip()));
        }

        return Map.copyOf(outcomes);
    }

    private List<AdjustmentResult.Outcome.Status>
    parseAdjustmentOutcomes(List<String> lines) {
        List<AdjustmentResult.Outcome.Status> outcomes =
                new ArrayList<>();

        for (String line : lines) {
            outcomes.add(
                    AdjustmentResult.Outcome.Status.valueOf(
                            line.strip()));
        }

        return List.copyOf(outcomes);
    }

    private Map<Nation, Balance> parseBalances(
            List<String> lines
    ) {
        Map<Nation, Balance> balances = new EnumMap<>(Nation.class);

        for (String line : lines) {
            String[] values = values(line, 3);

            Nation nation = nation(values[0]);

            balances.put(
                    nation,
                    new Balance(
                            nation,
                            Integer.parseInt(values[1].strip()),
                            Integer.parseInt(values[2].strip())));
        }

        return Map.copyOf(balances);
    }

    private Map<UnitId, Province> parseLocations(
            List<String> lines,
            Units units
    ) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();

        for (String line : lines) {
            String[] values = values(line, 4);

            locations.put(
                    unitOf(
                            units,
                            values[0],
                            values[1],
                            values[2]),
                    province(values[3]));
        }

        return Map.copyOf(locations);
    }

    private Set<UnitId> parseUnitsOnly(
            List<String> lines,
            Units units
    ) {
        Set<UnitId> selected = new LinkedHashSet<>();

        for (String line : lines) {
            String[] values = values(line, 3);

            selected.add(
                    unitOf(
                            units,
                            values[0],
                            values[1],
                            values[2]));
        }

        return Set.copyOf(selected);
    }

    private Set<AdjustmentProcessorTestCase.ExpectedBuild>
    parseExpectedBuilds(List<String> lines) {
        Set<AdjustmentProcessorTestCase.ExpectedBuild> builds =
                new LinkedHashSet<>();

        for (String line : lines) {
            String[] values = values(line, 3);

            builds.add(
                    new AdjustmentProcessorTestCase.ExpectedBuild(
                            nation(values[0]),
                            unitType(values[1]),
                            province(values[2])));
        }

        return Set.copyOf(builds);
    }

    private static Map<String, List<String>> sectionsOf(
            List<String> lines
    ) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = null;

        for (String rawLine : lines) {
            String line = rawLine.strip();

            if (line.isBlank() || line.startsWith("#"))
                continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(
                                1,
                                line.length() - 1).strip()
                        .toUpperCase(Locale.ROOT);

                sections.putIfAbsent(
                        currentSection,
                        new ArrayList<>());

                continue;
            }

            int equalsIndex = line.indexOf('=');

            if (equalsIndex >= 0) {
                String key = line.substring(0, equalsIndex)
                        .strip()
                        .toUpperCase(Locale.ROOT);

                sections.put(
                        key,
                        List.of(
                                line.substring(equalsIndex + 1)
                                        .strip()));

                continue;
            }

            if (currentSection == null)
                throw new IllegalArgumentException(
                        "Fixture content appears before any section: "
                                + rawLine);

            sections.get(currentSection).add(line);
        }

        return sections;
    }

    private static List<String> section(
            Map<String, List<String>> sections,
            String name
    ) {
        return sections.getOrDefault(name, List.of());
    }

    private static String oneValue(
            Map<String, List<String>> sections,
            String name
    ) {
        List<String> values = section(sections, name);

        if (values.size() != 1)
            throw new IllegalArgumentException(
                    "Expected exactly one " + name + " value");

        return values.get(0).toUpperCase(Locale.ROOT);
    }

    private static String[] values(
            String line,
            int expectedCount
    ) {
        String[] values = line.split("\\|", -1);
        requireValueCount(line, values, expectedCount);
        return values;
    }

    private static void requireValueCount(
            String line,
            String[] values,
            int expectedCount
    ) {
        if (values.length != expectedCount)
            throw new IllegalArgumentException(
                    "Expected "
                            + expectedCount
                            + " values in fixture line: "
                            + line);
    }

    private static UnitId unitOf(
            Units units,
            String nation,
            String unitType,
            String origin
    ) {
        UnitKey key = new UnitKey(
                nation(nation),
                unitType(unitType),
                province(origin));

        UnitId unit = units.byKey().get(key);

        if (unit == null)
            throw new IllegalArgumentException(
                    "Fixture references unknown unit: "
                            + nation
                            + "|"
                            + unitType
                            + "|"
                            + origin);

        return unit;
    }

    private static Nation nation(String value) {
        return Nation.valueOf(
                value.strip().toUpperCase(Locale.ROOT));
    }

    private static UnitType unitType(String value) {
        return UnitType.valueOf(
                value.strip().toUpperCase(Locale.ROOT));
    }

    private static Province province(String value) {

        Objects.requireNonNull(value, "value");

        String key = provinceKey(value);

        if (key.isEmpty())
            throw new IllegalArgumentException(
                    "Province value cannot be blank");

        for (Province province : Province.values()) {
            if (key.equals(provinceKey(province.name()))
                    || key.equals(provinceKey(province.fullName))
                    || key.equals(provinceKey(province.toString())))
                return province;
        }

        throw new IllegalArgumentException(
                "Unknown province: " + value);

    }

    private static String provinceKey(String value) {

        StringBuilder key = new StringBuilder();

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (Character.isLetterOrDigit(character))
                key.append(Character.toLowerCase(character));
        }

        return key.toString();

    }

    private record UnitKey(
            Nation nation,
            UnitType unitType,
            Province origin
    ) {
    }

    private record Units(
            Map<UnitId, Province> locations,
            Map<UnitKey, UnitId> byKey
    ) {
    }

}