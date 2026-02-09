package dev.zhengxiang.cachedemo;

/**
 * 缓存名称常量（示例项目使用）
 */
public final class CacheNames {

    public static final String USER_INFO = "user_info";
    public static final String SYS_CONFIG = "sys_config";
    public static final String SHORT_LIVED = "short_lived";
    public static final String DISTRIBUTED_LOCK = "distributed_lock";
    public static final String SESSION_CACHE = "session_cache";
    public static final String TEMP_CALC = "temp_calc";

    private CacheNames() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
