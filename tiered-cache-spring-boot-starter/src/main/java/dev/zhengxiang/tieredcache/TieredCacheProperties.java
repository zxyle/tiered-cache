package dev.zhengxiang.tieredcache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 二级缓存配置属性
 * 支持按 cacheName 配置不同的一致性/可用性策略
 */
@Data
@ConfigurationProperties(prefix = "cache.tiered")
public class TieredCacheProperties {

    /**
     * 是否启用二级缓存自动配置
     */
    private boolean enabled = true;

    /**
     * 缓存 key 前缀
     */
    private String cachePrefix = "cache:";

    /**
     * 预定义缓存名称列表。为空或未配置时仅支持动态创建缓存。
     */
    private List<String> cacheNames = new ArrayList<>();

    /**
     * 本地缓存配置
     */
    private LocalConfig local = new LocalConfig();

    /**
     * 远程缓存配置
     */
    private RemoteConfig remote = new RemoteConfig();

    /**
     * 全局默认降级策略
     */
    private FallbackStrategy defaultFallbackStrategy = FallbackStrategy.THROW;

    /**
     * 全局默认 clear 模式
     */
    private ClearMode defaultClearMode = ClearMode.SAFE;

    /**
     * 按 cacheName 配置不同策略
     */
    private Map<String, CacheStrategy> caches = new HashMap<>();

    /**
     * 获取预定义缓存名称（不可变）。为 null 或空时表示仅动态模式。
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
