package domain;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public abstract class Constants {

    // private constructor
    private Constants() {   }

    public static final int STARTING_YEAR = 1901;

    // For more info on ANSI constants, see:
    // https://gist.github.com/fnky/458719343aabd01cfb17a3a4f7296797
    public static final String ANSI_RESET           = "\u001B[0m";
    public static final String ANSI_GREEN           = "\u001B[32m";
    public static final String ANSI_RED             = "\u001B[31m";
    public static final String ANSI_ORANGE          = "\u001B[33m";
    public static final String ANSI_YELLOW          = "\u001B[93m";
    public static final String ANSI_BRIGHTWHITE     = "\u001B[97m";

    public static final String ANSI_BOLD            = "\u001B[1m";
    public static final String ANSI_DIM             = "\u001B[2m";
    public static final String ANSI_CYAN            = "\u001B[36m";
    public static final String ANSI_BG_LIGHT        = "\u001B[48;2;245;245;245m";

    // Backstabbr palette recorded by Backstabbr Helper.
    // True-color escapes keep these independent of the terminal's basic palette.
    public static final String ANSI_AUSTRIA         = "\u001B[38;2;204;0;0m";      // #cc0000
    public static final String ANSI_ENGLAND         = "\u001B[38;2;0;0;170m";      // #0000aa
    public static final String ANSI_FRANCE          = "\u001B[38;2;153;153;255m";  // #9999ff
    public static final String ANSI_GERMANY         = "\u001B[38;2;0;0;0m";        // #000000
    public static final String ANSI_ITALY           = "\u001B[38;2;0;170;0m";      // #00aa00
    public static final String ANSI_RUSSIA          = "\u001B[38;2;187;0;187m";    // #bb00bb
    public static final String ANSI_TURKEY          = "\u001B[38;2;187;187;0m";    // #bbbb00


    public static String nationColor(Nation nation) {
        return switch (nation) {
            case AUSTRIA -> ANSI_AUSTRIA;
            case ENGLAND -> ANSI_ENGLAND;
            case FRANCE -> ANSI_FRANCE;
            case GERMANY -> ANSI_GERMANY;
            case ITALY -> ANSI_ITALY;
            case RUSSIA -> ANSI_RUSSIA;
            case TURKEY -> ANSI_TURKEY;
        };
    }


    // used in old, inefficient implementation of `Justice`
    /*public static int factorial(int n) {
        int product = 1;
        for (int i = n; i > 1; i--)
            product *= i;
        return product;
    }*/

    public static Integer[] range(int n) {
        Integer[] range = new Integer[n];
        for (int i = 0; i < n; i++)
            range[i] = i;
        return range;
    }

    public static void printTimestamp() {
        System.out.println();
        System.out.println(ZonedDateTime.now(
                ZoneId.of( "America/Montreal" )));
        System.out.println("----------------------------------------\n");
    }

}