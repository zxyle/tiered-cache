package dev.zhengxiang.tieredcache;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility methods for tiered cache.
 */
public final class TieredCacheUtils {

    private TieredCacheUtils() {
    }

    /**
     * Applies a random offset to the base TTL to avoid cache stampede when many keys expire at once.
     *
     * @param baseTtlMs    base TTL in milliseconds
     * @param randomFactor random factor in [0, 1]; e.g. 0.1 means ±10% of baseTtlMs
     * @return randomized TTL in milliseconds, at least 1 so Redis writes remain valid
     */
    public static long randomizeTtl(long baseTtlMs, double randomFactor) {
        if (baseTtlMs <= 0 || randomFactor <= 0) {
            return baseTtlMs;
        }
        long offset = (long) (baseTtlMs * randomFactor);
        long result = baseTtlMs + ThreadLocalRandom.current().nextLong(-offset, offset + 1);
        return Math.max(1, result);
    }
}
