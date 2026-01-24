package dev.zhengxiang.cachedemo.cache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 缓存消息监听器
 * 监听 Redis Pub/Sub 消息，清除本地缓存
 */
@Slf4j
@Component
@DependsOn(CacheManagers.DEFAULT)  // 确保缓存管理器先初始化
@RequiredArgsConstructor
public class CacheMessageListener {

    private final RedissonClient redissonClient;
    private final TieredCacheManager tieredCacheManager;

    /**
     * 初始化时订阅缓存失效 Topic
     * <p>
     * 监听其他实例发送的缓存失效消息，同步清除本地缓存
     */
    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CacheMessagePublisher.CACHE_TOPIC);

        topic.addListener(CacheMessage.class, (channel, message) -> {
            // 忽略自己发送的消息（当前实例已经更新了本地缓存）
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

    /**
     * 处理缓存消息
     * <p>
     * 根据消息类型执行相应操作：
     * - EVICT：清除指定 key 的本地缓存
     * - CLEAR：清空整个本地缓存
     *
     * @param message 缓存消息
     */
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
