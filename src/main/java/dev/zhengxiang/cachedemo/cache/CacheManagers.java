package dev.zhengxiang.cachedemo.cache;

/**
 * 缓存管理器名称常量
 */
public final class CacheManagers {

    /**
     * 默认二级缓存管理器（不需要显式指定）
     */
    public static final String DEFAULT = "tiered";

    /**
     * 仅Redis缓存管理器
     */
    public static final String REDIS = "redis";

    /**
     * 仅本地缓存管理器
     */
    public static final String LOCAL = "local";

    /**
     * 私有构造函数，防止实例化
     */
    private CacheManagers() {
    }

}