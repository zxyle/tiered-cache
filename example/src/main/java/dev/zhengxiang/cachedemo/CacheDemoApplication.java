package dev.zhengxiang.cachedemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 二级缓存示例应用启动类
 */
@SpringBootApplication
@MapperScan("dev.zhengxiang.cachedemo")
public class CacheDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheDemoApplication.class, args);
    }
}
