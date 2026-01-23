package dev.zhengxiang.cachedemo.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 缓存消息发布器
 * 使用 Redis Pub/Sub 通知其他实例清除本地缓存
 * 采用异步发布，避免阻塞业务线程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheMessagePublisher {

    public static final String CACHE_TOPIC = "cache:invalidate";

    private final RedissonClient redissonClient;

    /**
     * 发布缓存失效消息（异步）
     *
     * @param cacheName 缓存名称
     * @param key       缓存键
     */
    public void publishEvict(String cacheName, Object key) {
        try {
            CacheMessage message = CacheMessage.evict(cacheName, key);
            RTopic topic = redissonClient.getTopic(CACHE_TOPIC);

            // 异步发布，避免阻塞业务线程
            topic.publishAsync(message)
                    .whenComplete((count, e) -> {
                        if (e != null) {
                            log.warn("发布缓存失效消息失败: cache={}, key={}", cacheName, key, e);
                        } else {
                            log.debug("发布缓存失效消息成功: cache={}, key={}, subscribers={}", cacheName, key, count);
                        }
                    });
        } catch (Exception e) {
            log.warn("发布缓存失效消息异常: cache={}, key={}", cacheName, key, e);
        }
    }

    /**
     * 发布清空缓存消息（异步）
     *
     * @param cacheName 缓存名称
     */
    public void publishClear(String cacheName) {
        try {
            CacheMessage message = CacheMessage.clear(cacheName);
            RTopic topic = redissonClient.getTopic(CACHE_TOPIC);

            // 异步发布
            topic.publishAsync(message)
                    .whenComplete((count, e) -> {
                        if (e != null) {
                            log.warn("发布清空缓存消息失败: cache={}", cacheName, e);
                        } else {
                            log.debug("发布清空缓存消息成功: cache={}, subscribers={}", cacheName, count);
                        }
                    });
        } catch (Exception e) {
            log.warn("发布清空缓存消息异常: cache={}", cacheName, e);
        }
    }
}
