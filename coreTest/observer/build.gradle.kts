@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import java.time.Duration
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
        compilations.getByName("test").cinterops.create("observerTestSpawn") {
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

fun registerObserverHelper(suffix: String, directory: String, options: List<String> = emptyList()): TaskProvider<Exec> {
    val output = layout.buildDirectory.dir("observer-helper/$directory").get().asFile
    val configure = tasks.register<Exec>("configureObserverHelper$suffix") {
        inputs.dir(helperSources)
        outputs.file(output.resolve("CMakeCache.txt"))
        commandLine(cmakeCommandLine(listOf("-S", helperSources.absolutePath,
            "-B", output.absolutePath, "-DCMAKE_BUILD_TYPE=Release") + options))
    }
    return tasks.register<Exec>("buildObserverHelper$suffix") {
        dependsOn(configure)
        inputs.dir(helperSources)
        outputs.dir(output.resolve("bin"))
        commandLine(cmakeCommandLine(listOf("--build", output.absolutePath, "--config", "Release")))
    }
}

val hostObserverHelper = registerObserverHelper("Host", "host")
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    dependsOn(hostObserverHelper)
    environment("FILE_OBSERVER_HELPER", layout.buildDirectory.file(
        "observer-helper/host/bin/observer-helper${if (hostTarget == JvmTarget.WINDOWS) ".exe" else ""}"
    ).get().asFile.absolutePath)
    timeout.set(Duration.ofMinutes(5))
    // Independent filesystem fixtures permit parallel Gradle workers.
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}

if (hostTarget == JvmTarget.MACOS) {
    val simulatorHelper = registerObserverHelper("Simulator", "iosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0"
    ))
    val executable = layout.buildDirectory.file("observer-helper/iosSimulatorArm64/bin/observer-helper").get().asFile
    val signHelper = tasks.register<Exec>("signObserverHelperSimulator") {
        dependsOn(simulatorHelper)
        commandLine("codesign", "--force", "--sign", "-", executable.absolutePath)
    }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        timeout.set(Duration.ofMinutes(5))
        dependsOn(signHelper)
        environment("FILE_OBSERVER_HELPER", executable.absolutePath)
        // simctl forwards variables to the simulated process only with this prefix.
        environment("SIMCTL_CHILD_FILE_OBSERVER_HELPER", executable.absolutePath)
    }
}
