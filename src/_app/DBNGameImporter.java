package _app;

import domain.*;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.DiploBNOrderResolution;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.UnitId;
import phase.UnitLookup;
import phase.adjustments.*;
import phase.retreats.RetreatOrder;

import java.io.IOException;
import java.util.*;

import static _app.DBNCliFormatting.*;


/**
 * Console entry point for importing and displaying one DiploBN game.
 */
public final class DBNGameImporter {


    private DBNGameImporter() {  }


    public static void main(String[] args) {

        String gamePageUrl = requestedGamePageUrl(args);

        if (gamePageUrl == null)
            return;

        DiploBNGameClient client = new DiploBNGameClient();

        try {
            System.out.println();
            System.out.println("Downloading DiploBN game...");
            System.out.println();

            printSummary(client.loadGamePage(gamePageUrl));

        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid DiploBN game URL: " + exception.getMessage());
        } catch (IOException exception) {
            System.err.println("Unable to download DiploBN game: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("DiploBN game download was interrupted");
        }

    }


    // Summary output \\

    private static void printSummary(DiploBNGame imported) {

        System.out.println("DIPLOBN IMPORT SUMMARY:");
        System.out.println();

        printMetadata("Competition", imported.competition());
        printMetadata("Game label", imported.gameLabel());
        printMetadata("Source URL", imported.sourceUrl());

        System.out.println("Source phases: " + imported.phases().size());
        System.out.println();

        for (int index = 0; index < imported.phases().size(); index++)
            printPhase(index + 1, imported.phases().get(index));

    }

    private static void printPhase(int number, DiploBNPhase phase) {

        System.out.printf(
                "[%d] %s %s%n",
                number, formatNumericSourcePhase(phase.sourcePhase()), phase.gamePhase());

        System.out.printf("    Status:              %s%n", phase.sourceStatus());
        System.out.printf("    Units:               %d%n", phase.board().locations().size());
        System.out.printf("    Supply-center owners:%d%n", phase.board().owners().size());
        System.out.printf("    Movement orders:     %d%n", phase.movementOrders().size());
        System.out.printf("    Retreat orders:      %d%n", phase.retreatOrders().size());
        System.out.printf("    Adjustment orders:   %d%n", phase.adjustmentOrders().size());
        System.out.printf("    Order resolutions:   %d%n", phase.resolutions().size());
        System.out.printf("    Retreat resolutions: %d%n", phase.retreatResolutions().size());

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

            List<Map.Entry<UnitId, Province>> forces = forcesOf(nation, phase);

            if (forces.isEmpty())
                continue;

            System.out.println("      " + fullNationName(nation) + ":");

            for (Map.Entry<UnitId, Province> entry : forces)
                System.out.printf(
                        "        %s %s%n",
                        unitMarker(entry.getKey().unitType()), entry.getValue());

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

            System.out.println("      " + fullNationName(nation) + ":");

            for (String command : commands)
                System.out.println("        " + command);

        }

    }


    // Command collection \\

    private static List<Map.Entry<UnitId, Province>> forcesOf(
            Nation nation,
            DiploBNPhase phase
    ) {

        List<Map.Entry<UnitId, Province>> forces = new ArrayList<>();

        for (Map.Entry<UnitId, Province> entry : phase.board().locations().entrySet())
            if (entry.getKey().owner() == nation)
                forces.add(entry);

        return forces;

    }

    private static List<String> commandsOf(Nation nation, DiploBNPhase phase) {

        List<String> commands = new ArrayList<>();

        for (Order order : phase.movementOrders()) {

            if (order.owner() != nation)
                continue;

            Province issuingProvince = locationOf(order.unit(), phase);

            commands.add(
                    formatMovementOrder(order) + " "
                            + markerFor(phase.resolutions(), nation, issuingProvince, false));

        }

        for (RetreatOrder order : phase.retreatOrders()) {

            if (order.unit().owner() != nation)
                continue;

            Province issuingProvince = locationOf(order.unit(), phase);

            commands.add(
                    unitMarker(order.unit().unitType()) + " " + issuingProvince
                            + " R " + order.destination() + " "
                            + markerFor(phase.retreatResolutions(), nation, issuingProvince, true));

        }

        for (AdjustmentOrder order : phase.adjustmentOrders()) {

            if (order.nation() != nation)
                continue;

            commands.add(
                    formatAdjustmentOrder(order, phase) + " "
                            + markerFor(
                            phase.resolutions(), nation, issuingProvinceOf(order, phase), false));

        }

        for (DiploBNOrderResolution resolution : phase.retreatResolutions()) {

            if (resolution.nation() != nation)
                continue;

            if (hasRetreatOrderAt(nation, resolution.issuingProvince(), phase))
                continue;

            commands.add(formatRetreatDisband(resolution, phase) + " " + markerFor(resolution));

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

            if (Province.equalsIgnoreCoast(locationOf(order.unit(), phase), province))
                return true;

        }

        return false;

    }


    // Order formatting \\

    private static String formatMovementOrder(Order order) {

        String unit = unitMarker(order.unitType()) + " " + order.unit().origin();

        return switch (order.type()) {
            case HOLD -> unit + " H";
            case MOVE -> unit + " - " + order.target();
            case SUPPORT -> formatSupportOrder(unit, order);
            case CONVOY -> unit + " C " + order.target() + " - " + order.auxiliaryTarget();
            default -> throw new IllegalStateException(
                    "Unexpected movement order type: " + order.type());
        };

    }

    private static String formatSupportOrder(String unit, Order order) {

        if (order.auxiliaryTarget() == null)
            return unit + " S " + order.target();

        return unit + " S " + order.target() + " - " + order.auxiliaryTarget();

    }

    private static String formatAdjustmentOrder(AdjustmentOrder order, DiploBNPhase phase) {

        if (order instanceof BuildOrder build)
            return unitMarker(build.unitType()) + " " + build.location() + " B";

        if (order instanceof DisbandOrder disband)
            return unitMarker(disband.unit().unitType())
                    + " " + locationOf(disband.unit(), phase) + " D";

        if (order instanceof WaiveOrder)
            return "WAIVE";

        throw new IllegalStateException(
                "Unsupported adjustment order type: " + order.getClass().getName());

    }

    private static String formatRetreatDisband(
            DiploBNOrderResolution resolution,
            DiploBNPhase phase
    ) {

        UnitId unit = UnitLookup.firstAt(
                phase.board().locations(), resolution.issuingProvince(), resolution.nation());

        if (unit == null)
            return "? " + resolution.issuingProvince() + " D";

        return unitMarker(unit.unitType()) + " " + resolution.issuingProvince() + " D";

    }

    private static Province issuingProvinceOf(AdjustmentOrder order, DiploBNPhase phase) {

        if (order instanceof BuildOrder build)
            return build.location();

        if (order instanceof DisbandOrder disband)
            return locationOf(disband.unit(), phase);

        if (order instanceof WaiveOrder)
            return null;

        throw new IllegalStateException(
                "Unsupported adjustment order type: " + order.getClass().getName());

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

            if (resolution.nation() != nation || resolution.retreatOrder() != retreatOrder)
                continue;

            if (Province.equalsIgnoreCoast(resolution.issuingProvince(), issuingProvince))
                return markerFor(resolution);

        }

        return "[--]";

    }

    private static String markerFor(DiploBNOrderResolution resolution) {
        return resolution.successful() ? "[OK]" : "[FAIL]";
    }


    // Display lookup helpers \\

    private static Province locationOf(UnitId unit, DiploBNPhase phase) {

        Province location = phase.board().locationOf(unit);

        return location == null ? unit.origin() : location;

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

        for (DiploBNOrderResolution resolution : phase.resolutions())
            nations.add(resolution.nation());

        for (DiploBNOrderResolution resolution : phase.retreatResolutions())
            nations.add(resolution.nation());

        return nations;

    }

    private static String fullNationName(Nation nation) {

        String name = nation.name().toLowerCase();

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);

    }

    private static String unitMarker(UnitType unitType) {

        return switch (unitType) {
            case ARMY -> "A";
            case FLEET -> "F";
        };

    }


}