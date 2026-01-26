package dev.zhengxiang.cachedemo.cache;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/**
 * 缓存同步消息
 */
@Slf4j
@Data
@NoArgsConstructor
public class CacheMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息类型
     */
    public enum Type {
        /**
         * 清除指定 key
         */
        EVICT,

        /**
         * 清空整个缓存
         */
        CLEAR
    }

    /**
     * 消息来源实例ID（用于识别消息来源，避免自己处理自己发的消息）
     */
    private String instanceId;

    /**
     * 消息类型
     */
    private Type type;

    /**
     * 缓存名称
     */
    private String cacheName;

    /**
     * 缓存键（EVICT 类型时使用）
     */
    private Object key;

    /**
     * 当前实例 ID（静态变量，每个 JVM 实例唯一）
     * 格式：hostname:pid，便于排查问题
     */
    private static final String CURRENT_INSTANCE_ID = generateNodeId();

    /**
     * 生成节点唯一标识
     * 格式：hostname:pid，例如 "web-server-01:12345"
     */
    private static String generateNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String nodeId = host + ":" + pid;
            log.info("生成节点ID: {}", nodeId);
            return nodeId;
        } catch (Exception e) {
            // 降级使用短 UUID
            String fallbackId = UUID.randomUUID().toString().substring(0, 8);
            log.warn("无法获取主机名，使用降级ID: {}", fallbackId, e);
            return fallbackId;
        }
    }

    /**
     * 获取当前实例ID
     */
    public static String getCurrentInstanceId() {
        return CURRENT_INSTANCE_ID;
    }

    /**
     * 创建 EVICT 消息
     */
    public static CacheMessage evict(String cacheName, Object key) {
        CacheMessage message = new CacheMessage();
        message.setInstanceId(CURRENT_INSTANCE_ID);
        message.setType(Type.EVICT);
        message.setCacheName(cacheName);
        message.setKey(key);
        return message;
    }

    /**
     * 创建 CLEAR 消息
     */
    public static CacheMessage clear(String cacheName) {
        CacheMessage message = new CacheMessage();
        message.setInstanceId(CURRENT_INSTANCE_ID);
        message.setType(Type.CLEAR);
        message.setCacheName(cacheName);
        return message;
    }

    /**
     * 判断是否是当前实例发送的消息
     */
    public boolean isFromCurrentInstance() {
        return CURRENT_INSTANCE_ID.equals(this.instanceId);
    }
}
