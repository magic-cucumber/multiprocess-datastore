# multiprocess-datastore

支持跨进程通知的 Preference DataStore，面向 JVM Desktop、Android 和 iOS。

当前已初始化构建、原生模块和发布流程；跨进程通知功能尚未实现。

| 模块 | 用途 | 原生库 |
| --- | --- | --- |
| `core` | Kotlin Multiplatform 核心（JVM、Android、iOS） | — |
| `platform:platform-windows` | Windows JVM 原生库 | `datastore.dll` |
| `platform:platform-linux` | Linux JVM 原生库 | `libdatastore.so` |
| `platform:platform-macos` | macOS JVM 原生库 | `libdatastore.dylib` |

构建约定由 `buildSrc` 管理；依赖版本位于 `gradle/libs.versions.toml`。
原生编译和资源打包在各平台模块的 `build.gradle.kts` 中配置，产物随平台 JAR 发布。

## 本地验证

需要 JDK 17、Android SDK（compileSdk 37）、CMake 和对应平台的 C++ 工具链。
Windows 使用 MSVC；iOS 编译和测试需要 macOS/Xcode。

```shell
# JVM 核心测试
./gradlew :core:jvmTest

# 原生模块必须在对应系统上构建；以下以 Windows 为例
./gradlew :platform:platform-windows:test :platform:platform-windows:jar

# Android
./gradlew :core:testAndroidHostTest
./gradlew :core:connectedAndroidDeviceTest

# iOS（macOS）
./gradlew :core:iosSimulatorArm64Test
```

Windows PowerShell 使用 `./gradlew.bat`。本地 Android SDK 路径可通过 `local.properties` 的 `sdk.dir` 设置。

## 发布

Maven group：`top.kagg886.datastore.multiprocess`。
各模块版本在 `gradle.properties` 的 `LIB_*_VERSION` 中配置，使用 `v1.0.0` 格式。
与 wvbridge 一致，环境变量可覆盖版本和 `SKIP_SIGN`。

```shell
./gradlew :core:publishToMavenLocal
./gradlew :platform:platform-windows:publishToMavenLocal
```

GitHub 的 Test 工作流覆盖 Windows/Linux/macOS JVM、原生库加载、Android host/device 和 iOS simulator。
手动运行 Publish 工作流可选择全部或单个模块；测试成功后才发布到 Maven Central。
core 在 macOS 发布所有 KMP 目标，原生平台模块各自在对应系统发布。

仓库需设置与 wvbridge 同名的 Secrets：

- `MAVENCENTRALUSERNAME`
- `MAVENCENTRALPASSWORD`
- `SIGNING_SECRETKEYRING`（ASCII armored GPG 私钥）
- `SIGNING_PASSWORD`

本地默认 `SKIP_SIGN=true`；CI 发布强制 `SKIP_SIGN=false`。
本地发布到 Maven Central 时也必须提供凭据和签名密钥并设置 `SKIP_SIGN=false`。

已配置 Dokka，可运行 `./gradlew dokkaGenerate`；暂未配置文档站点或部署。

作者：[kagg886](https://github.com/magic-cucumber/)  
仓库：[magic-cucumber/multiprocess-datastore](https://github.com/magic-cucumber/multiprocess-datastore)
