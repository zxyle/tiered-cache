package dev.zhengxiang.tieredcache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for tiered cache.
 * Supports per-cacheName consistency and availability strategies.
 */
@Data
@ConfigurationProperties(prefix = "cache.tiered")
public class TieredCacheProperties {

    /**
     * Whether to enable tiered cache auto-configuration.
     */
    private boolean enabled = true;

    /**
     * Cache key prefix.
     */
    private String cachePrefix = "cache:";

    /**
     * Predefined cache names. If empty or unset, only dynamic cache creation is supported.
     */
    private List<String> cacheNames = new ArrayList<>();

    /**
     * Local cache configuration.
     */
    private LocalConfig local = new LocalConfig();

    /**
     * Remote cache configuration.
     */
    private RemoteConfig remote = new RemoteConfig();

    /**
     * Global default fallback strategy.
     */
    private FallbackStrategy defaultFallbackStrategy = FallbackStrategy.THROW;

    /**
     * Global default clear mode.
     */
    private ClearMode defaultClearMode = ClearMode.SAFE;

    /**
     * Per-cacheName strategy overrides.
     */
    private Map<String, CacheStrategy> caches = new HashMap<>();

    /**
     * Returns predefined cache names (immutable). Null or empty means dynamic-only mode.
     */
    public List<String> getCacheNames() {
        return cacheNames == null || cacheNames.isEmpty() ? List.of() : List.copyOf(cacheNames);
    }

    @Data
    public static class LocalConfig {
        private int maximumSize = 1000;
        private Duration expireAfterWrite = Duration.ofMinutes(5);
    }

    @Data
    public static class RemoteConfig {
        private Duration defaultTtl = Duration.ofHours(1);
        private Duration nullValueTtl = Duration.ofMinutes(1);
        private double ttlRandomFactor = 0.1;
        private long lockWaitTimeMs = 500;
        private String cacheTopicPrefix = "cache:topic:";
    }

    @Data
    public static class CacheStrategy {
        private Duration remoteTtl;
        private Duration localTtl;
        private Integer localMaxSize;
        private FallbackStrategy fallbackStrategy;
        private ClearMode clearMode;
    }

    public enum FallbackStrategy {
        THROW,
        FALLBACK
    }

    public enum ClearMode {
        SAFE,
        FULL
    }

    public CacheStrategy getEffectiveStrategy(String cacheName) {
        CacheStrategy strategy = caches.get(cacheName);
        CacheStrategy effective = new CacheStrategy();

        if (strategy != null) {
            effective.setRemoteTtl(strategy.getRemoteTtl());
            effective.setLocalTtl(strategy.getLocalTtl());
            effective.setLocalMaxSize(strategy.getLocalMaxSize());
            effective.setFallbackStrategy(strategy.getFallbackStrategy());
            effective.setClearMode(strategy.getClearMode());
        }

        if (effective.getRemoteTtl() == null) {
            effective.setRemoteTtl(remote.getDefaultTtl());
        }
        if (effective.getLocalTtl() == null) {
            effective.setLocalTtl(local.getExpireAfterWrite());
        }
        if (effective.getLocalMaxSize() == null) {
            effective.setLocalMaxSize(local.getMaximumSize());
        }
        if (effective.getFallbackStrategy() == null) {
            effective.setFallbackStrategy(defaultFallbackStrategy);
        }
        if (effective.getClearMode() == null) {
            effective.setClearMode(defaultClearMode);
        }

        return effective;
    }
}
