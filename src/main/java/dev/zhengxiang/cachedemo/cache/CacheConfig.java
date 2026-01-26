package dev.zhengxiang.cachedemo.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.CacheStrategy;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.spring.cache.RedissonSpringCacheManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多级缓存配置类
 * 支持不同业务场景选择不同的缓存策略
 */
@EnableCaching
@Configuration
@EnableConfigurationProperties(TieredCacheProperties.class)
public class CacheConfig {

    /**
     * 创建支持 Java 8 时间类型的 Codec
     * 解决 LocalDateTime 等类型序列化问题
     */
    private JsonJacksonCodec createSafeCodec() {
        ObjectMapper mapper = new ObjectMapper();
        // 注册 Java 8 日期时间模块
        mapper.registerModule(new JavaTimeModule());
        // 允许序列化时写入类型信息，解决反序列化类型丢失问题
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new JsonJacksonCodec(mapper);
    }

    /**
     * 二级缓存管理器 - 默认缓存策略
     * 适用场景：需要高性能且数据相对稳定的业务
     * <p>
     * 查询顺序：L1(Caffeine) -> L2(Redis) -> 数据源
     * 写入时：同时更新L1和L2，并通过Redis Pub/Sub通知其他实例清除L1
     * <p>
     * 特性：
     * 1. 缓存穿透：缓存空值占位符
     * 2. 缓存击穿：分布式锁保护热点key（看门狗自动续期）
     * 3. 缓存雪崩：Redis TTL 添加随机偏移
     * 4. 可配置降级策略和clear模式
     */
    @Bean(CacheManagers.DEFAULT)
    @Primary
    public TieredCacheManager tieredCacheManager(RedissonClient redissonClient,
                                                  CacheMessagePublisher messagePublisher,
                                                  TieredCacheProperties properties) {
        // 创建支持 Java 8 时间类型的 Codec
        JsonJacksonCodec codec = createSafeCodec();

        // L2缓存：Redis分布式缓存（带随机过期时间防雪崩）
        RedissonSpringCacheManager redisCacheManager = createRedisCacheManager(redissonClient, properties, codec);

        // 预定义的缓存名称（可选）
        List<String> predefinedCacheNames = List.of(
                CacheNames.USER_INFO,
                CacheNames.SYS_CONFIG,
                CacheNames.SHORT_LIVED
        );

        return new TieredCacheManager(
                redisCacheManager,
                messagePublisher,
                redissonClient,
                properties,
                predefinedCacheNames,
                codec
        );
    }

    /**
     * 仅Redis缓存管理器
     * 适用场景：数据需要跨实例共享但不需要本地缓存的业务
     * 使用方式：@Cacheable(cacheNames = "cache_name", cacheManager = CacheManagers.REDIS)
     */
    @Bean(CacheManagers.REDIS)
    public CacheManager redisOnlyCacheManager(RedissonClient redissonClient,
                                               TieredCacheProperties properties) {
        return createRedisCacheManager(redissonClient, properties, createSafeCodec());
    }

    /**
     * 仅本地缓存管理器
     * 适用场景：单实例应用或不需要跨实例共享的高频访问数据
     * 使用方式：@Cacheable(cacheNames = "cache_name", cacheManager = CacheManagers.LOCAL)
     */
    @Bean(CacheManagers.LOCAL)
    public CacheManager localOnlyCacheManager(TieredCacheProperties properties) {
        CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
        caffeineCacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(properties.getLocal().getMaximumSize())
                .expireAfterWrite(properties.getLocal().getExpireAfterWrite())
                .recordStats());
        return caffeineCacheManager;
    }

    /**
     * 创建Redis缓存管理器
     * 根据配置为每个缓存设置不同的 TTL，并添加随机偏移防雪崩
     *
     * @param redissonClient Redisson 客户端
     * @param properties     缓存配置属性
     * @param codec          序列化编解码器
     */
    private RedissonSpringCacheManager createRedisCacheManager(RedissonClient redissonClient,
                                                                TieredCacheProperties properties,
                                                                JsonJacksonCodec codec) {
        Map<String, org.redisson.spring.cache.CacheConfig> configMap = new HashMap<>();

        // 为配置的缓存设置 TTL
        for (Map.Entry<String, CacheStrategy> entry : properties.getCaches().entrySet()) {
            String cacheName = entry.getKey();
            CacheStrategy strategy = entry.getValue();
            Duration ttl = strategy.getRemoteTtl() != null ? strategy.getRemoteTtl() : properties.getRemote().getDefaultTtl();

            // 添加随机偏移防雪崩
            long ttlMs = randomizeTtl(ttl.toMillis(), properties.getRemote().getTtlRandomFactor());
            configMap.put(cacheName, new org.redisson.spring.cache.CacheConfig(ttlMs, 0));
        }

        // 为预定义的缓存名称设置默认 TTL（如果未在 caches 中配置）
        setDefaultTtlIfAbsent(configMap, CacheNames.USER_INFO, properties, Duration.ofHours(24));
        setDefaultTtlIfAbsent(configMap, CacheNames.SYS_CONFIG, properties, Duration.ofHours(1));
        setDefaultTtlIfAbsent(configMap, CacheNames.SHORT_LIVED, properties, Duration.ofMinutes(5));
        setDefaultTtlIfAbsent(configMap, CacheNames.DISTRIBUTED_LOCK, properties, Duration.ofMinutes(30));
        setDefaultTtlIfAbsent(configMap, CacheNames.SESSION_CACHE, properties, Duration.ofHours(2));

        // 创建支持动态 TTL 的缓存管理器
        DynamicTtlRedissonCacheManager cacheManager = new DynamicTtlRedissonCacheManager(
                redissonClient, configMap, properties);
        // 使用支持 Java 8 时间类型的 Codec
        cacheManager.setCodec(codec);
        // 允许存储null值占位符，用于解决缓存穿透
        cacheManager.setAllowNullValues(true);

        return cacheManager;
    }

    /**
     * 如果缓存未配置 TTL，则设置默认值
     */
    private void setDefaultTtlIfAbsent(Map<String, org.redisson.spring.cache.CacheConfig> configMap,
                                        String cacheName,
                                        TieredCacheProperties properties,
                                        Duration defaultTtl) {
        configMap.computeIfAbsent(cacheName, k -> {
            long ttlMs = randomizeTtl(defaultTtl.toMillis(), properties.getRemote().getTtlRandomFactor());
            return new org.redisson.spring.cache.CacheConfig(ttlMs, 0);
        });
    }

    /**
     * 为 TTL 添加随机偏移，防止缓存雪崩
     *
     * @param baseTtlMs    基础 TTL（毫秒）
     * @param randomFactor 随机因子（0.1 表示 ±10%）
     * @return 带随机偏移的 TTL
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
