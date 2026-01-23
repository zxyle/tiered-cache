package dev.zhengxiang.cachedemo;

import dev.zhengxiang.cachedemo.cache.CacheManagers;
import dev.zhengxiang.cachedemo.cache.CacheNames;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    /**
     * 用户信息 - 使用二级缓存（默认）
     * 先查本地缓存，再查Redis，最后查数据库
     */
    @Cacheable(cacheNames = CacheNames.USER_INFO, key = "'user_' + #id")
    public User getUser(String id) {
        User user = new User();
        user.setName("zhengxiang");
        System.out.println("查询数据库 - 用户ID: " + id + " (二级缓存)");
        return user;
    }

    /**
     * 分布式锁信息 - 仅使用Redis缓存
     * 跳过本地缓存，直接使用Redis，适合需要跨实例共享的数据
     */
    @Cacheable(cacheNames = CacheNames.DISTRIBUTED_LOCK, key = "'lock_' + #resource", cacheManager = CacheManagers.REDIS)
    public String acquireLock(String resource) {
        String lockId = "lock_" + System.currentTimeMillis();
        System.out.println("获取分布式锁 - 资源: " + resource + " (仅Redis缓存)");
        return lockId;
    }

    /**
     * 临时计算结果 - 仅使用本地缓存
     * 不需要跨实例共享，追求极致性能
     */
    @Cacheable(cacheNames = CacheNames.TEMP_CALC, key = "'calc_' + #input", cacheManager = CacheManagers.LOCAL)
    public String heavyCalculation(String input) {
        // 模拟重计算
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = "calculated_" + input.hashCode();
        System.out.println("执行重计算 - 输入: " + input + " (仅本地缓存)");
        return result;
    }

    /**
     * 会话信息 - 仅使用Redis缓存
     * 需要跨实例共享但不需要本地缓存
     */
    @Cacheable(cacheNames = CacheNames.SESSION_CACHE, key = "'session_' + #sessionId", cacheManager = CacheManagers.REDIS)
    public String getSessionInfo(String sessionId) {
        System.out.println("查询会话信息 - SessionID: " + sessionId + " (仅Redis缓存)");
        return "session_data_" + sessionId;
    }
}
