package dev.zhengxiang.tieredcache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.DependsOn;

/**
 * 缓存消息监听器：监听 Redis Pub/Sub，清除本地缓存
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
                log.debug("忽略本实例发送的消息: instanceId={}", message.getInstanceId());
                return;
            }
            log.debug("收到缓存消息: type={}, cache={}, key={}, from={}",
                    message.getType(), message.getCacheName(), message.getKey(), message.getInstanceId());
            handleMessage(message);
        });
        log.info("缓存消息监听器启动: topic={}, instanceId={}",
                CacheMessagePublisher.CACHE_TOPIC, CacheMessage.getCurrentInstanceId());
    }

    private void handleMessage(CacheMessage message) {
        TieredCache cache = tieredCacheManager.getTieredCache(message.getCacheName());
        if (cache == null) {
            log.warn("缓存不存在: {}", message.getCacheName());
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
                    log.warn("未知消息类型: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("处理缓存消息异常: type={}, cache={}, key={}",
                    message.getType(), message.getCacheName(), message.getKey(), e);
        }
    }
}
