# vulscan-kit

Общая библиотека системы VulScan: контракты (DTO и JSON-схемы), которые используют Core Backend и модули обнаружения уязвимостей.

## Модули

| Модуль | Артефакт | Содержимое |
|---|---|---|
| `contracts` | `io.vulscan:contracts` | DTO Kafka-сообщений и internal API, JSON-схемы, примеры, `Json`, `Fingerprint`, `ScanJobPayloadValidator` |

Модули `net` (SafeHttpClient, IpPolicy, Redactor) и `worker` (рантайм воркера) добавляются на этапах S-3 и S-4.

## Требования

JDK 21. Версии зафиксированы в `gradle/libs.versions.toml`.

## Сборка

```
./gradlew build
```

## Публикация и подключение

Публикация идёт в GitHub Packages автоматически при push тега `v*`:

```
git tag v0.1.0 && git push origin v0.1.0
```

Подключение из другого проекта (нужен персональный токен GitHub с правом `read:packages`, он прописывается в `~/.gradle/gradle.properties`: `gpr.user=<логин>` и `gpr.key=<токен>`):

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/tolegenjob/vulscan-kit")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("io.vulscan:contracts:0.1.0")
}
```
