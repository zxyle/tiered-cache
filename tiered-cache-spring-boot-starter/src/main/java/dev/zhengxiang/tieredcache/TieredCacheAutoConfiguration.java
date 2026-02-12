package dev.zhengxiang.tieredcache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.zhengxiang.tieredcache.TieredCacheProperties.CacheStrategy;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.spring.cache.CacheConfig;
import org.redisson.spring.cache.RedissonSpringCacheManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 二级缓存自动配置
 */
@EnableCaching
@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(prefix = "cache.tiered", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TieredCacheProperties.class)
public class TieredCacheAutoConfiguration {

    private static JsonJacksonCodec createSafeCodec() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new JsonJacksonCodec(mapper);
    }

    @Bean(CacheManagers.DEFAULT)
    @Primary
    public TieredCacheManager tieredCacheManager(RedissonClient redissonClient,
                                                  CacheMessagePublisher messagePublisher,
                                                  TieredCacheProperties properties) {
        JsonJacksonCodec codec = createSafeCodec();
        RedissonSpringCacheManager redisCacheManager = createRedisCacheManager(redissonClient, properties, codec);
        List<String> predefinedNames = properties.getCacheNames();
        return new TieredCacheManager(
                redisCacheManager,
                messagePublisher,
                redissonClient,
                properties,
                predefinedNames.isEmpty() ? null : predefinedNames,
                codec
        );
    }

    @Bean
    public CacheMessagePublisher cacheMessagePublisher(RedissonClient redissonClient) {
        return new CacheMessagePublisher(redissonClient);
    }

    @Bean
    public CacheMessageListener cacheMessageListener(RedissonClient redissonClient,
                                                      TieredCacheManager tieredCacheManager) {
        return new CacheMessageListener(redissonClient, tieredCacheManager);
    }

    @Bean(CacheManagers.REDIS)
    public CacheManager redisOnlyCacheManager(RedissonClient redissonClient,
                                               TieredCacheProperties properties) {
        return createRedisCacheManager(redissonClient, properties, createSafeCodec());
    }

    @Bean(CacheManagers.LOCAL)
    public CacheManager localOnlyCacheManager(TieredCacheProperties properties) {
        CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
        caffeineCacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(properties.getLocal().getMaximumSize())
                .expireAfterWrite(properties.getLocal().getExpireAfterWrite())
                .recordStats());
        return caffeineCacheManager;
    }

    private RedissonSpringCacheManager createRedisCacheManager(RedissonClient redissonClient,
                                                                TieredCacheProperties properties,
                                                                JsonJacksonCodec codec) {
        Map<String, CacheConfig> configMap = new HashMap<>();

        for (Map.Entry<String, CacheStrategy> entry : properties.getCaches().entrySet()) {
            String cacheName = entry.getKey();
            CacheStrategy strategy = entry.getValue();
            Duration ttl = strategy.getRemoteTtl() != null ? strategy.getRemoteTtl() : properties.getRemote().getDefaultTtl();
            long ttlMs = randomizeTtl(ttl.toMillis(), properties.getRemote().getTtlRandomFactor());
            configMap.put(cacheName, new CacheConfig(ttlMs, 0));
        }

        for (String cacheName : properties.getCacheNames()) {
            configMap.computeIfAbsent(cacheName, k -> {
                long ttlMs = randomizeTtl(properties.getRemote().getDefaultTtl().toMillis(),
                        properties.getRemote().getTtlRandomFactor());
                return new CacheConfig(ttlMs, 0);
            });
        }

        DynamicTtlRedissonCacheManager cacheManager = new DynamicTtlRedissonCacheManager(
                redissonClient, configMap, properties);
        cacheManager.setCodec(codec);
        cacheManager.setAllowNullValues(true);
        return cacheManager;
    }

    private static long randomizeTtl(long baseTtlMs, double randomFactor) {
        if (baseTtlMs <= 0 || randomFactor <= 0) {
            return baseTtlMs;
        }
        long offset = (long) (baseTtlMs * randomFactor);
        return baseTtlMs + ThreadLocalRandom.current().nextLong(-offset, offset + 1);
    }
}
