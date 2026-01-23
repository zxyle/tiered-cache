package dev.zhengxiang.cachedemo.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.spring.cache.CacheConfig;
import org.redisson.spring.cache.RedissonSpringCacheManager;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 扩展 RedissonSpringCacheManager，支持动态创建的缓存使用默认 TTL
 * <p>
 * 解决问题：原生 RedissonSpringCacheManager 对于未在 configMap 中预定义的缓存，
 * 会创建无 TTL（永不过期）的缓存，导致内存泄漏和数据不一致。
 */
@Slf4j
public class DynamicTtlRedissonCacheManager extends RedissonSpringCacheManager {

    private final TieredCacheProperties properties;
    private final Map<String, CacheConfig> configMap;

    public DynamicTtlRedissonCacheManager(RedissonClient redisson,
                                          Map<String, CacheConfig> config,
                                          TieredCacheProperties properties) {
        super(redisson, config);
        this.properties = properties;
        // 保存一份引用，用于动态添加配置
        this.configMap = new ConcurrentHashMap<>(config);
    }

    @Override
    public Cache getCache(@NonNull String name) {
        // 如果缓存未在 configMap 中配置，动态添加默认 TTL 配置
        if (!configMap.containsKey(name)) {
            long defaultTtlMs = randomizeTtl(
                    properties.getRemote().getDefaultTtl().toMillis(),
                    properties.getRemote().getTtlRandomFactor()
            );
            CacheConfig defaultConfig = new CacheConfig(defaultTtlMs, 0);
            configMap.put(name, defaultConfig);

            // 调用父类方法重新设置 config，使新配置生效
            setConfig(configMap);

            log.info("为动态缓存设置默认 TTL: name={}, ttl={}ms", name, defaultTtlMs);
        }

        return super.getCache(name);
    }

    /**
     * 为 TTL 添加随机偏移，防止缓存雪崩
     */
    private long randomizeTtl(long baseTtlMs, double randomFactor) {
        if (baseTtlMs <= 0 || randomFactor <= 0) {
            return baseTtlMs;
        }
        long offset = (long) (baseTtlMs * randomFactor);
        long randomOffset = ThreadLocalRandom.current().nextLong(-offset, offset + 1);
        return baseTtlMs + randomOffset;
    }
}
