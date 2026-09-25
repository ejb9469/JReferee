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
public final class DiploBNChecker {


    private DiploBNChecker() {
    }


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

    private static void compareMovementPhases(
            DiploBNGame imported
    ) {

        int comparedOrders = 0;
        int matchingOrders = 0;
        int coastAmbiguousOrders = 0;
        int ineffectiveSupportOrders = 0;
        int mismatchingOrders = 0;

        for (var phase : imported.phases()) {

            if (phase.movementOrders().isEmpty())
                continue;

            DiploBNAdjudicationComparator comparison =
                    DiploBNAdjudicationComparator.compare(phase);

            comparedOrders += comparison.comparedCount();
            matchingOrders += comparison.matchingCount();
            coastAmbiguousOrders += comparison.coastAmbiguousCount();
            ineffectiveSupportOrders +=
                    comparison.ineffectiveSupportCount();
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

            if (comparison.mismatchingCount() == 0
                    && comparison.coastAmbiguousCount() == 0
                    && comparison.ineffectiveSupportCount() == 0) {
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

                if (!entry.coastAmbiguous()
                        && !entry.ineffectiveSupport()
                        && !entry.mismatches())
                    continue;

                if (entry.coastAmbiguous()) {
                    System.out.printf(
                            "    %s[COAST AMBIGUOUS]%s "
                                    + "SOURCE=%-5b JREFEREE=%-5b %s%n",
                            Constants.ANSI_ORANGE,
                            Constants.ANSI_RESET,
                            entry.sourceSuccessful(),
                            entry.adjudicatedOrder().verdict,
                            entry.adjudicatedOrder()
                    );
                } else if (entry.ineffectiveSupport()) {
                    System.out.printf(
                            "    %s[INEFFECTIVE SUPPORT]%s "
                                    + "SOURCE=%-5b JREFEREE=%-5b %s%n",
                            Constants.ANSI_YELLOW,
                            Constants.ANSI_RESET,
                            entry.sourceSuccessful(),
                            entry.adjudicatedOrder().verdict,
                            entry.adjudicatedOrder()
                    );
                } else {
                    System.out.printf(
                            "    %s[MISMATCH]%s "
                                    + "SOURCE=%-5b JREFEREE=%-5b %s%n",
                            Constants.ANSI_RED,
                            Constants.ANSI_RESET,
                            entry.sourceSuccessful(),
                            entry.adjudicatedOrder().verdict,
                            entry.adjudicatedOrder()
                    );
                }

                if (entry.sourceReason() != null)
                    System.out.printf(
                            "      Source reason: %s%n",
                            entry.sourceReason());
            }

        }

        System.out.println();
        System.out.println("----------------------------------------");
        System.out.printf(
                "TOTAL MATCHES:              [%d/%d]%n",
                matchingOrders,
                comparedOrders);
        System.out.printf(
                "TOTAL COAST AMBIGUITIES:    %s[%d]%s%n",
                Constants.ANSI_ORANGE,
                coastAmbiguousOrders,
                Constants.ANSI_RESET);
        System.out.printf(
                "TOTAL INEFFECTIVE SUPPORTS: %s[%d]%s%n",
                Constants.ANSI_YELLOW,
                ineffectiveSupportOrders,
                Constants.ANSI_RESET);
        System.out.printf(
                "TOTAL DEFINITE MISMATCHES:  %s[%d]%s%n",
                Constants.ANSI_RED,
                mismatchingOrders,
                Constants.ANSI_RESET);
        System.out.println("----------------------------------------");

    }

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