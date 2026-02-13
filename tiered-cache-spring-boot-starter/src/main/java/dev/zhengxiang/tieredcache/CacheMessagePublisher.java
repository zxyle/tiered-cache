package dev.zhengxiang.tieredcache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

/**
 * Cache message publisher: uses Redis Pub/Sub to notify other instances to invalidate local cache.
 */
@Slf4j
@RequiredArgsConstructor
public class CacheMessagePublisher {

    public static final String CACHE_TOPIC = "cache:invalidate";

    private final RedissonClient redissonClient;

    public void publishEvict(String cacheName, Object key) {
        try {
            CacheMessage message = CacheMessage.evict(cacheName, key);
            RTopic topic = redissonClient.getTopic(CACHE_TOPIC);
            topic.publishAsync(message)
                    .whenComplete((count, e) -> {
                        if (e != null) {
                            log.warn("Failed to publish cache evict message: cache={}, key={}", cacheName, key, e);
                        } else {
                            log.debug("Published cache evict message: cache={}, key={}, subscribers={}", cacheName, key, count);
                        }
                    });
        } catch (Exception e) {
            log.warn("Error publishing cache evict message: cache={}, key={}", cacheName, key, e);
        }
    }

    public void publishClear(String cacheName) {
        try {
            CacheMessage message = CacheMessage.clear(cacheName);
            RTopic topic = redissonClient.getTopic(CACHE_TOPIC);
            topic.publishAsync(message)
                    .whenComplete((count, e) -> {
                        if (e != null) {
                            log.warn("Failed to publish cache clear message: cache={}", cacheName, e);
                        } else {
                            log.debug("Published cache clear message: cache={}, subscribers={}", cacheName, count);
                        }
                    });
        } catch (Exception e) {
            log.warn("Error publishing cache clear message: cache={}", cacheName, e);
        }
    }
}
