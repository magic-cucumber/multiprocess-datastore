import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

fun Project.library(
    ios: KotlinNativeTarget.() -> Unit = {},
    block: KotlinMultiplatformExtension.() -> Unit = {},
) {
    extensions.configure<KotlinMultiplatformExtension> {
        explicitApi()
        jvmToolchain(17)
        jvm {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "${project.group}.core"
            compileSdk = 37
            minSdk = 23
            androidResources.enable = true
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            withHostTestBuilder {
                sourceSetTreeName = "test"
            }
            withDeviceTestBuilder {
                sourceSetTreeName = "test"
            }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
        iosArm64(ios)
        iosSimulatorArm64(ios)
        block()
    }
    extensions.configure<DokkaExtension> {
        moduleName.set("${rootProject.name}:${project.name}")
        moduleVersion.set(project.version.toString())
    }
}
