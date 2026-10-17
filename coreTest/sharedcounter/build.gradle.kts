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
        compilations.getByName("test").cinterops.create("counterTestSpawn") {
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

fun registerCounterHelper(suffix: String, directory: String, options: List<String> = emptyList()): TaskProvider<Exec> {
    val output = layout.buildDirectory.dir("counter-helper/$directory").get().asFile
    val configure = tasks.register<Exec>("configureCounterHelper$suffix") {
        inputs.dir(helperSources)
        outputs.file(output.resolve("CMakeCache.txt"))
        commandLine(cmakeCommandLine(listOf("-S", helperSources.absolutePath,
            "-B", output.absolutePath, "-DCMAKE_BUILD_TYPE=Release") + options))
    }
    return tasks.register<Exec>("buildCounterHelper$suffix") {
        dependsOn(configure)
        inputs.dir(helperSources)
        outputs.dir(output.resolve("bin"))
        commandLine(cmakeCommandLine(listOf("--build", output.absolutePath, "--config", "Release")))
    }
}

val hostCounterHelper = registerCounterHelper("Host", "host")
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    dependsOn(hostCounterHelper)
    environment("SHARED_COUNTER_HELPER", layout.buildDirectory.file(
        "counter-helper/host/bin/counter-helper${if (hostTarget == JvmTarget.WINDOWS) ".exe" else ""}"
    ).get().asFile.absolutePath)
    // Independent filesystem fixtures permit parallel Gradle workers.
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}

if (hostTarget == JvmTarget.MACOS) {
    val simulatorHelper = registerCounterHelper("Simulator", "iosSimulatorArm64", listOf(
        "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_SYSROOT=iphonesimulator",
        "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0"
    ))
    val executable = layout.buildDirectory.file("counter-helper/iosSimulatorArm64/bin/counter-helper").get().asFile
    val signHelper = tasks.register<Exec>("signCounterHelperSimulator") {
        dependsOn(simulatorHelper)
        commandLine("codesign", "--force", "--sign", "-", executable.absolutePath)
    }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        dependsOn(signHelper)
        environment("SHARED_COUNTER_HELPER", executable.absolutePath)
        // simctl forwards variables to the simulated process only with this prefix.
        environment("SIMCTL_CHILD_SHARED_COUNTER_HELPER", executable.absolutePath)
    }
}
