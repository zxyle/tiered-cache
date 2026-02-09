package dev.zhengxiang.tieredcache;

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
@Data
@Slf4j
@NoArgsConstructor
public class CacheMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Type {
        EVICT,
        CLEAR
    }

    private String instanceId;
    private Type type;
    private String cacheName;
    private Object key;

    private static final String CURRENT_INSTANCE_ID = generateNodeId();

    private static String generateNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            String nodeId = host + ":" + pid;
            log.info("生成节点ID: {}", nodeId);
            return nodeId;
        } catch (Exception e) {
            String fallbackId = UUID.randomUUID().toString().substring(0, 8);
            log.warn("无法获取主机名，使用降级ID: {}", fallbackId, e);
            return fallbackId;
        }
    }

    public static String getCurrentInstanceId() {
        return CURRENT_INSTANCE_ID;
    }

    public static CacheMessage evict(String cacheName, Object key) {
        CacheMessage message = new CacheMessage();
        message.setInstanceId(CURRENT_INSTANCE_ID);
        message.setType(Type.EVICT);
        message.setCacheName(cacheName);
        message.setKey(key);
        return message;
    }

    public static CacheMessage clear(String cacheName) {
        CacheMessage message = new CacheMessage();
        message.setInstanceId(CURRENT_INSTANCE_ID);
        message.setType(Type.CLEAR);
        message.setCacheName(cacheName);
        return message;
    }

    public boolean isFromCurrentInstance() {
        return CURRENT_INSTANCE_ID.equals(this.instanceId);
    }
}
