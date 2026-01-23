package dev.zhengxiang.cachedemo.cache;

/**
 * 缓存锁获取异常
 * 当获取分布式锁失败且降级策略为 THROW 时抛出
 */
public class CacheLockAcquireException extends RuntimeException {

    public CacheLockAcquireException(String message) {
        super(message);
    }

    public CacheLockAcquireException(String message, Throwable cause) {
        super(message, cause);
    }
}
