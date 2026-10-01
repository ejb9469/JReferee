package _app;

import java.io.PrintStream;
import java.util.Objects;
import java.util.Scanner;


/**
 * Shared input and presentation helpers for DiploBN console applications.
 */
public abstract class DBNCliFormatting {


    private DBNCliFormatting() {  }


    public static String requestedGamePageUrl(String[] args) {

        if (args.length > 0)
            return args[0];

        return requestedGamePageUrl(new Scanner(System.in), System.out, System.err);

    }

    /**
     * Reads an interactive URL without closing the caller-owned input.
     */
    public static String requestedGamePageUrl(
            Scanner input,
            PrintStream output,
            PrintStream errors
    ) {

        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(errors, "errors");

        output.println(
                "Enter DiploBN game URL "
                        + "(for example: https://diplobn.com/game/?GameID=12990):");

        String url = input.hasNextLine() ? input.nextLine().strip() : null;

        if (url == null || url.isBlank()) {
            errors.println("No DiploBN game URL was supplied");
            return null;
        }

        return url;

    }

    public static void printMetadata(String label, String value) {
        printMetadata(System.out, label, value);
    }

    public static void printMetadata(PrintStream output, String label, String value) {

        Objects.requireNonNull(output, "output");

        output.printf(
                "%-14s %s%n",
                label + ":",
                value == null ? "<not supplied>" : value);

    }

    public static String formatSourcePhase(int sourcePhase) {

        int year = sourcePhase / 10;

        return switch (sourcePhase % 10) {
            case 1 -> "S" + year;
            case 2 -> "F" + year;
            case 3 -> "W" + year;
            default -> formatNumericSourcePhase(sourcePhase);
        };

    }

    public static String formatNumericSourcePhase(int sourcePhase) {
        return sourcePhase / 10 + "." + sourcePhase % 10;
    }


}