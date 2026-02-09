package dev.zhengxiang.cachedemo;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    @GetMapping("/{id}")
    public User getUser(@PathVariable String id) {
        return userService.getUser(id);
    }

    @PostMapping
    public User createUser(@RequestBody User user) {
        return userService.createUser(user);
    }

    @PutMapping
    public User updateUser(@RequestBody User user) {
        return userService.updateUser(user);
    }

    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return "用户 " + id + " 删除成功，缓存已清除";
    }

    @DeleteMapping("/cache/all")
    public String clearAllUserCache() {
        userService.clearAllUserCache();
        return "所有用户缓存已清除";
    }

    @GetMapping("/lock/{resource}")
    public String acquireLock(@PathVariable String resource) {
        return userService.acquireLock(resource);
    }

    @GetMapping("/calc/{input}")
    public String heavyCalculation(@PathVariable String input) {
        return userService.heavyCalculation(input);
    }

    @GetMapping("/session/{sessionId}")
    public String getSessionInfo(@PathVariable String sessionId) {
        return userService.getSessionInfo(sessionId);
    }
}
