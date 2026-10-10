import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

group = "top.kagg886.datastore.multiprocess"
version()

library {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.6.2")
        }
    }
}

publishing(KotlinMultiplatform())
