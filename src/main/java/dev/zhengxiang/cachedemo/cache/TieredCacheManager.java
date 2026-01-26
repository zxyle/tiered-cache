package dev.zhengxiang.cachedemo.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.CacheStrategy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.cache.CacheManager;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 二级缓存管理器
 * <p>
 * 管理 TieredCache 实例，支持按 cacheName 配置不同的策略：
 * - 不同的本地缓存大小和 TTL
 * - 不同的降级策略（THROW/FALLBACK）
 * - 不同的 clear 模式（SAFE/FULL）
 */
@Slf4j
public class TieredCacheManager implements CacheManager {

    /**
     * 远程缓存管理器（L2: Redis）
     */
    private final CacheManager remoteCacheManager;

    /**
     * 缓存消息发布器，用于跨实例同步
     */
    private final CacheMessagePublisher messagePublisher;

    /**
     * Redisson 客户端，用于分布式锁等操作
     */
    private final RedissonClient redissonClient;

    /**
     * 二级缓存配置属性
     */
    private final TieredCacheProperties properties;

    /**
     * 序列化编解码器，用于 Redis 数据序列化
     */
    private final Codec codec;

    /**
     * 缓存实例映射表，key 为缓存名称
     */
    private final Map<String, TieredCache> cacheMap = new ConcurrentHashMap<>();

    /**
     * 预定义的缓存名称集合
     */
    private final Collection<String> predefinedCacheNames;

    /**
     * 是否支持动态创建缓存
     */
    private final boolean dynamic;

    /**
     * 创建二级缓存管理器
     *
     * @param remoteCacheManager   远程缓存管理器（Redis）
     * @param messagePublisher     缓存消息发布器
     * @param redissonClient       Redisson 客户端
     * @param properties           配置属性
     * @param predefinedCacheNames 预定义的缓存名称列表，为 null 时支持动态创建
     * @param codec                序列化编解码器
     */
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

        if (predefinedCacheNames != null && !predefinedCacheNames.isEmpty()) {
            this.predefinedCacheNames = Collections.unmodifiableCollection(predefinedCacheNames);
            this.dynamic = false;
            // 预创建所有缓存
            for (String name : predefinedCacheNames) {
                this.cacheMap.put(name, createTieredCache(name));
            }
            log.info("TieredCacheManager 初始化完成，预定义缓存: {}", predefinedCacheNames);
        } else {
            this.predefinedCacheNames = Collections.emptySet();
            this.dynamic = true;
            log.info("TieredCacheManager 初始化完成，支持动态创建缓存");
        }
    }

    /**
     * 获取指定名称的缓存
     * <p>
     * 动态模式下，如果缓存不存在会自动创建；
     * 非动态模式下，只返回预定义的缓存。
     *
     * @param name 缓存名称
     * @return 缓存实例，非动态模式下缓存不存在时返回 null
     */
    @Override
    public org.springframework.cache.Cache getCache(@NonNull String name) {
        TieredCache cache = this.cacheMap.get(name);
        if (cache != null) {
            return cache;
        }

        if (!this.dynamic) {
            // 非动态模式，只返回预定义的缓存
            log.warn("缓存不存在且不允许动态创建: {}", name);
            return null;
        }

        // 动态创建缓存
        return this.cacheMap.computeIfAbsent(name, this::createTieredCache);
    }

    /**
     * 获取所有缓存名称
     *
     * @return 缓存名称集合（不可修改）
     */
    @NonNull
    @Override
    public Collection<String> getCacheNames() {
        if (this.dynamic) {
            return Collections.unmodifiableSet(this.cacheMap.keySet());
        }
        return this.predefinedCacheNames;
    }

    /**
     * 创建二级缓存实例
     * 根据 cacheName 获取对应的策略配置
     */
    private TieredCache createTieredCache(String name) {
        // 获取该缓存的策略配置
        CacheStrategy strategy = properties.getEffectiveStrategy(name);

        // 创建本地缓存（按策略配置大小和 TTL）
        Cache<Object, Object> localCache = createLocalCache(name, strategy);

        // 获取远程缓存
        org.springframework.cache.Cache remoteCache = remoteCacheManager.getCache(name);
        if (remoteCache == null) {
            log.warn("远程缓存创建失败: {}", name);
        }

        log.info("创建二级缓存: name={}, localMaxSize={}, localTtl={}, remoteTtl={}, fallback={}, clearMode={}",
                name, strategy.getLocalMaxSize(), strategy.getLocalTtl(),
                strategy.getRemoteTtl(), strategy.getFallbackStrategy(), strategy.getClearMode());

        return new TieredCache(name, localCache, remoteCache, messagePublisher, redissonClient, properties, codec);
    }

    /**
     * 为每个缓存创建独立的本地缓存
     * 支持不同的 localTtl 和 localMaxSize
     */
    private Cache<Object, Object> createLocalCache(String name, CacheStrategy strategy) {
        return Caffeine.newBuilder()
                .maximumSize(strategy.getLocalMaxSize())
                .expireAfterWrite(strategy.getLocalTtl())
                .recordStats()
                .build();
    }

    /**
     * 获取 TieredCache 实例（用于缓存同步）
     */
    @Nullable
    public TieredCache getTieredCache(String name) {
        return this.cacheMap.get(name);
    }

    /**
     * 获取所有 TieredCache 实例
     */
    public Collection<TieredCache> getAllTieredCaches() {
        return Collections.unmodifiableCollection(this.cacheMap.values());
    }

}
