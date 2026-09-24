package _app;

import domain.Constants;
import parsing.diplobn.DiploBNAdjudicationComparison;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;

import java.io.IOException;
import java.util.Scanner;


/**
 * Downloads one DiploBN game and compares its recorded movement outcomes with
 * JReferee's independently adjudicated results.
 */
public final class DiploBNAdjudicationComparator {


    private DiploBNAdjudicationComparator() {
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
        int mismatchingOrders = 0;

        for (var phase : imported.phases()) {

            if (phase.movementOrders().isEmpty())
                continue;

            DiploBNAdjudicationComparison comparison =
                    DiploBNAdjudicationComparison.compare(phase);

            comparedOrders += comparison.comparedCount();
            matchingOrders += comparison.matchingCount();
            mismatchingOrders += comparison.mismatchingCount();

            System.out.printf(
                    "%s  [%d/%d match]",
                    formatSourcePhase(phase.sourcePhase()),
                    comparison.matchingCount(),
                    comparison.comparedCount());

            if (comparison.mismatchingCount() == 0) {
                System.out.println("  [OK]");
                continue;
            }

            System.out.printf("   %s[MISMATCH]%s\n", Constants.ANSI_RED, Constants.ANSI_RESET);

            for (var entry : comparison.entries()) {

                if (!entry.compared() || entry.matches())
                    continue;

                System.out.printf(
                        "    SOURCE=%-5b JREFEREE=%-5b %s%n",
                        entry.sourceSuccessful(),
                        entry.adjudicatedOrder().verdict,
                        entry.adjudicatedOrder()
                );

                if (entry.sourceReason() != null)
                    System.out.printf(
                            "      Source reason: %s%n",
                            entry.sourceReason());
            }

        }

        System.out.println();
        System.out.println("----------------------------------------");
        System.out.printf(
                "TOTAL MATCHES:    [%d/%d]%n",
                matchingOrders,
                comparedOrders);
        System.out.printf(
                "TOTAL MISMATCHES: [%s%d%s]%n",
                Constants.ANSI_RED,
                mismatchingOrders,
                Constants.ANSI_RESET);
        System.out.println("----------------------------------------");

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