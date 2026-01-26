package dev.zhengxiang.cachedemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 二级缓存演示应用启动类
 * <p>
 * 演示基于 Caffeine + Redis 的二级缓存方案，支持：
 * - 二级缓存（L1 本地 + L2 Redis）
 * - 仅 Redis 缓存
 * - 仅本地缓存
 */
@SpringBootApplication
@MapperScan("dev.zhengxiang.cachedemo")
public class CacheDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheDemoApplication.class, args);
    }

}
