package dev.zhengxiang.cachedemo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    UserService userService;

    /**
     * 查询用户 - 测试二级缓存
     * GET /user/{id}
     */
    @GetMapping("/{id}")
    public User getUser(@PathVariable String id) {
        return userService.getUser(id);
    }

    /**
     * 更新用户 - 测试 @CachePut 缓存更新
     * PUT /user
     */
    @PutMapping
    public User updateUser(@RequestBody User user) {
        return userService.updateUser(user);
    }

    /**
     * 删除用户 - 测试 @CacheEvict 缓存清除
     * DELETE /user/{id}
     */
    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return "用户 " + id + " 删除成功，缓存已清除";
    }

    /**
     * 清除所有用户缓存
     * DELETE /user/cache/all
     */
    @DeleteMapping("/cache/all")
    public String clearAllUserCache() {
        userService.clearAllUserCache();
        return "所有用户缓存已清除";
    }

    /**
     * 测试仅Redis缓存 - 分布式锁
     */
    @GetMapping("/lock/{resource}")
    public String acquireLock(@PathVariable String resource) {
        return userService.acquireLock(resource);
    }

    /**
     * 测试仅本地缓存 - 重计算
     */
    @GetMapping("/calc/{input}")
    public String heavyCalculation(@PathVariable String input) {
        return userService.heavyCalculation(input);
    }

    /**
     * 测试仅Redis缓存 - 会话信息
     */
    @GetMapping("/session/{sessionId}")
    public String getSessionInfo(@PathVariable String sessionId) {
        return userService.getSessionInfo(sessionId);
    }
}
