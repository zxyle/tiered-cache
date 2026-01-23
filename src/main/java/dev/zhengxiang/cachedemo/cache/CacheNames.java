package dev.zhengxiang.cachedemo.cache;

/**
 * 缓存名称常量类
 * 统一管理所有缓存名称，避免硬编码
 */
public final class CacheNames {

    // 二级缓存（本地 + Redis）
    public static final String USER_INFO = "user_info";
    public static final String SYS_CONFIG = "sys_config";
    public static final String SHORT_LIVED = "short_lived";

    // 仅Redis缓存
    public static final String DISTRIBUTED_LOCK = "distributed_lock";
    public static final String SESSION_CACHE = "session_cache";

    // 仅本地缓存
    public static final String TEMP_CALC = "temp_calc";

    // 私有构造函数，防止实例化
    private CacheNames() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}