package dev.zhengxiang.cachedemo.cache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.CacheStrategy;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.ClearMode;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.FallbackStrategy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.NonNull;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 二级缓存实现
 * <p>
 * 查询顺序：L1(本地Caffeine) -> L2(Redis) -> 数据源
 * 写入顺序：写入L1和L2，并通知其他实例失效L1
 * <p>
 * 特性：
 * 1. 缓存穿透：缓存空值（NullValue占位符）
 * 2. 缓存击穿：分布式锁保护热点key（看门狗自动续期）
 * 3. 缓存雪崩：过期时间添加随机偏移
 * 4. 可配置降级策略：THROW（保护DB）或 FALLBACK（保证可用性）
 * 5. 可配置clear模式：SAFE（仅清L1）或 FULL（清L1+L2）
 */
@Slf4j
public class TieredCache implements org.springframework.cache.Cache {

    /**
     * 空值占位符，用于解决缓存穿透问题
     */
    public static final String NULL_VALUE = "@@CACHE_NULL@@";

    /**
     * 分布式锁前缀
     */
    private static final String LOCK_PREFIX = "lock:";

    private final String name;
    private final Cache<Object, Object> localCache;           // L1: Caffeine
    private final org.springframework.cache.Cache remoteCache; // L2: Redis (Spring Cache 抽象)
    private final CacheMessagePublisher messagePublisher;
    private final RedissonClient redissonClient;
    private final TieredCacheProperties properties;
    private final CacheStrategy strategy;                      // 当前缓存的策略

    public TieredCache(String name,
                       Cache<Object, Object> localCache,
                       org.springframework.cache.Cache remoteCache,
                       CacheMessagePublisher messagePublisher,
                       RedissonClient redissonClient,
                       TieredCacheProperties properties) {
        this.name = name;
        this.localCache = localCache;
        this.remoteCache = remoteCache;
        this.messagePublisher = messagePublisher;
        this.redissonClient = redissonClient;
        this.properties = properties;
        // 根据 cacheName 获取对应策略
        this.strategy = properties.getEffectiveStrategy(name);

        log.info("创建二级缓存: name={}, fallback={}, clearMode={}, localTtl={}, remoteTtl={}",
                name, strategy.getFallbackStrategy(), strategy.getClearMode(),
                strategy.getLocalTtl(), strategy.getRemoteTtl());
    }

    @NonNull
    @Override
    public String getName() {
        return this.name;
    }

    @NonNull
    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(@NonNull Object key) {
        String keyStr = key.toString();

        // 1. 先查本地缓存
        Object value = localCache.getIfPresent(keyStr);
        if (value != null) {
            log.debug("L1 命中: cache={}, key={}", name, keyStr);
            return wrapValue(value);
        }

        // 2. 本地未命中，查 Redis
        ValueWrapper wrapper = remoteCache.get(keyStr);
        if (wrapper != null) {
            log.debug("L2 命中: cache={}, key={}", name, keyStr);
            Object remoteValue = wrapper.get();
            // 回填本地缓存
            if (remoteValue != null) {
                localCache.put(keyStr, remoteValue);
            }
            return wrapper;
        }

        log.debug("缓存未命中: cache={}, key={}", name, keyStr);
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return unwrapNullValue((T) value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        String keyStr = key.toString();

        // 1. 快速路径：检查 L1 缓存
        Object value = localCache.getIfPresent(keyStr);
        if (value != null) {
            log.debug("L1 命中: cache={}, key={}", name, keyStr);
            return unwrapNullValue((T) value);
        }

        // 2. 检查 L2 缓存
        ValueWrapper wrapper = remoteCache.get(keyStr);
        if (wrapper != null) {
            log.debug("L2 命中: cache={}, key={}", name, keyStr);
            Object remoteValue = wrapper.get();
            // 回填本地缓存
            if (remoteValue != null) {
                localCache.put(keyStr, remoteValue);
            }
            return unwrapNullValue((T) remoteValue);
        }

        // 3. 都未命中，使用分布式锁防止缓存击穿
        return loadWithLock(keyStr, valueLoader);
    }

    /**
     * 使用分布式锁加载数据，防止缓存击穿
     * 使用看门狗机制，不指定 leaseTime，Redisson 自动续期
     */
    @SuppressWarnings("unchecked")
    private <T> T loadWithLock(String keyStr, Callable<T> valueLoader) {
        String lockKey = properties.getCachePrefix() + LOCK_PREFIX + name + ":" + keyStr;
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            // 使用看门狗机制：不指定 leaseTime，Redisson 自动续期（默认30秒，每10秒续期）
            acquired = lock.tryLock(properties.getRemote().getLockWaitTimeMs(), TimeUnit.MILLISECONDS);

            if (acquired) {
                try {
                    // Double Check：获取锁后再次检查缓存
                    ValueWrapper wrapper = remoteCache.get(keyStr);
                    if (wrapper != null) {
                        log.debug("获取锁后 L2 命中: cache={}, key={}", name, keyStr);
                        Object remoteValue = wrapper.get();
                        if (remoteValue != null) {
                            localCache.put(keyStr, remoteValue);
                        }
                        return unwrapNullValue((T) remoteValue);
                    }

                    // 真正加载数据
                    log.debug("加载数据: cache={}, key={}", name, keyStr);
                    T result = valueLoader.call();

                    // 缓存结果（包括null值，解决缓存穿透）
                    Object toCache = (result == null) ? NULL_VALUE : result;
                    remoteCache.put(keyStr, toCache);
                    localCache.put(keyStr, toCache);

                    return result;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 获取锁失败，根据策略决定行为
                return handleLockFailure(keyStr, valueLoader);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValueRetrievalException(keyStr, valueLoader, e);
        } catch (CacheLockAcquireException e) {
            throw e;
        } catch (Exception e) {
            throw new ValueRetrievalException(keyStr, valueLoader, e);
        }
    }

    /**
     * 处理获取锁失败的情况
     */
    @SuppressWarnings("unchecked")
    private <T> T handleLockFailure(String keyStr, Callable<T> valueLoader) throws Exception {
        // 最后尝试读一次 L2（可能其他线程已加载完成）
        ValueWrapper wrapper = remoteCache.get(keyStr);
        if (wrapper != null) {
            Object remoteValue = wrapper.get();
            if (remoteValue != null) {
                localCache.put(keyStr, remoteValue);
            }
            return unwrapNullValue((T) remoteValue);
        }

        // 根据策略决定降级行为
        if (strategy.getFallbackStrategy() == FallbackStrategy.THROW) {
            log.warn("获取锁失败，抛出异常: cache={}, key={}", name, keyStr);
            throw new CacheLockAcquireException("当前访问人数过多，请稍后重试");
        } else {
            log.warn("获取锁失败，降级查询数据源: cache={}, key={}", name, keyStr);
            return valueLoader.call();
        }
    }

    /**
     * 解包空值占位符
     */
    @SuppressWarnings("unchecked")
    private <T> T unwrapNullValue(T value) {
        if (NULL_VALUE.equals(value)) {
            return null;
        }
        return value;
    }

    /**
     * 包装返回值
     */
    private ValueWrapper wrapValue(Object value) {
        if (value == null) {
            return null;
        }
        // 如果是空值占位符，返回包含 null 的 wrapper
        if (NULL_VALUE.equals(value)) {
            return new SimpleValueWrapper(null);
        }
        return new SimpleValueWrapper(value);
    }

    @Override
    public void put(@NonNull Object key, Object value) {
        String keyStr = key.toString();
        if (value == null) {
            // 不缓存 null，如需缓存 null 应使用 putWithNullHandle
            return;
        }
        log.debug("写入缓存: cache={}, key={}", name, keyStr);

        // 写入 L2 (Redis)
        remoteCache.put(keyStr, value);
        // 写入 L1 (Caffeine)
        localCache.put(keyStr, value);
        // 通知其他实例清除本地缓存
        publishInvalidateMessage(keyStr);
    }

    @Override
    public ValueWrapper putIfAbsent(@NonNull Object key, Object value) {
        ValueWrapper existingValue = get(key);
        if (existingValue != null) {
            return existingValue;
        }
        put(key, value);
        return null;
    }

    @Override
    public void evict(@NonNull Object key) {
        String keyStr = key.toString();
        log.debug("删除缓存: cache={}, key={}", name, keyStr);

        // 清除本地缓存
        localCache.invalidate(keyStr);
        // 清除 Redis 缓存
        remoteCache.evict(keyStr);
        // 通知其他实例清除本地缓存
        publishInvalidateMessage(keyStr);
    }

    @Override
    public boolean evictIfPresent(@NonNull Object key) {
        String keyStr = key.toString();
        Object localValue = localCache.getIfPresent(keyStr);
        ValueWrapper remoteValue = remoteCache.get(keyStr);

        boolean existed = localValue != null || remoteValue != null;
        if (existed) {
            evict(key);
        }
        return existed;
    }

    @Override
    public void clear() {
        log.debug("清空缓存: cache={}, mode={}", name, strategy.getClearMode());

        // 1. 清空本机 L1
        localCache.invalidateAll();

        // 2. 广播通知所有节点清空 L1
        publishClearMessage();

        // 3. 根据策略决定是否清除 L2
        if (strategy.getClearMode() == ClearMode.FULL) {
            clearRemoteCache();
        }
        // SAFE 模式：L2 依赖 TTL 自然过期
    }

    /**
     * 清除远程缓存（直接删除整个 Hash key）
     */
    private void clearRemoteCache() {
        // Redisson Spring Cache 使用 cacheName 作为 Hash key
        log.info("清除远程缓存: cacheName={}", name);

        // 使用 UNLINK 异步删除整个 Hash key，避免 bigkey 阻塞
        redissonClient.getKeys().unlinkAsync(name)
                .whenComplete((count, e) -> {
                    if (e != null) {
                        log.error("清除远程缓存失败: cacheName={}", name, e);
                    } else {
                        log.info("清除远程缓存完成: cacheName={}, deleted={}", name, count > 0);
                    }
                });
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    /**
     * 清除本地缓存（被其他实例通知时调用）
     */
    public void evictLocal(Object key) {
        String keyStr = key.toString();
        log.debug("收到通知清除本地缓存: cache={}, key={}", name, keyStr);
        localCache.invalidate(keyStr);
    }

    /**
     * 清除整个本地缓存（被其他实例通知时调用）
     */
    public void clearLocal() {
        log.debug("收到通知清空本地缓存: cache={}", name);
        localCache.invalidateAll();
    }

    private void publishInvalidateMessage(Object key) {
        if (messagePublisher != null) {
            messagePublisher.publishEvict(name, key);
        }
    }

    private void publishClearMessage() {
        if (messagePublisher != null) {
            messagePublisher.publishClear(name);
        }
    }

    /**
     * 获取本地缓存统计信息
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats getLocalCacheStats() {
        return localCache.stats();
    }
}
