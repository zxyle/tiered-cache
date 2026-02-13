package dev.zhengxiang.tieredcache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.DependsOn;

/**
 * Cache message listener: subscribes to Redis Pub/Sub and invalidates local cache accordingly.
 */
@Slf4j
@DependsOn(CacheManagers.DEFAULT)
@RequiredArgsConstructor
public class CacheMessageListener {

    private final RedissonClient redissonClient;
    private final TieredCacheManager tieredCacheManager;

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CacheMessagePublisher.CACHE_TOPIC);
        topic.addListener(CacheMessage.class, (channel, message) -> {
            if (message.isFromCurrentInstance()) {
                log.debug("Ignoring message from current instance: instanceId={}", message.getInstanceId());
                return;
            }
            log.debug("Received cache message: type={}, cache={}, key={}, from={}",
                    message.getType(), message.getCacheName(), message.getKey(), message.getInstanceId());
            handleMessage(message);
        });
        log.info("Cache message listener started: topic={}, instanceId={}",
                CacheMessagePublisher.CACHE_TOPIC, CacheMessage.getCurrentInstanceId());
    }

    private void handleMessage(CacheMessage message) {
        TieredCache cache = tieredCacheManager.getTieredCache(message.getCacheName());
        if (cache == null) {
            log.warn("Cache does not exist: {}", message.getCacheName());
            return;
        }
        try {
            switch (message.getType()) {
                case EVICT:
                    cache.evictLocal(message.getKey());
                    break;
                case CLEAR:
                    cache.clearLocal();
                    break;
                default:
                    log.warn("Unknown message type: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("Error handling cache message: type={}, cache={}, key={}",
                    message.getType(), message.getCacheName(), message.getKey(), e);
        }
    }
}
