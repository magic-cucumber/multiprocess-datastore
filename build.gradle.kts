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
