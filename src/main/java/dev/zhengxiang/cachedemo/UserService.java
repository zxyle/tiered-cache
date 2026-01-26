package dev.zhengxiang.cachedemo;

import dev.zhengxiang.cachedemo.cache.CacheManagers;
import dev.zhengxiang.cachedemo.cache.CacheNames;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户服务类
 * <p>
 * 演示不同缓存策略的使用：
 * - 二级缓存（默认）：getUser, updateUser, deleteUser
 * - 仅 Redis 缓存：acquireLock, getSessionInfo
 * - 仅本地缓存：heavyCalculation
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    /**
     * 用户信息 - 使用二级缓存（默认）
     * 先查本地缓存，再查Redis，最后查数据库
     */
    @Cacheable(cacheNames = CacheNames.USER_INFO, key = "'user_' + #id")
    public User getUser(String id) {
        System.out.println("查询数据库 - 用户ID: " + id + " (二级缓存)");
        return userMapper.selectById(id);
    }

    /**
     * 查询所有用户
     */
    public List<User> getAllUsers() {
        System.out.println("查询所有用户 - 无缓存");
        return userMapper.selectList(null);
    }

    /**
     * 创建用户
     */
    @Transactional
    public User createUser(User user) {
        System.out.println("创建用户 - 用户名: " + user.getName());
        userMapper.insert(user);
        return user;
    }

    /**
     * 更新用户信息 - 使用 @CachePut 更新缓存
     * 每次调用都会执行方法，并将返回值更新到缓存中
     */
    @CachePut(cacheNames = CacheNames.USER_INFO, key = "'user_' + #user.id")
    @Transactional
    public User updateUser(User user) {
        System.out.println("更新数据库 - 用户ID: " + user.getId() + ", 用户名: " + user.getName() + " (更新缓存)");
        userMapper.updateById(user);
        return user;
    }

    /**
     * 删除用户 - 使用 @CacheEvict 清除缓存
     * 删除用户后清除对应的缓存数据
     */
    @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "'user_' + #id")
    @Transactional
    public void deleteUser(String id) {
        System.out.println("删除数据库 - 用户ID: " + id + " (清除缓存)");
        userMapper.deleteById(id);
    }

    /**
     * 批量删除所有用户缓存 - 使用 allEntries = true
     */
    @CacheEvict(cacheNames = CacheNames.USER_INFO, allEntries = true)
    public void clearAllUserCache() {
        System.out.println("清除所有用户缓存");
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
