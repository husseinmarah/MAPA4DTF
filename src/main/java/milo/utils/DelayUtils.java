package milo.utils;

import java.util.concurrent.ThreadLocalRandom;

public class DelayUtils {
    public static void randomDelay(int minMs, int maxMs) {
        try {
            int delay = ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Delay was interrupted", e);
        }
    }

    public static void randomDelay() {
        randomDelay(1, 500); // Default 1-500 ms range
    }

}
