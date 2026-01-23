package dev.zhengxiang.cachedemo.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存指标监控接口
 * 提供二级缓存的运行时指标信息
 */
@RestController
@RequestMapping("/cache/metrics")
@RequiredArgsConstructor
public class CacheMetricsController {

    private final TieredCacheManager tieredCacheManager;

    /**
     * 获取所有缓存的指标信息
     */
    @GetMapping
    public Map<String, CacheMetrics> getAllCacheMetrics() {
        Map<String, CacheMetrics> metricsMap = new HashMap<>();
        Collection<TieredCache> caches = tieredCacheManager.getAllTieredCaches();

        for (TieredCache cache : caches) {
            metricsMap.put(cache.getName(), buildMetrics(cache));
        }

        return metricsMap;
    }

    /**
     * 获取指定缓存的指标信息
     */
    @GetMapping("/{cacheName}")
    public CacheMetrics getCacheMetrics(@PathVariable String cacheName) {
        TieredCache cache = tieredCacheManager.getTieredCache(cacheName);
        if (cache == null) {
            throw new IllegalArgumentException("缓存不存在: " + cacheName);
        }
        return buildMetrics(cache);
    }

    /**
     * 获取汇总指标
     */
    @GetMapping("/summary")
    public CacheSummary getCacheSummary() {
        Collection<TieredCache> caches = tieredCacheManager.getAllTieredCaches();
        CacheSummary summary = new CacheSummary();
        summary.setCacheCount(caches.size());

        long totalHits = 0;
        long totalMisses = 0;
        long totalRequests = 0;

        for (TieredCache cache : caches) {
            CacheStats stats = cache.getLocalCacheStats();
            totalHits += stats.hitCount();
            totalMisses += stats.missCount();
            totalRequests += stats.requestCount();
        }

        summary.setTotalHitCount(totalHits);
        summary.setTotalMissCount(totalMisses);
        summary.setTotalRequestCount(totalRequests);
        summary.setOverallHitRate(totalRequests > 0 ? (double) totalHits / totalRequests : 0.0);

        return summary;
    }

    private CacheMetrics buildMetrics(TieredCache cache) {
        CacheStats stats = cache.getLocalCacheStats();
        CacheMetrics metrics = new CacheMetrics();

        metrics.setCacheName(cache.getName());
        metrics.setHitCount(stats.hitCount());
        metrics.setMissCount(stats.missCount());
        metrics.setRequestCount(stats.requestCount());
        metrics.setHitRate(stats.hitRate());
        metrics.setEvictionCount(stats.evictionCount());
        metrics.setLoadSuccessCount(stats.loadSuccessCount());
        metrics.setLoadFailureCount(stats.loadFailureCount());
        metrics.setTotalLoadTime(stats.totalLoadTime());
        metrics.setAverageLoadPenalty(stats.averageLoadPenalty());

        return metrics;
    }

    /**
     * 单个缓存的指标
     */
    @Data
    public static class CacheMetrics {
        /** 缓存名称 */
        private String cacheName;
        /** 命中次数 */
        private long hitCount;
        /** 未命中次数 */
        private long missCount;
        /** 总请求次数 */
        private long requestCount;
        /** 命中率 (0.0 ~ 1.0) */
        private double hitRate;
        /** 驱逐次数 */
        private long evictionCount;
        /** 加载成功次数 */
        private long loadSuccessCount;
        /** 加载失败次数 */
        private long loadFailureCount;
        /** 总加载时间（纳秒） */
        private long totalLoadTime;
        /** 平均加载耗时（纳秒） */
        private double averageLoadPenalty;
    }

    /**
     * 缓存汇总指标
     */
    @Data
    public static class CacheSummary {
        /** 缓存数量 */
        private int cacheCount;
        /** 总命中次数 */
        private long totalHitCount;
        /** 总未命中次数 */
        private long totalMissCount;
        /** 总请求次数 */
        private long totalRequestCount;
        /** 总体命中率 */
        private double overallHitRate;
    }
}
