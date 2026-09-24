package _app;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.DiploBNOrderResolution;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.adjustments.WaiveOrder;
import phase.retreats.RetreatOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;


/**
 * Console entry point for importing one DiploBN game page into JReferee.
 *
 * <p>Supply a DiploBN game URL as the first command-line argument, or run
 * without arguments to enter one interactively.</p>
 */
public final class DiploBNGameImporter {


    private DiploBNGameImporter() {
    }


    public static void main(String[] args) {

        String gamePageUrl = requestedGamePageUrl(args);

        if (gamePageUrl == null)
            return;

        DiploBNGameClient client = new DiploBNGameClient();

        try {
            System.out.println();
            System.out.println("Downloading DiploBN game...");
            System.out.println();

            DiploBNGame imported = client.loadGamePage(gamePageUrl);

            printSummary(imported);

        } catch (IllegalArgumentException exception) {
            System.err.println(
                    "Invalid DiploBN game URL: "
                            + exception.getMessage());
        } catch (IOException exception) {
            System.err.println(
                    "Unable to download DiploBN game: "
                            + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            System.err.println(
                    "DiploBN game download was interrupted");
        }

    }


    private static String requestedGamePageUrl(String[] args) {

        if (args.length > 0)
            return args[0];

        System.out.println(
                "Enter DiploBN game URL "
                        + "(for example: "
                        + "https://diplobn.com/game/?GameID=12990):");

        Scanner scanner = new Scanner(System.in);

        if (!scanner.hasNextLine()) {
            System.err.println(
                    "No DiploBN game URL was supplied");

            return null;
        }

        String gamePageUrl = scanner.nextLine().strip();

        if (gamePageUrl.isBlank()) {
            System.err.println(
                    "No DiploBN game URL was supplied");

            return null;
        }

        return gamePageUrl;

    }

    private static void printSummary(DiploBNGame imported) {

        System.out.println("DIPLOBN IMPORT SUMMARY:");
        System.out.println();

        printMetadata("Competition", imported.competition());
        printMetadata("Game label", imported.gameLabel());
        printMetadata("Source URL", imported.sourceUrl());

        System.out.println(
                "Source phases: "
                        + imported.phases().size());

        System.out.println();

        for (int index = 0; index < imported.phases().size(); index++) {
            printPhase(
                    index + 1,
                    imported.phases().get(index));
        }

    }

    private static void printMetadata(
            String label,
            String value
    ) {

        System.out.printf(
                "%-14s %s%n",
                label + ":",
                value == null
                        ? "<not supplied>"
                        : value);

    }

    private static void printPhase(
            int number,
            DiploBNPhase phase
    ) {

        System.out.printf(
                "[%d] %s %s%n",
                number,
                formatSourcePhase(phase.sourcePhase()),
                phase.gamePhase());

        System.out.printf(
                "    Status:              %s%n",
                phase.sourceStatus());

        System.out.printf(
                "    Units:               %d%n",
                phase.board().locations().size());

        System.out.printf(
                "    Supply-center owners:%d%n",
                phase.board().owners().size());

        System.out.printf(
                "    Movement orders:     %d%n",
                phase.movementOrders().size());

        System.out.printf(
                "    Retreat orders:      %d%n",
                phase.retreatOrders().size());

        System.out.printf(
                "    Adjustment orders:   %d%n",
                phase.adjustmentOrders().size());

        System.out.printf(
                "    Order resolutions:   %d%n",
                phase.resolutions().size());

        System.out.printf(
                "    Retreat resolutions: %d%n",
                phase.retreatResolutions().size());

        printForces(phase);
        printCommands(phase);

        System.out.println();

    }

    private static void printForces(DiploBNPhase phase) {

        System.out.println("    FORCES:");

        if (phase.board().locations().isEmpty()) {
            System.out.println("      <none>");
            return;
        }

        for (Nation nation : nationsIn(phase)) {

            List<Map.Entry<UnitId, Province>> forces =
                    forcesOf(nation, phase);

            if (forces.isEmpty())
                continue;

            System.out.println(
                    "      " + fullNationName(nation) + ":");

            for (Map.Entry<UnitId, Province> entry : forces) {
                System.out.printf(
                        "        %s %s%n",
                        unitMarker(entry.getKey().unitType()),
                        entry.getValue());
            }

        }

    }

    private static void printCommands(DiploBNPhase phase) {

        if (phase.movementOrders().isEmpty()
                && phase.retreatOrders().isEmpty()
                && phase.adjustmentOrders().isEmpty()
                && phase.retreatResolutions().isEmpty())
            return;

        System.out.println("    COMMANDS:");

        for (Nation nation : nationsIn(phase)) {

            List<String> commands = commandsOf(nation, phase);

            if (commands.isEmpty())
                continue;

            System.out.println(
                    "      " + fullNationName(nation) + ":");

            for (String command : commands)
                System.out.println("        " + command);

        }

    }

    private static List<Map.Entry<UnitId, Province>> forcesOf(
            Nation nation,
            DiploBNPhase phase
    ) {

        List<Map.Entry<UnitId, Province>> forces = new ArrayList<>();

        for (Map.Entry<UnitId, Province> entry :
                phase.board().locations().entrySet()) {
            if (entry.getKey().owner() == nation)
                forces.add(entry);
        }

        return forces;

    }

    private static List<String> commandsOf(
            Nation nation,
            DiploBNPhase phase
    ) {

        List<String> commands = new ArrayList<>();

        for (Order order : phase.movementOrders()) {
            if (order.owner() != nation)
                continue;

            Province issuingProvince = locationOf(
                    order.unit(),
                    phase);

            commands.add(
                    formatMovementOrder(order)
                            + " "
                            + markerFor(
                            phase.resolutions(),
                            nation,
                            issuingProvince,
                            false)
            );
        }

        for (RetreatOrder order : phase.retreatOrders()) {
            if (order.unit().owner() != nation)
                continue;

            Province issuingProvince = locationOf(
                    order.unit(),
                    phase);

            commands.add(
                    unitMarker(order.unit().unitType())
                            + " "
                            + issuingProvince
                            + " R "
                            + order.destination()
                            + " "
                            + markerFor(
                            phase.retreatResolutions(),
                            nation,
                            issuingProvince,
                            true)
            );
        }

        for (AdjustmentOrder order : phase.adjustmentOrders()) {
            if (order.nation() != nation)
                continue;

            commands.add(
                    formatAdjustmentOrder(order, phase)
                            + " "
                            + markerFor(
                            phase.resolutions(),
                            nation,
                            issuingProvinceOf(order, phase),
                            false)
            );
        }

        for (DiploBNOrderResolution resolution :
                phase.retreatResolutions()) {

            if (resolution.nation() != nation)
                continue;

            if (hasRetreatOrderAt(
                    nation,
                    resolution.issuingProvince(),
                    phase))
                continue;

            commands.add(
                    formatRetreatDisband(
                            resolution,
                            phase)
                            + " "
                            + markerFor(resolution)
            );
        }

        return commands;

    }

    private static boolean hasRetreatOrderAt(
            Nation nation,
            Province province,
            DiploBNPhase phase
    ) {

        for (RetreatOrder order : phase.retreatOrders()) {
            if (order.unit().owner() != nation)
                continue;

            Province location = locationOf(order.unit(), phase);

            if (Province.equalsIgnoreCoast(location, province))
                return true;
        }

        return false;

    }

    private static String formatMovementOrder(Order order) {

        String unit = unitMarker(order.unitType())
                + " "
                + order.unit().origin();

        return switch (order.type()) {
            case HOLD -> unit + " H";
            case MOVE -> unit + " - " + order.target();
            case SUPPORT -> formatSupportOrder(unit, order);
            case CONVOY -> unit
                    + " C "
                    + order.target()
                    + " - "
                    + order.auxiliaryTarget();
            default -> throw new IllegalStateException(
                    "Unexpected movement order type: "
                            + order.type());
        };

    }

    private static String formatSupportOrder(
            String unit,
            Order order
    ) {

        if (order.auxiliaryTarget() == null)
            return unit + " S " + order.target();

        return unit
                + " S "
                + order.target()
                + " - "
                + order.auxiliaryTarget();

    }

    private static String formatAdjustmentOrder(
            AdjustmentOrder order,
            DiploBNPhase phase
    ) {

        if (order instanceof BuildOrder buildOrder) {
            return unitMarker(buildOrder.unitType())
                    + " "
                    + buildOrder.location()
                    + " B";
        }

        if (order instanceof DisbandOrder disbandOrder) {
            return unitMarker(disbandOrder.unit().unitType())
                    + " "
                    + locationOf(disbandOrder.unit(), phase)
                    + " D";
        }

        if (order instanceof WaiveOrder)
            return "WAIVE";

        throw new IllegalStateException(
                "Unsupported adjustment order type: "
                        + order.getClass().getName());

    }

    private static String formatRetreatDisband(
            DiploBNOrderResolution resolution,
            DiploBNPhase phase
    ) {

        UnitId unit = unitAt(
                resolution.nation(),
                resolution.issuingProvince(),
                phase);

        if (unit == null)
            return "? "
                    + resolution.issuingProvince()
                    + " D";

        return unitMarker(unit.unitType())
                + " "
                + resolution.issuingProvince()
                + " D";

    }

    private static Province issuingProvinceOf(
            AdjustmentOrder order,
            DiploBNPhase phase
    ) {

        if (order instanceof BuildOrder buildOrder)
            return buildOrder.location();

        if (order instanceof DisbandOrder disbandOrder)
            return locationOf(disbandOrder.unit(), phase);

        if (order instanceof WaiveOrder)
            return null;

        throw new IllegalStateException(
                "Unsupported adjustment order type: "
                        + order.getClass().getName());

    }

    private static String markerFor(
            List<DiploBNOrderResolution> resolutions,
            Nation nation,
            Province issuingProvince,
            boolean retreatOrder
    ) {

        if (issuingProvince == null)
            return "[--]";

        for (DiploBNOrderResolution resolution : resolutions) {
            if (resolution.nation() != nation)
                continue;

            if (resolution.retreatOrder() != retreatOrder)
                continue;

            if (!Province.equalsIgnoreCoast(
                    resolution.issuingProvince(),
                    issuingProvince))
                continue;

            return markerFor(resolution);
        }

        return "[--]";

    }

    private static String markerFor(
            DiploBNOrderResolution resolution
    ) {

        return resolution.successful()
                ? "[OK]"
                : "[FAIL]";

    }

    private static UnitId unitAt(
            Nation nation,
            Province province,
            DiploBNPhase phase
    ) {

        for (Map.Entry<UnitId, Province> entry :
                phase.board().locations().entrySet()) {

            if (entry.getKey().owner() != nation)
                continue;

            if (Province.equalsIgnoreCoast(
                    entry.getValue(),
                    province))
                return entry.getKey();
        }

        return null;

    }

    private static Province locationOf(
            UnitId unit,
            DiploBNPhase phase
    ) {

        Province location = phase.board().locationOf(unit);

        if (location == null)
            return unit.origin();

        return location;

    }

    private static Set<Nation> nationsIn(DiploBNPhase phase) {

        Set<Nation> nations = EnumSet.noneOf(Nation.class);

        for (UnitId unit : phase.board().locations().keySet())
            nations.add(unit.owner());

        for (Order order : phase.movementOrders())
            nations.add(order.owner());

        for (RetreatOrder order : phase.retreatOrders())
            nations.add(order.unit().owner());

        for (AdjustmentOrder order : phase.adjustmentOrders())
            nations.add(order.nation());

        for (DiploBNOrderResolution resolution :
                phase.resolutions())
            nations.add(resolution.nation());

        for (DiploBNOrderResolution resolution :
                phase.retreatResolutions())
            nations.add(resolution.nation());

        return nations;

    }

    private static String fullNationName(Nation nation) {

        String name = nation.name().toLowerCase();

        return Character.toUpperCase(name.charAt(0))
                + name.substring(1);

    }

    private static String unitMarker(UnitType unitType) {

        return switch (unitType) {
            case ARMY -> "A";
            case FLEET -> "F";
        };

    }

    /**
     * Formats DiploBN's five-digit phase value as {@code year.turn}.
     *
     * <p>For example, {@code 19041} becomes {@code 1904.1}.</p>
     */
    private static String formatSourcePhase(int sourcePhase) {

        int year = sourcePhase / 10;
        int turn = sourcePhase % 10;

        return year + "." + turn;

    }

}