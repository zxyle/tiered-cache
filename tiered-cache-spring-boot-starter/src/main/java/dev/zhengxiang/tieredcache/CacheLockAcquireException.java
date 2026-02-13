package dev.zhengxiang.tieredcache;

/**
 * Thrown when distributed lock acquisition fails and fallback strategy is THROW.
 */
public class CacheLockAcquireException extends RuntimeException {

    public CacheLockAcquireException(String message) {
        super(message);
    }

    public CacheLockAcquireException(String message, Throwable cause) {
        super(message, cause);
    }
}
