package _app.util;

import java.util.Scanner;

/**
 * Shared console helpers for DiploBN command-line applications.
 */
public abstract class AbstractDiploBNConsoleApp {


    protected final String requestedGamePageUrl(String[] args) {

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

    protected final void printMetadata(
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

    protected static String formatSeasonalSourcePhase(int sourcePhase) {

        int year = sourcePhase / 10;

        return switch (sourcePhase % 10) {
            case 1 -> "S" + year;
            case 2 -> "F" + year;
            case 3 -> "W" + year;
            default -> formatNumericSourcePhase(sourcePhase);
        };

    }

    protected static String formatNumericSourcePhase(int sourcePhase) {
        return sourcePhase / 10 + "." + sourcePhase % 10;
    }

}
