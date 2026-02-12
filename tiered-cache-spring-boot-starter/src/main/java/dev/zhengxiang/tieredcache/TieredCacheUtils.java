package dev.zhengxiang.tieredcache;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 二级缓存通用工具方法
 */
public final class TieredCacheUtils {

    private TieredCacheUtils() {
    }

    /**
     * 在基准 TTL 上叠加随机偏移，避免大量 key 同时过期造成缓存雪崩。
     *
     * @param baseTtlMs   基准 TTL（毫秒）
     * @param randomFactor 随机因子，取值 [0, 1]，例如 0.1 表示在 baseTtlMs 的 ±10% 范围内随机
     * @return 随机化后的 TTL（毫秒），至少为 1，保证 Redis 写入有效
     */
    public static long randomizeTtl(long baseTtlMs, double randomFactor) {
        if (baseTtlMs <= 0 || randomFactor <= 0) {
            return baseTtlMs;
        }
        long offset = (long) (baseTtlMs * randomFactor);
        long result = baseTtlMs + ThreadLocalRandom.current().nextLong(-offset, offset + 1);
        return Math.max(1, result);
    }
}
