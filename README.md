# 二级缓存多模块项目

基于 Caffeine + Redis 的二级缓存，以多模块形式提供 Starter 与示例应用。

## 模块结构

| 模块                                   | 说明                                |
|--------------------------------------|-----------------------------------|
| **tiered-cache-spring-boot-starter** | 二级缓存 Spring Boot Starter，可被其他项目依赖 |
| **example**                          | 示例应用，依赖 Starter，演示用法              |

## 构建要求

- **JDK 17+**（推荐 21，与 Spring Boot 3.5.x 一致）
- Maven 3.6+

构建命令（需在 JDK 17+ 下执行）：

```bash
mvn clean install
```

## 运行示例应用

```bash
cd example
mvn spring-boot:run
```

需本地提供 MySQL、Redis，并在 `example/src/main/resources/application.yml` 中配置数据源与 Redis 连接。

## 使用 Starter

在其它 Spring Boot 3.x 项目中引入：

```xml

<dependency>
    <groupId>dev.zhengxiang</groupId>
    <artifactId>tiered-cache-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

配置 `cache.tiered.*`（如 `cache-names`、`caches`、本地/远程 TTL 等），使用 `@Cacheable` 时默认使用二级缓存；指定
`cacheManager = CacheManagers.REDIS` 或 `CacheManagers.LOCAL` 可选用仅 Redis 或仅本地缓存。

## 配置说明

- **cache.tiered.enabled**：是否启用二级缓存自动配置（默认 true）
- **cache.tiered.cache-names**：预定义缓存名称列表，为空则仅支持动态创建
- **cache.tiered.caches**：按 cacheName 配置策略（remoteTtl、localTtl、fallbackStrategy、clearMode 等）

详见 `example/src/main/resources/application.yml` 中的示例。
