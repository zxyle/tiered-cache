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
        System.out.println("Querying database - User ID: " + id + " (tiered cache)");
        return userMapper.selectById(id);
    }

    public List<User> getAllUsers() {
        System.out.println("Querying all users - no cache");
        return userMapper.selectList(null);
    }

    @Transactional
    public User createUser(User user) {
        System.out.println("Creating user - username: " + user.getName());
        userMapper.insert(user);
        return user;
    }

    @CachePut(cacheNames = CacheNames.USER_INFO, key = "'user_' + #user.id")
    @Transactional
    public User updateUser(User user) {
        System.out.println("Updating database - User ID: " + user.getId() + ", username: " + user.getName() + " (updating cache)");
        userMapper.updateById(user);
        return user;
    }

    @CacheEvict(cacheNames = CacheNames.USER_INFO, key = "'user_' + #id")
    @Transactional
    public void deleteUser(String id) {
        System.out.println("Deleting from database - User ID: " + id + " (clearing cache)");
        userMapper.deleteById(id);
    }

    @CacheEvict(cacheNames = CacheNames.USER_INFO, allEntries = true)
    public void clearAllUserCache() {
        System.out.println("Clearing all user cache");
    }

    @Cacheable(cacheNames = CacheNames.DISTRIBUTED_LOCK, key = "'lock_' + #resource", cacheManager = CacheManagers.REDIS)
    public String acquireLock(String resource) {
        String lockId = "lock_" + System.currentTimeMillis();
        System.out.println("Acquiring distributed lock - resource: " + resource + " (Redis-only cache)");
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
        System.out.println("Running heavy calculation - input: " + input + " (local-only cache)");
        return result;
    }

    @Cacheable(cacheNames = CacheNames.SESSION_CACHE, key = "'session_' + #sessionId", cacheManager = CacheManagers.REDIS)
    public String getSessionInfo(String sessionId) {
        System.out.println("Querying session info - SessionID: " + sessionId + " (Redis-only cache)");
        return "session_data_" + sessionId;
    }
}
