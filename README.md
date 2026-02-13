# Tiered Cache Multi-Module Project

A two-tier cache (L1 Caffeine + L2 Redis) provided as a multi-module Maven project with a Spring Boot Starter and an example application.

## Module Structure

| Module                                   | Description                                           |
|-----------------------------------------|-------------------------------------------------------|
| **tiered-cache-spring-boot-starter**    | Tiered cache Spring Boot Starter, reusable by other projects |
| **example**                             | Example application depending on the Starter          |

## Build Requirements

- **JDK 17+** (21 recommended, aligned with Spring Boot 3.5.x)
- Maven 3.6+

Build (run with JDK 17+):

```bash
mvn clean install
```

## Running the Example Application

```bash
cd example
mvn spring-boot:run
```

Ensure MySQL and Redis are available locally and configure the datasource and Redis connection in `example/src/main/resources/application.yml`.

## Using the Starter

Add the dependency in any Spring Boot 3.x project:

```xml

<dependency>
    <groupId>dev.zhengxiang</groupId>
    <artifactId>tiered-cache-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Configure `cache.tiered.*` (e.g. `cache-names`, `caches`, local/remote TTL). With `@Cacheable`, the tiered cache is used by default; use `cacheManager = CacheManagers.REDIS` or `CacheManagers.LOCAL` to use Redis-only or local-only cache.

## Configuration Reference

- **cache.tiered.enabled**: Enable tiered cache auto-configuration (default: true)
- **cache.tiered.cache-names**: Predefined cache names; if empty, only dynamic creation is supported
- **cache.tiered.caches**: Per-cacheName strategy (remoteTtl, localTtl, fallbackStrategy, clearMode, etc.)

See `example/src/main/resources/application.yml` for a full example.
