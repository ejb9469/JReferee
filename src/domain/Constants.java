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

    // One country palette for both terminal and browser output.
    public static final int RGB_AUSTRIA             = 0xcc0000;
    public static final int RGB_ENGLAND             = 0x0000aa;
    public static final int RGB_FRANCE              = 0x9999ff;
    public static final int RGB_GERMANY             = 0x000000;
    public static final int RGB_ITALY               = 0x00aa00;
    public static final int RGB_RUSSIA              = 0xbb00bb;
    public static final int RGB_TURKEY              = 0xbbbb00;

    public static final String ANSI_AUSTRIA         = ansiColor(RGB_AUSTRIA);
    public static final String ANSI_ENGLAND         = ansiColor(RGB_ENGLAND);
    public static final String ANSI_FRANCE          = ansiColor(RGB_FRANCE);
    public static final String ANSI_GERMANY         = ansiColor(RGB_GERMANY);
    public static final String ANSI_ITALY           = ansiColor(RGB_ITALY);
    public static final String ANSI_RUSSIA          = ansiColor(RGB_RUSSIA);
    public static final String ANSI_TURKEY          = ansiColor(RGB_TURKEY);


    public static int nationRgb(Nation nation) {
        return switch (nation) {
            case AUSTRIA -> RGB_AUSTRIA;
            case ENGLAND -> RGB_ENGLAND;
            case FRANCE -> RGB_FRANCE;
            case GERMANY -> RGB_GERMANY;
            case ITALY -> RGB_ITALY;
            case RUSSIA -> RGB_RUSSIA;
            case TURKEY -> RGB_TURKEY;
        };
    }

    public static String nationHex(Nation nation) {
        return String.format("#%06x", nationRgb(nation));
    }

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

    private static String ansiColor(int rgb) {
        return "\u001B[38;2;"
                + ((rgb >> 16) & 255) + ";"
                + ((rgb >> 8) & 255) + ";"
                + (rgb & 255) + "m";
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