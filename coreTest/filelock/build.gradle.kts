@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget as KotlinJvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val helperSources = file("src/nonAndroidTest/native")

kotlin {
    jvmToolchain(17)
    jvm {
        compilerOptions.jvmTarget.set(KotlinJvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate {
        common {
            group("nonAndroid") {
                withJvm()
                group("native") {
                    withNative()
                }
            }
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("test").cinterops.create("lockTestSpawn") {
            definitionFile.set(helperSources.resolve("spawn.def"))
            includeDirs(helperSources.resolve("include"))
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("nonAndroidTest") {
            dependencies {
                implementation(project(":core"))
            }
        }
        jvmTest {
            dependencies {
                runtimeOnly(project(":platform:platform-${hostTarget.name.lowercase()}"))
            }
        }
    }
}

fun registerLockHelper(suffix: String, directory: String, options: List<String> = emptyList()): TaskProvider<Exec> {
    val output = layout.buildDirectory.dir("lock-helper/$directory").get().asFile
    val configure = tasks.register<Exec>("configureLockHelper$suffix") {
        inputs.dir(helperSources)
        outputs.file(output.resolve("CMakeCache.txt"))
        commandLine(cmakeCommandLine(listOf("-S", helperSources.absolutePath,
            "-B", output.absolutePath, "-DCMAKE_BUILD_TYPE=Release") + options))
    }
    return tasks.register<Exec>("buildLockHelper$suffix") {
        dependsOn(configure)
        inputs.dir(helperSources)
        outputs.dir(output.resolve("bin"))
        commandLine(cmakeCommandLine(listOf("--build", output.absolutePath, "--config", "Release")))
    }
}

val hostLockHelper = registerLockHelper("Host", "host")
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    dependsOn(hostLockHelper)
    environment("FILE_LOCK_HELPER", layout.buildDirectory.file(
        "lock-helper/host/bin/lock-helper${if (hostTarget == JvmTarget.WINDOWS) ".exe" else ""}"
    ).get().asFile.absolutePath)
    // Independent filesystem fixtures permit parallel Gradle workers.
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}

if (hostTarget == JvmTarget.MACOS) {
    val simulatorHelper = registerLockHelper("Simulator", "iosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0"
    ))
    val executable = layout.buildDirectory.file("lock-helper/iosSimulatorArm64/bin/lock-helper").get().asFile
    val signHelper = tasks.register<Exec>("signLockHelperSimulator") {
        dependsOn(simulatorHelper)
        commandLine("codesign", "--force", "--sign", "-", executable.absolutePath)
    }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        dependsOn(signHelper)
        environment("FILE_LOCK_HELPER", executable.absolutePath)
        // simctl forwards variables to the simulated process only with this prefix.
        environment("SIMCTL_CHILD_FILE_LOCK_HELPER", executable.absolutePath)
    }
}
