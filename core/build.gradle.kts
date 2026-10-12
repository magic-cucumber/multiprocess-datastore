@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

group = "top.kagg886.datastore.multiprocess"
version()

library {
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("sharedCounter") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/sharedcounter.def"))
        }
    }
    applyDefaultHierarchyTemplate {
        common {
            group("nonAndroid") {
                withJvm()
                group("native") {
                    withNative()
                }
            }
            group("android") {
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        }
        commonMain.dependencies {
            api(libs.androidx.datastore.preferences.core)
            api(libs.androidx.datastore.core.okio)
            api(libs.okio)
        }
        getByName("nonAndroidMain").dependencies {
            implementation(libs.androidx.annotation)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.6.2")
        }
    }
}

publishing(KotlinMultiplatform())
