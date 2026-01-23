package dev.zhengxiang.cachedemo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserControler {

    @Autowired
    UserService userService;

    /**
     * 测试二级缓存 - 用户信息
     */
    @GetMapping("/user/{id}")
    public User getUser(@PathVariable String id) {
        return userService.getUser(id);
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
