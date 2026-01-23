package dev.zhengxiang.cachedemo.cache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.CacheStrategy;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.ClearMode;
import dev.zhengxiang.cachedemo.cache.TieredCacheProperties.FallbackStrategy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RScript;
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
     * 使用唯一字符串常量，确保序列化/反序列化安全
     */
    private static final String NULL_VALUE = "@@TIERED_CACHE_NULL_VALUE@@";

    /**
     * 分布式锁前缀
     */
    private static final String LOCK_PREFIX = "lock:";

    /**
     * 缓存 Redis 是否支持 UNLINK 命令（版本 >= 4.0）
     * 使用 volatile 确保多线程可见性，所有 TieredCache 实例共享
     */
    private static volatile Boolean supportsUnlink = null;

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

        // 使用 Caffeine 的 get(key, mappingFunction) 方法
        // 内置并发控制：同一个 key 只有一个线程执行加载逻辑，其他线程等待结果
        // 这样可以防止多个线程同时穿透到 L2/数据源
        Object value = localCache.get(keyStr, k -> {
            // 1. 检查 L2 缓存
            ValueWrapper wrapper = remoteCache.get(keyStr);
            if (wrapper != null) {
                log.debug("L2 命中: cache={}, key={}", name, keyStr);
                return wrapper.get();
            }

            // 2. L2 也未命中，使用分布式锁加载数据
            log.debug("L1/L2 都未命中，加载数据: cache={}, key={}", name, keyStr);
            return loadWithLockInternal(keyStr, valueLoader);
        });

        return unwrapNullValue((T) value);
    }

    /**
     * 使用分布式锁加载数据（内部方法，返回原始值供 Caffeine 缓存）
     */
    private <T> Object loadWithLockInternal(String keyStr, Callable<T> valueLoader) {
        String lockKey = properties.getCachePrefix() + LOCK_PREFIX + name + ":" + keyStr;
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            acquired = lock.tryLock(properties.getRemote().getLockWaitTimeMs(), TimeUnit.MILLISECONDS);

            if (acquired) {
                try {
                    // Double Check：获取锁后再次检查 L2 缓存
                    ValueWrapper wrapper = remoteCache.get(keyStr);
                    if (wrapper != null) {
                        log.debug("获取锁后 L2 命中: cache={}, key={}", name, keyStr);
                        return wrapper.get();
                    }

                    // 真正加载数据
                    log.debug("加载数据: cache={}, key={}", name, keyStr);
                    T result = valueLoader.call();

                    // 缓存结果到 L2（包括null值，解决缓存穿透）
                    Object toCache = (result == null) ? NULL_VALUE : result;
                    putToRemoteCache(keyStr, toCache, result == null);

                    return toCache;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 获取锁失败，根据策略决定行为
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

    /**
     * 处理获取锁失败的情况（内部方法）
     * <p>
     * FALLBACK 策略下会查询数据源并写回 L2，避免其他 JVM 实例重复打 DB
     */
    private <T> Object handleLockFailureInternal(String keyStr, Callable<T> valueLoader) throws Exception {
        // 最后尝试读一次 L2（可能其他线程已加载完成）
        ValueWrapper wrapper = remoteCache.get(keyStr);
        if (wrapper != null) {
            return wrapper.get();
        }

        // 根据策略决定降级行为
        if (strategy.getFallbackStrategy() == FallbackStrategy.THROW) {
            log.warn("获取锁失败，抛出异常: cache={}, key={}", name, keyStr);
            throw new CacheLockAcquireException("当前访问人数过多，请稍后重试");
        } else {
            log.warn("获取锁失败，降级查询数据源: cache={}, key={}", name, keyStr);
            T result = valueLoader.call();
            Object toCache = (result == null) ? NULL_VALUE : result;

            // 写回 L2 缓存，避免其他 JVM 实例重复查询数据源
            // 注意：这里没有锁保护，可能会覆盖其他线程的写入，但在 FALLBACK 场景下可接受
            putToRemoteCache(keyStr, toCache, result == null);

            return toCache;
        }
    }

    /**
     * 解包空值占位符
     */
    private <T> T unwrapNullValue(T value) {
        if (NULL_VALUE.equals(value)) {
            return null;
        }
        return value;
    }

    /**
     * 写入远程缓存
     * <p>
     * 统一使用 RMapCache 进行写入，确保存储方式一致：
     * - null 值使用较短的 TTL（nullValueTtl），防止缓存穿透攻击长时间污染缓存
     * - 正常值使用配置的 remoteTtl
     *
     * @param key         缓存键
     * @param value       缓存值
     * @param isNullValue 是否为 null 值占位符
     */
    private void putToRemoteCache(String key, Object value, boolean isNullValue) {
        RMapCache<Object, Object> mapCache = redissonClient.getMapCache(name);

        long ttlMs;
        if (isNullValue) {
            // null 值使用固定的较短 TTL，无需随机偏移
            ttlMs = properties.getRemote().getNullValueTtl().toMillis();
        } else {
            // 正常值使用配置的 remoteTtl，添加随机偏移防雪崩（±10%）
            ttlMs = randomizeTtl(strategy.getRemoteTtl().toMillis());
        }

        mapCache.put(key, value, ttlMs, TimeUnit.MILLISECONDS);
        log.debug("写入 L2: cache={}, key={}, isNull={}, ttl={}ms", name, key, isNullValue, ttlMs);
    }

    /**
     * 为 TTL 添加随机偏移，防止缓存雪崩
     */
    private long randomizeTtl(long baseTtlMs) {
        double randomFactor = properties.getRemote().getTtlRandomFactor();
        if (baseTtlMs <= 0 || randomFactor <= 0) {
            return baseTtlMs;
        }
        long offset = (long) (baseTtlMs * randomFactor);
        return baseTtlMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(-offset, offset + 1);
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
        boolean isNullValue = (value == null);
        Object toCache = isNullValue ? NULL_VALUE : value;

        log.debug("写入缓存: cache={}, key={}, isNull={}", name, keyStr, isNullValue);

        // 1. 写入 L2 (保证数据源先更新)
        putToRemoteCache(keyStr, toCache, isNullValue);
        // 2. 写入 L1 (Caffeine)
        localCache.put(keyStr, toCache);
        // 3. 通知其他实例清除本地缓存（防止其他实例读到旧值）
        publishInvalidateMessage(keyStr);
    }

    /**
     * 如果不存在则写入
     * <p>
     * 使用 Redisson 的原子操作 {@code RMapCache.putIfAbsent()}，无需分布式锁。
     * <p>
     * 注意：Spring Cache 的 putIfAbsent 是"尽力而为"的弱语义，不保证跨 L1/L2 的强一致性。
     * 本实现保证 L2（Redis）层面的原子性，L1 采用"后写入覆盖"策略。
     *
     * @param key   缓存键
     * @param value 缓存值
     * @return 如果 key 已存在，返回已存在的值；如果 key 不存在且写入成功，返回 null
     */
    @Override
    public ValueWrapper putIfAbsent(@NonNull Object key, Object value) {
        String keyStr = key.toString();
        boolean isNullValue = (value == null);
        Object toCache = isNullValue ? NULL_VALUE : value;

        // 使用 Redisson 原子操作写入 L2
        RMapCache<Object, Object> mapCache = redissonClient.getMapCache(name);
        long ttlMs = isNullValue
                ? properties.getRemote().getNullValueTtl().toMillis()
                : randomizeTtl(strategy.getRemoteTtl().toMillis());

        // putIfAbsent 是原子操作，返回已存在的值或 null
        Object existing = mapCache.putIfAbsent(keyStr, toCache, ttlMs, TimeUnit.MILLISECONDS);

        if (existing != null) {
            // key 已存在，返回已存在的值
            log.debug("putIfAbsent L2 已存在: cache={}, key={}", name, keyStr);
            // 回填 L1
            localCache.put(keyStr, existing);
            return wrapValue(existing);
        }

        // key 不存在，写入成功，同步到 L1
        log.debug("putIfAbsent 写入成功: cache={}, key={}, isNull={}", name, keyStr, isNullValue);
        localCache.put(keyStr, toCache);
        // 通知其他实例清除本地缓存
        publishInvalidateMessage(keyStr);

        return null;
    }

    @Override
    public void evict(@NonNull Object key) {
        String keyStr = key.toString();
        log.debug("删除缓存: cache={}, key={}", name, keyStr);

        // 1. 先清除 Redis 缓存（防止其他线程从 L2 读到旧值回填 L1）
        remoteCache.evict(keyStr);
        // 2. 再清除本地缓存
        localCache.invalidate(keyStr);
        // 3. 通知其他实例清除本地缓存
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

        // 1. 根据策略决定是否清除 L2, SAFE 模式：L2 依赖 TTL 自然过期
        if (strategy.getClearMode() == ClearMode.FULL) {
            clearRemoteCache();
        }

        // 2. 清空本机 L1
        localCache.invalidateAll();

        // 3. 广播通知所有节点清空 L1
        publishClearMessage();

    }

    /**
     * 清除远程缓存（根据 Redis 版本选择 UNLINK 或 DEL）
     * <p>
     * Redis 4.0+ 支持 UNLINK 命令，它是异步删除，不会阻塞主线程
     * Redis 4.0 以下只能使用 DEL 命令，同步删除
     */
    private void clearRemoteCache() {
        log.info("清除远程缓存: cacheName={}", name);

        // 首次调用时检测 Redis 版本（双重检查锁，只检测一次）
        if (supportsUnlink == null) {
            synchronized (TieredCache.class) {
                if (supportsUnlink == null) {
                    supportsUnlink = detectUnlinkSupport();
                }
            }
        }

        if (supportsUnlink) {
            // Redis 4.0+ 使用 UNLINK 异步删除，避免 bigkey 阻塞
            redissonClient.getKeys().unlink(name);
            log.info("远程缓存已清理(UNLINK): {}", name);
        } else {
            // Redis 4.0 以下使用 DEL 同步删除
            redissonClient.getKeys().delete(name);
            log.info("远程缓存已清理(DEL): {}", name);
        }
    }

    /**
     * 检测 Redis 是否支持 UNLINK 命令（版本 >= 4.0）
     *
     * @return true 如果支持 UNLINK，false 如果不支持
     */
    private boolean detectUnlinkSupport() {
        try {
            // 通过 Lua 脚本执行 INFO server 命令获取版本信息
            String serverInfo = redissonClient.getScript().eval(
                    RScript.Mode.READ_ONLY,
                    "return redis.call('INFO', 'server')",
                    RScript.ReturnType.VALUE
            );

            // 解析 redis_version:x.x.x
            int majorVersion = parseRedisMajorVersion(serverInfo);
            boolean supports = majorVersion >= 4;

            log.info("Redis 版本检测完成: majorVersion={}, supportsUnlink={}", majorVersion, supports);
            return supports;
        } catch (Exception e) {
            log.warn("检测 Redis 版本失败，默认使用 DEL 命令: {}", e.getMessage());
            // 检测失败时降级使用 DEL（更安全，兼容所有版本）
            return false;
        }
    }

    /**
     * 从 INFO server 输出中解析 Redis 主版本号
     *
     * @param serverInfo INFO server 命令的输出
     * @return Redis 主版本号，解析失败返回 0
     */
    private int parseRedisMajorVersion(String serverInfo) {
        if (serverInfo == null || serverInfo.isEmpty()) {
            return 0;
        }

        // INFO server 输出格式示例：
        // # Server
        // redis_version:7.0.5
        // redis_git_sha1:00000000
        // ...
        for (String line : serverInfo.split("\n")) {
            line = line.trim();
            if (line.startsWith("redis_version:")) {
                String version = line.substring("redis_version:".length()).trim();
                // 解析主版本号（第一个 . 之前的数字）
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
