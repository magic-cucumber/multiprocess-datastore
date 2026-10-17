import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.jvm") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("com.vanniktech.maven.publish") apply false
    id("org.jetbrains.dokka")
}

dokka {
    moduleName.set(rootProject.name)
}

dependencies {
    dokka(project(":core"))
}

val nativeTestLogLevel = gradle.startParameter.logLevel.ordinal

subprojects {
    tasks.withType<Test>().configureEach {
        inputs.property("nativeTestLogLevel", nativeTestLogLevel)
        environment("DATASTORE_TEST_LOG_LEVEL", nativeTestLogLevel.toString())
        testLogging {
            showStandardStreams = true
        }
    }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        inputs.property("nativeTestLogLevel", nativeTestLogLevel)
        environment("DATASTORE_TEST_LOG_LEVEL", nativeTestLogLevel.toString())
        environment("SIMCTL_CHILD_DATASTORE_TEST_LOG_LEVEL", nativeTestLogLevel.toString())
    }
}
