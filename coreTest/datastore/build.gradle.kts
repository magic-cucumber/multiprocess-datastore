@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import java.time.Duration
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins { id("org.jetbrains.kotlin.multiplatform") }

val helperJvmRuntime by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
    }
}
dependencies { helperJvmRuntime(project(":coreTest:datastore:helper")) }

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate {
        common {
            group("nonAndroid") {
                withJvm()
                group("native") { withNative() }
            }
        }
    }
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("test").cinterops.create("datastoreTestSpawn") {
            definitionFile.set(file("src/nativeInterop/spawn.def"))
            includeDirs(file("src/nativeInterop/include"))
        }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("nonAndroidTest") {
            kotlin.srcDir("shared")
            dependencies { implementation(project(":core")) }
        }
        jvmTest.dependencies {
            runtimeOnly(project(":platform:platform-${hostTarget.name.lowercase()}"))
        }
    }
}

val java17 = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}
tasks.withType<Test>().configureEach {
    dependsOn(helperJvmRuntime)
    inputs.files(helperJvmRuntime)
    doFirst {
        environment("DATASTORE_HELPER_JAVA", java17.get().executablePath.asFile.absolutePath)
        environment("DATASTORE_HELPER_CLASSPATH", helperJvmRuntime.asPath)
    }
    timeout.set(Duration.ofMinutes(5))
}

if (hostTarget == JvmTarget.MACOS) {
    val executable = project(":coreTest:datastore:helper").layout.buildDirectory.file(
        "bin/iosSimulatorArm64/debugExecutable/datastore-helper.kexe"
    )
    val signHelper = tasks.register<Exec>("signDatastoreHelperSimulator") {
        dependsOn(":coreTest:datastore:helper:linkDebugExecutableIosSimulatorArm64")
        commandLine("codesign", "--force", "--sign", "-", executable.get().asFile.absolutePath)
    }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        dependsOn(signHelper)
        environment("DATASTORE_HELPER", executable.get().asFile.absolutePath)
        environment("SIMCTL_CHILD_DATASTORE_HELPER", executable.get().asFile.absolutePath)
        timeout.set(Duration.ofMinutes(5))
    }
}
