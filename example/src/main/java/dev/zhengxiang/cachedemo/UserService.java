package dev.zhengxiang.cachedemo;

import dev.zhengxiang.tieredcache.CacheManagers;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    @Cacheable(cacheNames = CacheNames.USER_INFO, key = "'user_' + #id")
    public User getUser(String id) {
        System.out.println("查询数据库 - 用户ID: " + id + " (二级缓存)");
        return userMapper.selectById(id);
    }

    public List<User> getAllUsers() {
        System.out.println("查询所有用户 - 无缓存");
        return userMapper.selectList(null);
    }

    @Transactional
    public User createUser(User user) {
        System.out.println("创建用户 - 用户名: " + user.getName());
        userMapper.insert(user);
        return user;
    }

    @CachePut(cacheNames = CacheNames.USER_INFO, key = "'user_' + #user.id")
    @Transactional
    public User updateUser(User user) {
        System.out.println("更新数据库 - 用户ID: " + user.getId() + ", 用户名: " + user.getName() + " (更新缓存)");
        userMapper.updateById(user);
        return user;
    }

    @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "'user_' + #id")
    @Transactional
    public void deleteUser(String id) {
        System.out.println("删除数据库 - 用户ID: " + id + " (清除缓存)");
        userMapper.deleteById(id);
    }

    @CacheEvict(cacheNames = CacheNames.USER_INFO, allEntries = true)
    public void clearAllUserCache() {
        System.out.println("清除所有用户缓存");
    }

    @Cacheable(cacheNames = CacheNames.DISTRIBUTED_LOCK, key = "'lock_' + #resource", cacheManager = CacheManagers.REDIS)
    public String acquireLock(String resource) {
        String lockId = "lock_" + System.currentTimeMillis();
        System.out.println("获取分布式锁 - 资源: " + resource + " (仅Redis缓存)");
        return lockId;
    }

    @Cacheable(cacheNames = CacheNames.TEMP_CALC, key = "'calc_' + #input", cacheManager = CacheManagers.LOCAL)
    public String heavyCalculation(String input) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = "calculated_" + input.hashCode();
        System.out.println("执行重计算 - 输入: " + input + " (仅本地缓存)");
        return result;
    }

    @Cacheable(cacheNames = CacheNames.SESSION_CACHE, key = "'session_' + #sessionId", cacheManager = CacheManagers.REDIS)
    public String getSessionInfo(String sessionId) {
        System.out.println("查询会话信息 - SessionID: " + sessionId + " (仅Redis缓存)");
        return "session_data_" + sessionId;
    }
}
