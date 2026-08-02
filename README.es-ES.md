# Proyecto Multi-Módulo de Caché en Niveles

Un caché en dos niveles (L1 Caffeine + L2 Redis) proporcionado como un proyecto multi-módulo Maven con un iniciador de Spring Boot y una aplicación de ejemplo.

## Estructura del Módulo

| Módulo                                   | Descripción                                           |
|-----------------------------------------|-------------------------------------------------------|
| **tiered-cache-spring-boot-starter**    | Iniciador de Spring Boot para caché en niveles, reutilizable por otros proyectos |
| **example**                             | Aplicación de ejemplo que depende del Iniciador      |

## Requisitos de Construcción

- **JDK 17+** (21 recomendado, alineado con Spring Boot 3.5.x)
- Maven 3.6+

Construir (ejecutar con JDK 17+):

```bash
mvn clean install
```

## Ejecutar la Aplicación de Ejemplo

```bash
cd example
mvn spring-boot:run
```

Asegúrese de que MySQL y Redis estén disponibles localmente y configure la fuente de datos y la conexión Redis en `example/src/main/resources/application.yml`.

## Usar el Iniciador

Agregue la dependencia en cualquier proyecto Spring Boot 3.x:

```xml

<dependency>
    <groupId>dev.zhengxiang</groupId>
    <artifactId>tiered-cache-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Configure `cache.tiered.*` (por ejemplo, `cache-names`, `caches`, TTL local/remoto). Con `@Cacheable`, se utiliza el caché en niveles por defecto; use `cacheManager = CacheManagers.REDIS` o `CacheManagers.LOCAL` para usar caché solo Redis o solo local.

## Referencia de Configuración

- **cache.tiered.enabled**: Habilitar la auto-configuración del caché en niveles (predeterminado: true)
- **cache.tiered.cache-names**: Nombres de caché predefinidos; si está vacío, solo se admite la creación dinámica
- **cache.tiered.caches**: Estrategia por nombre de caché (remoteTtl, localTtl, fallbackStrategy, clearMode, etc.)

Vea `example/src/main/resources/application.yml` para un ejemplo completo.
