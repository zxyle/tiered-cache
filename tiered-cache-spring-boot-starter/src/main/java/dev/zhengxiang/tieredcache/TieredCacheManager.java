package dev.zhengxiang.tieredcache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.zhengxiang.tieredcache.TieredCacheProperties.CacheStrategy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.cache.CacheManager;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 二级缓存管理器：管理 TieredCache 实例，支持预定义或动态创建
 */
@Slf4j
public class TieredCacheManager implements CacheManager {

    private final CacheManager remoteCacheManager;
    private final CacheMessagePublisher messagePublisher;
    private final RedissonClient redissonClient;
    private final TieredCacheProperties properties;
    private final Codec codec;
    private final Map<String, TieredCache> cacheMap = new ConcurrentHashMap<>();
    private final Collection<String> predefinedCacheNames;
    private final boolean dynamic;

    public TieredCacheManager(CacheManager remoteCacheManager,
                              CacheMessagePublisher messagePublisher,
                              RedissonClient redissonClient,
                              TieredCacheProperties properties,
                              @Nullable Collection<String> predefinedCacheNames,
                              Codec codec) {
        this.remoteCacheManager = remoteCacheManager;
        this.messagePublisher = messagePublisher;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.codec = codec;

        List<String> names = predefinedCacheNames == null || predefinedCacheNames.isEmpty()
                ? List.of()
                : List.copyOf(predefinedCacheNames);

        if (!names.isEmpty()) {
            this.predefinedCacheNames = Collections.unmodifiableCollection(names);
            this.dynamic = false;
            for (String name : names) {
                this.cacheMap.put(name, createTieredCache(name));
            }
            log.info("TieredCacheManager 初始化完成，预定义缓存: {}", names);
        } else {
            this.predefinedCacheNames = Collections.emptySet();
            this.dynamic = true;
            log.info("TieredCacheManager 初始化完成，支持动态创建缓存");
        }
    }

    @Override
    public org.springframework.cache.Cache getCache(@NonNull String name) {
        TieredCache cache = this.cacheMap.get(name);
        if (cache != null) {
            return cache;
        }
        if (!this.dynamic) {
            log.warn("缓存不存在且不允许动态创建: {}", name);
            return null;
        }
        return this.cacheMap.computeIfAbsent(name, this::createTieredCache);
    }

    @NonNull
    @Override
    public Collection<String> getCacheNames() {
        if (this.dynamic) {
            return Collections.unmodifiableSet(this.cacheMap.keySet());
        }
        return this.predefinedCacheNames;
    }

    private TieredCache createTieredCache(String name) {
        CacheStrategy strategy = properties.getEffectiveStrategy(name);
        Cache<Object, Object> localCache = Caffeine.newBuilder()
                .maximumSize(strategy.getLocalMaxSize())
                .expireAfterWrite(strategy.getLocalTtl())
                .recordStats()
                .build();
        org.springframework.cache.Cache remoteCache = remoteCacheManager.getCache(name);
        if (remoteCache == null) {
            log.warn("远程缓存创建失败: {}", name);
        }
        log.info("创建二级缓存: name={}, localMaxSize={}, localTtl={}, remoteTtl={}, fallback={}, clearMode={}",
                name, strategy.getLocalMaxSize(), strategy.getLocalTtl(),
                strategy.getRemoteTtl(), strategy.getFallbackStrategy(), strategy.getClearMode());
        return new TieredCache(name, localCache, remoteCache, messagePublisher, redissonClient, properties, codec);
    }

    @Nullable
    public TieredCache getTieredCache(String name) {
        return this.cacheMap.get(name);
    }

    public Collection<TieredCache> getAllTieredCaches() {
        return Collections.unmodifiableCollection(this.cacheMap.values());
    }
}
