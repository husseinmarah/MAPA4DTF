package milo.utils;

import java.util.concurrent.ThreadLocalRandom;

public class Helper {
    public static int getNextInt(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}

