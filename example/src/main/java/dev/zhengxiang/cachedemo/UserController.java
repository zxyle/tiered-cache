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
        return "User " + id + " deleted successfully, cache cleared";
    }

    @DeleteMapping("/cache/all")
    public String clearAllUserCache() {
        userService.clearAllUserCache();
        return "All user cache cleared";
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
