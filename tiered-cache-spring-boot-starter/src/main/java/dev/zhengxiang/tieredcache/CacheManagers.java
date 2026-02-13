package dev.zhengxiang.tieredcache;

/**
 * Cache manager name constants.
 */
public final class CacheManagers {

    /**
     * Default tiered cache manager (no need to specify explicitly).
     */
    public static final String DEFAULT = "tiered";

    /**
     * Redis-only cache manager.
     */
    public static final String REDIS = "redis";

    /**
     * Local-only cache manager.
     */
    public static final String LOCAL = "local";

    /**
     * Private constructor. instances are not allowed.
     */
    private CacheManagers() {
    }
}
