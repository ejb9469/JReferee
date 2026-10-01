package _app;

import _app.util.AbstractDiploBNConsoleApp;
import contracts.OrderForm;
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


public final class DBNGameImporter extends AbstractDiploBNConsoleApp {

    public static void main(String[] args) {
        new DBNGameImporter().run(args);
    }

    private void run(String[] args) {

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

    private void printSummary(DiploBNGame imported) {

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

        System.out.printf("[%d] %s %s%n",
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

            List<Map.Entry<UnitId, Province>> forces = new ArrayList<>();

            for (Map.Entry<UnitId, Province> entry : phase.board().locations().entrySet())
                if (entry.getKey().owner() == nation)
                    forces.add(entry);

            if (forces.isEmpty())
                continue;

            System.out.println("      " + fullNationName(nation) + ":");

            for (Map.Entry<UnitId, Province> entry : forces)
                System.out.println("        " + OrderForm.format(entry.getKey(), entry.getValue()));

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

    private static List<String> commandsOf(Nation nation, DiploBNPhase phase) {

        List<String> commands = new ArrayList<>();

        for (Order order : phase.movementOrders()) {

            if (order.owner() != nation)
                continue;

            Province location = phase.board().locationOf(order.unit());

            commands.add(OrderForm.format(order, location) + " "
                    + markerFor(phase.resolutions(), nation, location, false));

        }

        for (RetreatOrder order : phase.retreatOrders()) {

            if (order.owner() != nation)
                continue;

            Province location = phase.board().locationOf(order.unit());

            commands.add(OrderForm.format(order, location) + " "
                    + markerFor(phase.retreatResolutions(), nation, location, true));

        }

        for (AdjustmentOrder order : phase.adjustmentOrders()) {

            if (order.owner() != nation)
                continue;

            Province location = issuingProvinceOf(order, phase);

            commands.add(OrderForm.format(order, location) + " "
                    + markerFor(phase.resolutions(), nation, location, false));

        }

        // Retreat disbands may exist only as source resolutions.
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
            Nation nation, Province province, DiploBNPhase phase) {

        for (RetreatOrder order : phase.retreatOrders()) {
            if (order.owner() == nation
                    && Province.equalsIgnoreCoast(phase.board().locationOf(order.unit()), province))
                return true;
        }

        return false;

    }

    private static Province issuingProvinceOf(AdjustmentOrder order, DiploBNPhase phase) {

        if (order instanceof BuildOrder build)
            return build.location();

        if (order instanceof DisbandOrder disband)
            return phase.board().locationOf(disband.unit());

        if (order instanceof WaiveOrder)
            return null;

        throw new IllegalStateException(
                "Unsupported adjustment order type: " + order.getClass().getName());

    }

    private static String formatRetreatDisband(
            DiploBNOrderResolution resolution, DiploBNPhase phase) {

        UnitId unit = UnitLookup.firstAt(
                phase.board().locations(), resolution.issuingProvince(), resolution.nation());

        adjudication.Order disband = new adjudication.Order(
                resolution.nation(),
                unit == null ? null : unit.unitType(),
                resolution.issuingProvince(),
                OrderType.RETREAT);

        return disband.toString();

    }


    // Result markers remain separate from notation. \\

    private static String markerFor(
            List<DiploBNOrderResolution> resolutions, Nation nation,
            Province issuingProvince, boolean retreatOrder) {

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

    private static Set<Nation> nationsIn(DiploBNPhase phase) {

        Set<Nation> nations = EnumSet.noneOf(Nation.class);

        for (UnitId unit : phase.board().locations().keySet())
            nations.add(unit.owner());

        for (Order order : phase.movementOrders())
            nations.add(order.owner());

        for (RetreatOrder order : phase.retreatOrders())
            nations.add(order.owner());

        for (AdjustmentOrder order : phase.adjustmentOrders())
            nations.add(order.owner());

        for (DiploBNOrderResolution resolution : phase.resolutions())
            nations.add(resolution.nation());

        for (DiploBNOrderResolution resolution : phase.retreatResolutions())
            nations.add(resolution.nation());

        return nations;

    }

    private static String fullNationName(Nation nation) {

        String name = nation.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);

    }

}