# multiprocess-datastore

[中文文档](./README_CN.md)

## Introduction

**multiprocess-datastore** extends AndroidX DataStore with multi-process coordination for Kotlin Multiplatform applications on JVM desktop, Android, and iOS. It combines file locking, a shared version counter, and file change notifications so cooperating processes can update the same store and observe changes through `Flow`.

Android delegates to AndroidX's multi-process implementation. JVM desktop and iOS use this project's `OkioInterProcessCoordinator`.

## Modules & Versions

| Module | Maven Central | Description |
| --- | --- | --- |
| `core` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/core) | Shared factory API and multi-process coordination |
| `platform-windows` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/platform-windows) | Windows JVM native library (`datastore.dll`) |
| `platform-linux` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/platform-linux) | Linux JVM native library (`libdatastore.so`) |
| `platform-macos` | ![Maven Central](https://img.shields.io/maven-central/v/top.kagg886.datastore.multiprocess/platform-macos) | macOS JVM native library (`libdatastore.dylib`) |

## Supported Platforms

| Platform | Target | Requirements |
| --- | --- | --- |
| Windows / Linux / macOS | `jvm` | Java 17+ and the matching native platform module |
| Android | `android` | API 23+; no additional platform module |
| iOS device | `iosArm64` | Native implementation included in `core` |
| iOS simulator | `iosSimulatorArm64` | Apple Silicon simulator; no additional platform module |

## Installation

### 1. Core Module

Add Google Maven and Maven Central to your repositories and `core` to `commonMain`:

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

### 2. JVM Desktop Native Library

Add the platform module for the OS you deploy to. For example, for macOS:

```kotlin
kotlin {
    sourceSets {
        jvmMain.dependencies {
            runtimeOnly("top.kagg886.datastore.multiprocess:platform-macos:$datastoreVersion")
            // Windows: platform-windows
            // Linux: platform-linux
        }
    }
}
```

The native library is extracted from the platform JAR and loaded automatically. Android and iOS only need `core`.

To build from source, publish `core` and the matching platform module to Maven Local, then add `mavenLocal()` to the consuming project's repositories:

```shell
# macOS example; use platform-windows or platform-linux on the matching host.
bash ./gradlew :core:publishToMavenLocal :platform:platform-macos:publishToMavenLocal
```

## Quick Start

Define an `OkioSerializer<T>` for your immutable data, then create a store. This example stores a single integer and can live in `commonMain`:

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

Create one `CounterStore` per file per process, using the same absolute path and serializer in every process. Call `increment()` from a coroutine to perform a coordinated read-modify-write. Collect `dataStore.data` to observe values, including updates made by other cooperating processes. Call `close()` when the owner stops using the store.

On Android, choose an app-private path. On iOS, processes such as an app and its extension need access to the same App Group container.

## Integration with PreferenceDataStore

We generally use `PreferenceDataStoreFactory` rather than implement a DataStore ourselves, so we can use the following function to create a multi-process PreferenceDataStore:

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

Use the `.preferences_pb` file extension, such as `settings.preferences_pb`. Create and close the scope as shown in the previous example, then use Preferences APIs such as `edit`, typed keys, and `data`.

For more usage examples, see the [AndroidX DataStore documentation](https://developer.android.com/topic/libraries/architecture/datastore).
