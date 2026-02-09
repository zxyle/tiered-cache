package dev.zhengxiang.tieredcache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.zhengxiang.tieredcache.TieredCacheProperties.CacheStrategy;
import dev.zhengxiang.tieredcache.TieredCacheProperties.ClearMode;
import dev.zhengxiang.tieredcache.TieredCacheProperties.FallbackStrategy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.lang.NonNull;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 二级缓存实现：L1(Caffeine) + L2(Redis)
 */
@Slf4j
public class TieredCache implements org.springframework.cache.Cache {

    private static final String NULL_VALUE = "@@TIERED_CACHE_NULL_VALUE@@";
    private static final String LOCK_PREFIX = "lock:";
    private static volatile Boolean supportsUnlink = null;

    private final String name;
    private final Cache<Object, Object> localCache;
    private final org.springframework.cache.Cache remoteCache;
    private final CacheMessagePublisher messagePublisher;
    private final RedissonClient redissonClient;
    private final TieredCacheProperties properties;
    private final CacheStrategy strategy;
    private final Codec codec;

    public TieredCache(String name,
                       Cache<Object, Object> localCache,
                       org.springframework.cache.Cache remoteCache,
                       CacheMessagePublisher messagePublisher,
                       RedissonClient redissonClient,
                       TieredCacheProperties properties,
                       Codec codec) {
        this.name = name;
        this.localCache = localCache;
        this.remoteCache = remoteCache;
        this.messagePublisher = messagePublisher;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.codec = codec;
        this.strategy = properties.getEffectiveStrategy(name);

        log.info("创建二级缓存: name={}, fallback={}, clearMode={}, localTtl={}, remoteTtl={}, nullValueTtl={}",
                name, strategy.getFallbackStrategy(), strategy.getClearMode(),
                strategy.getLocalTtl(), strategy.getRemoteTtl(),
                properties.getRemote().getNullValueTtl());
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
        Object value = localCache.getIfPresent(keyStr);
        if (value != null) {
            log.debug("L1 命中: cache={}, key={}", name, keyStr);
            return wrapValue(value);
        }
        ValueWrapper wrapper = remoteCache.get(keyStr);
        if (wrapper != null) {
            log.debug("L2 命中: cache={}, key={}", name, keyStr);
            Object remoteValue = wrapper.get();
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
        Object value = localCache.get(keyStr, k -> {
            ValueWrapper wrapper = remoteCache.get(keyStr);
            if (wrapper != null) {
                log.debug("L2 命中: cache={}, key={}", name, keyStr);
                return wrapper.get();
            }
            log.debug("L1/L2 都未命中，加载数据: cache={}, key={}", name, keyStr);
            return loadWithLockInternal(keyStr, valueLoader);
        });
        return unwrapNullValue((T) value);
    }

    private <T> Object loadWithLockInternal(String keyStr, Callable<T> valueLoader) {
        String lockKey = properties.getCachePrefix() + LOCK_PREFIX + name + ":" + keyStr;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(properties.getRemote().getLockWaitTimeMs(), TimeUnit.MILLISECONDS);
            if (acquired) {
                try {
                    ValueWrapper wrapper = remoteCache.get(keyStr);
                    if (wrapper != null) {
                        log.debug("获取锁后 L2 命中: cache={}, key={}", name, keyStr);
                        return wrapper.get();
                    }
                    log.debug("加载数据: cache={}, key={}", name, keyStr);
                    T result = valueLoader.call();
                    Object toCache = (result == null) ? NULL_VALUE : result;
                    putToRemoteCache(keyStr, toCache, result == null);
                    return toCache;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                return handleLockFailureInternal(keyStr, valueLoader);
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

    private <T> Object handleLockFailureInternal(String keyStr, Callable<T> valueLoader) throws Exception {
        ValueWrapper wrapper = remoteCache.get(keyStr);
        if (wrapper != null) {
            return wrapper.get();
        }
        if (strategy.getFallbackStrategy() == FallbackStrategy.THROW) {
            log.warn("获取锁失败，抛出异常: cache={}, key={}", name, keyStr);
            throw new CacheLockAcquireException("当前访问人数过多，请稍后重试");
        } else {
            log.warn("获取锁失败，降级查询数据源: cache={}, key={}", name, keyStr);
            T result = valueLoader.call();
            Object toCache = (result == null) ? NULL_VALUE : result;
            putToRemoteCache(keyStr, toCache, result == null);
            return toCache;
        }
    }

    private <T> T unwrapNullValue(T value) {
        if (NULL_VALUE.equals(value)) {
            return null;
        }
        return value;
    }

    private void putToRemoteCache(String key, Object value, boolean isNullValue) {
        RMapCache<Object, Object> mapCache = redissonClient.getMapCache(name, codec);
        long ttlMs = isNullValue
                ? properties.getRemote().getNullValueTtl().toMillis()
                : randomizeTtl(strategy.getRemoteTtl().toMillis());
        mapCache.put(key, value, ttlMs, TimeUnit.MILLISECONDS);
        log.debug("写入 L2: cache={}, key={}, isNull={}, ttl={}ms", name, key, isNullValue, ttlMs);
    }

    private long randomizeTtl(long baseTtlMs) {
        double randomFactor = properties.getRemote().getTtlRandomFactor();
        if (baseTtlMs <= 0 || randomFactor <= 0) {
            return baseTtlMs;
        }
        long offset = (long) (baseTtlMs * randomFactor);
        return baseTtlMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(-offset, offset + 1);
    }

    private ValueWrapper wrapValue(Object value) {
        if (value == null) {
            return null;
        }
        if (NULL_VALUE.equals(value)) {
            return new SimpleValueWrapper(null);
        }
        return new SimpleValueWrapper(value);
    }

    @Override
    public void put(@NonNull Object key, Object value) {
        String keyStr = key.toString();
        boolean isNullValue = (value == null);
        Object toCache = isNullValue ? NULL_VALUE : value;
        log.debug("写入缓存: cache={}, key={}, isNull={}", name, keyStr, isNullValue);
        putToRemoteCache(keyStr, toCache, isNullValue);
        localCache.put(keyStr, toCache);
        publishInvalidateMessage(keyStr);
    }

    @Override
    public ValueWrapper putIfAbsent(@NonNull Object key, Object value) {
        String keyStr = key.toString();
        boolean isNullValue = (value == null);
        Object toCache = isNullValue ? NULL_VALUE : value;
        RMapCache<Object, Object> mapCache = redissonClient.getMapCache(name, codec);
        long ttlMs = isNullValue
                ? properties.getRemote().getNullValueTtl().toMillis()
                : randomizeTtl(strategy.getRemoteTtl().toMillis());
        Object existing = mapCache.putIfAbsent(keyStr, toCache, ttlMs, TimeUnit.MILLISECONDS);
        if (existing != null) {
            log.debug("putIfAbsent L2 已存在: cache={}, key={}", name, keyStr);
            localCache.put(keyStr, existing);
            return wrapValue(existing);
        }
        log.debug("putIfAbsent 写入成功: cache={}, key={}, isNull={}", name, keyStr, isNullValue);
        localCache.put(keyStr, toCache);
        publishInvalidateMessage(keyStr);
        return null;
    }

    @Override
    public void evict(@NonNull Object key) {
        String keyStr = key.toString();
        log.debug("删除缓存: cache={}, key={}", name, keyStr);
        remoteCache.evict(keyStr);
        localCache.invalidate(keyStr);
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
        if (strategy.getClearMode() == ClearMode.FULL) {
            clearRemoteCache();
        }
        localCache.invalidateAll();
        publishClearMessage();
    }

    private void clearRemoteCache() {
        log.info("清除远程缓存: cacheName={}", name);
        if (supportsUnlink == null) {
            synchronized (TieredCache.class) {
                if (supportsUnlink == null) {
                    supportsUnlink = detectUnlinkSupport();
                }
            }
        }
        if (supportsUnlink) {
            redissonClient.getKeys().unlink(name);
            log.info("远程缓存已清理(UNLINK): {}", name);
        } else {
            redissonClient.getKeys().delete(name);
            log.info("远程缓存已清理(DEL): {}", name);
        }
    }

    private boolean detectUnlinkSupport() {
        try {
            String serverInfo = redissonClient.getScript().eval(
                    RScript.Mode.READ_ONLY,
                    "return redis.call('INFO', 'server')",
                    RScript.ReturnType.VALUE
            );
            int majorVersion = parseRedisMajorVersion(serverInfo);
            boolean supports = majorVersion >= 4;
            log.info("Redis 版本检测完成: majorVersion={}, supportsUnlink={}", majorVersion, supports);
            return supports;
        } catch (Exception e) {
            log.warn("检测 Redis 版本失败，默认使用 DEL 命令: {}", e.getMessage());
            return false;
        }
    }

    private int parseRedisMajorVersion(String serverInfo) {
        if (serverInfo == null || serverInfo.isEmpty()) {
            return 0;
        }
        for (String line : serverInfo.split("\n")) {
            line = line.trim();
            if (line.startsWith("redis_version:")) {
                String version = line.substring("redis_version:".length()).trim();
                int dotIndex = version.indexOf('.');
                String majorStr = dotIndex > 0 ? version.substring(0, dotIndex) : version;
                try {
                    return Integer.parseInt(majorStr);
                } catch (NumberFormatException e) {
                    log.warn("解析 Redis 版本号失败: {}", version);
                    return 0;
                }
            }
        }
        return 0;
    }

    @Override
    public boolean invalidate() {
        clear();
        return true;
    }

    public void evictLocal(Object key) {
        String keyStr = key.toString();
        log.debug("收到通知清除本地缓存: cache={}, key={}", name, keyStr);
        localCache.invalidate(keyStr);
    }

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

    public com.github.benmanes.caffeine.cache.stats.CacheStats getLocalCacheStats() {
        return localCache.stats();
    }
}
