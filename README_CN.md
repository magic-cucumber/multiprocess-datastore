# multiprocess-datastore

[English](./README.md)

## 简介

**multiprocess-datastore** 为 AndroidX DataStore 提供 Kotlin Multiplatform 多进程协调能力，支持 JVM 桌面、Android 和 iOS。通过文件锁、共享版本计数器和文件变更通知，让协作进程能够更新同一份存储，并通过 `Flow` 观察变化。

Android 委托给 AndroidX 的多进程实现；JVM 桌面和 iOS 使用本项目的 `OkioInterProcessCoordinator`。

## 模块与版本

| 模块 | Maven Central | 说明 |
| --- | --- | --- |
| `core` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/core) | 公共工厂 API 与多进程协调实现 |
| `platform-windows` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/platform-windows) | Windows JVM 原生库（`datastore.dll`） |
| `platform-linux` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/platform-linux) | Linux JVM 原生库（`libdatastore.so`） |
| `platform-macos` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/platform-macos) | macOS JVM 原生库（`libdatastore.dylib`） |

## 支持的平台

| 平台 | 目标 | 要求 |
| --- | --- | --- |
| Windows / Linux / macOS | `jvm` | Java 17+，并引入对应原生平台模块 |
| Android | `android` | API 23+，无需额外平台模块 |
| iOS 真机 | `iosArm64` | 原生实现随 `core` 提供 |
| iOS 模拟器 | `iosSimulatorArm64` | Apple Silicon 模拟器，无需额外平台模块 |

## 安装引入

### 1. 核心模块

在仓库配置中加入 Google Maven 和 Maven Central，并在 `commonMain` 中引入 `core`：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
val datastoreVersion = "1.0.0"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("top.kagg886.datastore.multiprocess:core:$datastoreVersion")
        }
    }
}
```

### 2. JVM 桌面原生库

按实际部署的操作系统引入平台模块。以 macOS 为例：

```kotlin
kotlin {
    sourceSets {
        jvmMain.dependencies {
            runtimeOnly("top.kagg886.datastore.multiprocess:platform-macos:$datastoreVersion")
            // Windows：platform-windows
            // Linux：platform-linux
        }
    }
}
```

原生库会从平台 JAR 中自动解压并加载。Android 和 iOS 仅需引入 `core`。

如需从源码构建，可将 `core` 和对应平台模块发布到 Maven Local，再在使用方的仓库配置中加入 `mavenLocal()`：

```shell
# macOS 示例；Windows / Linux 请在对应系统使用相应的平台模块。
bash ./gradlew :core:publishToMavenLocal :platform:platform-macos:publishToMavenLocal
```

## 快速上手

为不可变数据实现 `OkioSerializer<T>`，然后创建存储。以下示例保存一个整数，可放在 `commonMain` 中：

```kotlin
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import okio.BufferedSink
import okio.BufferedSource
import okio.Path
import top.kagg886.datastore.multiprocess.MultiProcessDataStoreFactory

object CounterSerializer : OkioSerializer<Int> {
    override val defaultValue: Int = 0

    override suspend fun readFrom(source: BufferedSource): Int =
        source.readUtf8().toIntOrNull()
            ?: throw CorruptionException("Invalid counter value")

    override suspend fun writeTo(t: Int, sink: BufferedSink) {
        sink.writeUtf8(t.toString())
    }
}

class CounterStore(path: Path) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    val dataStore: DataStore<Int> = MultiProcessDataStoreFactory.create(
        serializer = CounterSerializer,
        scope = scope,
        produceFile = { path },
    )

    suspend fun increment(): Int = dataStore.updateData { it + 1 }

    suspend fun current(): Int = dataStore.data.first()

    suspend fun close() {
        job.cancelAndJoin()
    }
}
```

每个进程对同一文件只创建一个 `CounterStore`，所有进程使用相同的绝对路径和序列化方式。在协程中调用 `increment()` 完成受协调的读取、修改和写入；收集 `dataStore.data` 可观察数值变化，包括其他协作进程提交的更新。所属组件不再使用存储时，调用 `close()`。

Android 应选择应用私有目录；iOS 应用与扩展等不同进程需要访问同一个 App Group 共享容器。

## 和 PreferenceDataStore 集成

我们一般会使用 `PreferenceDataStoreFactory` 而非自己手写 DataStore，因此我们可以使用如下的函数来创建一个跨进程的 PreferenceDataStore：

```kotlin
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import top.kagg886.datastore.multiprocess.OkioInterProcessCoordinator

fun createPreferencesStore(
    path: Path,
    scope: CoroutineScope,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    storage = OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        coordinatorProducer = { file, fileSystem ->
            OkioInterProcessCoordinator(file, fileSystem, scope)
        },
        producePath = { path },
    ),
    scope = scope,
)

suspend fun incrementCounter(store: DataStore<Preferences>): Int {
    val key = intPreferencesKey("counter")
    store.edit { preferences ->
        preferences[key] = (preferences[key] ?: 0) + 1
    }
    return store.data.first()[key] ?: 0
}
```

数据文件使用 `.preferences_pb` 后缀，例如 `settings.preferences_pb`。作用域的创建和关闭方式与上例相同，之后即可使用 `edit`、类型化键和 `data` 等 Preferences API。

更多用法请参考 [AndroidX DataStore 官方文档](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn)。
