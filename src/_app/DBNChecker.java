package _app;

import domain.Constants;
import parsing.diplobn.DiploBNAdjudicationComparator;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;

import java.io.IOException;
import java.util.Scanner;


/**
 * Downloads one DiploBN game and compares its recorded movement outcomes with
 * JReferee's independently adjudicated results.
 */
public final class DBNChecker {


    // private constructor
    private DBNChecker() {  }


    // Entry point \\

    public static void main(String[] args) {

        String gamePageUrl = requestedGamePageUrl(args);

        if (gamePageUrl == null)
            return;

        try {
            System.out.println();
            System.out.println("Downloading and comparing DiploBN game...");
            System.out.println();

            DiploBNGame imported = new DiploBNGameClient()
                    .loadGamePage(gamePageUrl);

            printGameHeader(imported);
            compareMovementPhases(imported);

        } catch (IllegalArgumentException exception) {
            System.err.println(
                    "Unable to import or compare DiploBN game: "
                            + exception.getMessage());

            exception.printStackTrace(System.err);
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


    // Input helpers \\

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


    // Header output \\

    private static void printGameHeader(DiploBNGame imported) {

        System.out.println("DIPLOBN ADJUDICATION COMPARISON:");
        System.out.println();

        printMetadata("Competition", imported.competition());
        printMetadata("Game label", imported.gameLabel());
        printMetadata("Source URL", imported.sourceUrl());

        System.out.println(
                "Source phases: "
                        + imported.phases().size());

        System.out.println();

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


    // Movement comparison \\

    private static void compareMovementPhases(DiploBNGame imported) {

        int comparedOrders = 0;
        int matchingOrders = 0;
        int coastAmbiguousOrders = 0;
        int ineffectiveSupportOrders = 0;
        int ineffectiveConvoyOrders = 0;
        int invalidConvoyDestinationOrders = 0;
        int compatibilityDifferences = 0;
        int mismatchingOrders = 0;

        for (var phase : imported.phases()) {

            if (phase.movementOrders().isEmpty())
                continue;

            DiploBNAdjudicationComparator comparison =
                    DiploBNAdjudicationComparator.compare(phase);

            int phaseInvalidConvoyDestinations = 0;

            for (var entry : comparison.entries()) {
                if (entry.invalidConvoyDestination())
                    phaseInvalidConvoyDestinations++;
            }

            comparedOrders += comparison.comparedCount();
            matchingOrders += comparison.matchingCount();
            coastAmbiguousOrders += comparison.coastAmbiguousCount();
            ineffectiveSupportOrders += comparison.ineffectiveSupportCount();
            ineffectiveConvoyOrders += comparison.ineffectiveConvoyCount();
            invalidConvoyDestinationOrders += phaseInvalidConvoyDestinations;
            compatibilityDifferences += comparison.compatibilityDifferenceCount();
            mismatchingOrders += comparison.mismatchingCount();

            System.out.printf(
                    "%s  [%d/%d match]",
                    formatSourcePhase(phase.sourcePhase()),
                    comparison.matchingCount(),
                    comparison.comparedCount());

            if (comparison.coastAmbiguousCount() > 0)
                System.out.printf(
                        "  %s[%d COAST AMBIGUOUS]%s",
                        Constants.ANSI_ORANGE,
                        comparison.coastAmbiguousCount(),
                        Constants.ANSI_RESET);

            if (comparison.ineffectiveSupportCount() > 0)
                System.out.printf(
                        "  %s[%d INEFFECTIVE SUPPORT]%s",
                        Constants.ANSI_YELLOW,
                        comparison.ineffectiveSupportCount(),
                        Constants.ANSI_RESET);

            if (comparison.ineffectiveConvoyCount() > 0)
                System.out.printf(
                        "  %s[%d INEFFECTIVE CONVOY]%s",
                        Constants.ANSI_YELLOW,
                        comparison.ineffectiveConvoyCount(),
                        Constants.ANSI_RESET);

            if (phaseInvalidConvoyDestinations > 0)
                System.out.printf(
                        "  %s[%d INVALID CONVOY DESTINATION]%s",
                        Constants.ANSI_YELLOW,
                        phaseInvalidConvoyDestinations,
                        Constants.ANSI_RESET);

            if (comparison.mismatchingCount() == 0
                    && comparison.compatibilityDifferenceCount() == 0) {
                System.out.println("  [OK]");
                continue;
            }

            if (comparison.mismatchingCount() == 0) {
                System.out.printf(
                        "  %s[COMPATIBILITY DIFFERENCE]%s%n",
                        Constants.ANSI_YELLOW,
                        Constants.ANSI_RESET);
            } else {
                System.out.printf(
                        "  %s[MISMATCH]%s%n",
                        Constants.ANSI_RED,
                        Constants.ANSI_RESET);
            }

            for (var entry : comparison.entries()) {

                if (!entry.compatibilityDifference()
                        && !entry.mismatches())
                    continue;

                printDifference(entry);

            }

        }

        System.out.println();
        System.out.println("----------------------------------------");

        System.out.printf(
                "%-35s [%d/%d]%n",
                "TOTAL MATCHES:",
                matchingOrders,
                comparedOrders);

        System.out.printf(
                "%-35s %s[%d]%s%n",
                "TOTAL COAST AMBIGUITIES:",
                Constants.ANSI_ORANGE,
                coastAmbiguousOrders,
                Constants.ANSI_RESET);

        System.out.printf(
                "%-35s %s[%d]%s%n",
                "TOTAL INEFFECTIVE SUPPORTS:",
                Constants.ANSI_YELLOW,
                ineffectiveSupportOrders,
                Constants.ANSI_RESET);

        System.out.printf(
                "%-35s %s[%d]%s%n",
                "TOTAL INEFFECTIVE CONVOYS:",
                Constants.ANSI_YELLOW,
                ineffectiveConvoyOrders,
                Constants.ANSI_RESET);

        System.out.printf(
                "%-35s %s[%d]%s%n",
                "TOTAL INVALID CONVOY DESTINATIONS:",
                Constants.ANSI_YELLOW,
                invalidConvoyDestinationOrders,
                Constants.ANSI_RESET);

        System.out.printf(
                "%-35s %s[%d]%s%n",
                "TOTAL COMPATIBILITY DIFFERENCES:",
                Constants.ANSI_YELLOW,
                compatibilityDifferences,
                Constants.ANSI_RESET);

        System.out.printf(
                "%-35s %s[%d]%s%n",
                "TOTAL DEFINITE MISMATCHES:",
                Constants.ANSI_RED,
                mismatchingOrders,
                Constants.ANSI_RESET);

        System.out.println("----------------------------------------");

    }


    // Difference output \\

    private static void printDifference(
            DiploBNAdjudicationComparator.Entry entry
    ) {

        String label;
        String color;

        switch (entry.difference()) {

            case COAST_AMBIGUOUS -> {
                label = "COAST AMBIGUOUS";
                color = Constants.ANSI_ORANGE;
            }

            case INEFFECTIVE_SUPPORT -> {
                label = "INEFFECTIVE SUPPORT";
                color = Constants.ANSI_YELLOW;
            }

            case INEFFECTIVE_CONVOY -> {
                label = "INEFFECTIVE CONVOY";
                color = Constants.ANSI_YELLOW;
            }

            case INVALID_CONVOY_DESTINATION -> {
                label = "INVALID CONVOY DESTINATION";
                color = Constants.ANSI_YELLOW;
            }

            case DEFINITE_MISMATCH -> {
                label = "MISMATCH";
                color = Constants.ANSI_RED;
            }

            case NONE -> {
                return;
            }

            default -> throw new IllegalStateException(
                    "Unsupported comparison difference: "
                            + entry.difference());

        }

        System.out.printf(
                "    %s[%s]%s SOURCE=%-5b JREFEREE=%-5b %s%n",
                color,
                label,
                Constants.ANSI_RESET,
                entry.sourceSuccessful(),
                entry.adjudicatedOrder().verdict,
                entry.adjudicatedOrder());

        if (entry.sourceReason() != null)
            System.out.printf(
                    "      Source reason: %s%n",
                    entry.sourceReason());

        if (entry.ineffectiveConvoy()) {
            System.out.println(
                    "      Compatibility reason: no corresponding army move "
                            + "was submitted; convoy fleet was not dislodged.");
        } else if (entry.invalidConvoyDestination()) {
            System.out.printf(
                    "      Compatibility reason: source-success annotation "
                            + "for a convoy into sea province %s; accepted as "
                            + "an annotation difference, retaining JReferee's "
                            + "failed verdict.%n",
                    entry.adjudicatedOrder().pos2);
        }

    }


    // Formatting helpers \\

    /**
     * Formats DiploBN's five-digit phase value as a season plus year.
     */
    private static String formatSourcePhase(int sourcePhase) {

        int year = sourcePhase / 10;
        int turn = sourcePhase % 10;

        return switch (turn) {
            case 1 -> "S" + year;
            case 2 -> "F" + year;
            case 3 -> "W" + year;
            default -> year + "." + turn;
        };

    }


}