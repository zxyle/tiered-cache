package dev.zhengxiang.tieredcache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.spring.cache.CacheConfig;
import org.redisson.spring.cache.RedissonSpringCacheManager;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展 RedissonSpringCacheManager，支持动态创建的缓存使用默认 TTL
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
        this.configMap = new ConcurrentHashMap<>(config);
    }

    @Override
    public Cache getCache(@NonNull String name) {
        if (!configMap.containsKey(name)) {
            long defaultTtlMs = TieredCacheUtils.randomizeTtl(
                    properties.getRemote().getDefaultTtl().toMillis(),
                    properties.getRemote().getTtlRandomFactor()
            );
            CacheConfig defaultConfig = new CacheConfig(defaultTtlMs, 0);
            configMap.put(name, defaultConfig);
            setConfig(configMap);
            log.info("为动态缓存设置默认 TTL: name={}, ttl={}ms", name, defaultTtlMs);
        }
        return super.getCache(name);
    }

}
