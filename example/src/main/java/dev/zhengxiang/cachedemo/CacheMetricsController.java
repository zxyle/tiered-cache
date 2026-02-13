package dev.zhengxiang.cachedemo;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import dev.zhengxiang.tieredcache.TieredCache;
import dev.zhengxiang.tieredcache.TieredCacheManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/cache/metrics")
@RequiredArgsConstructor
public class CacheMetricsController {

    private final TieredCacheManager tieredCacheManager;

    @GetMapping
    public Map<String, CacheMetrics> getAllCacheMetrics() {
        Map<String, CacheMetrics> metricsMap = new HashMap<>();
        Collection<TieredCache> caches = tieredCacheManager.getAllTieredCaches();
        for (TieredCache cache : caches) {
            metricsMap.put(cache.getName(), buildMetrics(cache));
        }
        return metricsMap;
    }

    @GetMapping("/{cacheName}")
    public CacheMetrics getCacheMetrics(@PathVariable String cacheName) {
        TieredCache cache = tieredCacheManager.getTieredCache(cacheName);
        if (cache == null) {
            throw new IllegalArgumentException("Cache does not exist: " + cacheName);
        }
        return buildMetrics(cache);
    }

    @GetMapping("/summary")
    public CacheSummary getCacheSummary() {
        Collection<TieredCache> caches = tieredCacheManager.getAllTieredCaches();
        CacheSummary summary = new CacheSummary();
        summary.setCacheCount(caches.size());
        long totalHits = 0, totalMisses = 0, totalRequests = 0;
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

    @Data
    public static class CacheMetrics {
        private String cacheName;
        private long hitCount;
        private long missCount;
        private long requestCount;
        private double hitRate;
        private long evictionCount;
        private long loadSuccessCount;
        private long loadFailureCount;
        private long totalLoadTime;
        private double averageLoadPenalty;
    }

    @Data
    public static class CacheSummary {
        private int cacheCount;
        private long totalHitCount;
        private long totalMissCount;
        private long totalRequestCount;
        private double overallHitRate;
    }
}
