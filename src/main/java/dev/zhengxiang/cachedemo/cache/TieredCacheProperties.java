package dev.zhengxiang.cachedemo.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 二级缓存配置属性
 * 支持按 cacheName 配置不同的一致性/可用性策略
 */
@Data
@ConfigurationProperties(prefix = "cache.tiered")
public class TieredCacheProperties {

    /**
     * 缓存 key 前缀
     */
    private String cachePrefix = "cache:";

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
     * 本地缓存配置
     */
    @Data
    public static class LocalConfig {
        /**
         * 默认最大缓存条数
         */
        private int maximumSize = 1000;

        /**
         * 默认写入后过期时间
         */
        private Duration expireAfterWrite = Duration.ofMinutes(5);
    }

    /**
     * 远程缓存配置
     */
    @Data
    public static class RemoteConfig {
        /**
         * 默认 TTL
         */
        private Duration defaultTtl = Duration.ofHours(1);

        /**
         * 空值缓存 TTL（防止缓存穿透）
         */
        private Duration nullValueTtl = Duration.ofMinutes(1);

        /**
         * TTL 随机因子（防止缓存雪崩）
         * 实际 TTL = baseTtl * (1 + random(-factor, factor))
         */
        private double ttlRandomFactor = 0.1;

        /**
         * 分布式锁等待时间（毫秒）
         */
        private long lockWaitTimeMs = 500;

        /**
         * 缓存失效广播 Topic 前缀
         */
        private String cacheTopicPrefix = "cache:topic:";
    }

    /**
     * 单个缓存的策略配置
     */
    @Data
    public static class CacheStrategy {
        /**
         * 远程缓存 TTL
         */
        private Duration ttl;

        /**
         * 本地缓存 TTL（可选，覆盖全局配置）
         */
        private Duration localTtl;

        /**
         * 本地缓存最大条数（可选，覆盖全局配置）
         */
        private Integer localMaxSize;

        /**
         * 降级策略（可选，覆盖全局配置）
         */
        private FallbackStrategy fallbackStrategy;

        /**
         * clear 模式（可选，覆盖全局配置）
         */
        private ClearMode clearMode;
    }

    /**
     * 降级策略枚举
     */
    public enum FallbackStrategy {
        /**
         * 抛异常（保护DB，高一致性场景）
         */
        THROW,

        /**
         * 降级查DB（保证可用性场景）
         */
        FALLBACK
    }

    /**
     * clear() 模式枚举
     */
    public enum ClearMode {
        /**
         * 安全模式：仅清除 L1，L2 依赖 TTL 自然过期
         */
        SAFE,

        /**
         * 完全模式：清除 L1 + L2（使用 SCAN 批量删除）
         */
        FULL
    }

    /**
     * 获取指定缓存的策略，未配置的属性使用默认值
     *
     * @param cacheName 缓存名称
     * @return 缓存策略（已填充默认值）
     */
    public CacheStrategy getEffectiveStrategy(String cacheName) {
        CacheStrategy strategy = caches.get(cacheName);
        CacheStrategy effective = new CacheStrategy();

        if (strategy != null) {
            // 使用配置的值
            effective.setTtl(strategy.getTtl());
            effective.setLocalTtl(strategy.getLocalTtl());
            effective.setLocalMaxSize(strategy.getLocalMaxSize());
            effective.setFallbackStrategy(strategy.getFallbackStrategy());
            effective.setClearMode(strategy.getClearMode());
        }

        // 填充默认值
        if (effective.getTtl() == null) {
            effective.setTtl(remote.getDefaultTtl());
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
