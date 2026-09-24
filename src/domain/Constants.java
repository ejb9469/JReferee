package domain;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class Constants {

    // private constructor
    private Constants() {   }

    public static final int STARTING_YEAR = 1901;

    // For more info on ANSI constants, see:
    // https://gist.github.com/fnky/458719343aabd01cfb17a3a4f7296797
    public static final String ANSI_RESET           = "\u001B[0m";
    public static final String ANSI_RED             = "\u001B[31m";
    public static final String ANSI_ORANGE          = "\u001B[33m";
    public static final String ANSI_YELLOW          = "\u001B[93m";
    public static final String ANSI_BRIGHTWHITE     = "\u001B[97m";


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
