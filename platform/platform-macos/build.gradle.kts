import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.vanniktech.maven.publish")
}

group = "top.kagg886.datastore.multiprocess"
version()

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.file("platform/common/src/test/kotlin"))
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

val isHostPlatform = System.getProperty("os.name").startsWith("Mac")

tasks.named<Test>("test") {
    val supportedHost = isHostPlatform
    onlyIf { supportedHost }
}

tasks.register("jvmTest") {
    group = "verification"
    description = "Runs JVM tests on the matching host platform."
    if (isHostPlatform) {
        dependsOn(tasks.named("test"))
    }
}

val java17Launcher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

val processBuild = tasks.register<Exec>("processBuild") {
    onlyIf {
        System.getProperty("os.name").startsWith("Mac")
    }
    workingDir = project.file("native")
    environment("JAVA_HOME", java17Launcher.get().metadata.installationPath.asFile.absolutePath)
    commandLine(
        "zsh", "-c",
        """
            cmake -S . -B build -DCMAKE_BUILD_TYPE=Release &&
            cmake --build build --config Release
        """.trimIndent()
    )
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(processBuild)
    from(project.file("native/build/lib/libdatastore.dylib"))
}

publishing(KotlinJvm())
