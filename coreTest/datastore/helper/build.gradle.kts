import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "datastore-helper"
            entryPoint = "top.kagg886.datastore.multiprocess.testhelper.main"
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir("../shared")
            dependencies { implementation(project(":core")) }
        }
        jvmMain.dependencies {
            runtimeOnly(project(":platform:platform-${hostTarget.name.lowercase()}"))
        }
    }
}
