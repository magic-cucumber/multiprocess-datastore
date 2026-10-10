import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.Platform
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

fun Project.publishing(platform: Platform) {
    val skipSign = providers.environmentVariable("SKIP_SIGN")
        .orElse(providers.gradleProperty("SKIP_SIGN")).orElse("true").get().toBooleanStrict()
    extensions.configure<MavenPublishBaseExtension> {
        configure(platform)
        publishToMavenCentral(true)
        if (!skipSign) signAllPublications()
        coordinates(project.group.toString(), project.name, project.version.toString())
        pom {
            name.set(rootProject.name)
            description.set("Preference DataStore with cross-process notifications for JVM desktop, Android, and iOS.")
            inceptionYear.set("2026")
            url.set("https://github.com/magic-cucumber/multiprocess-datastore")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("kagg886")
                    name.set("kagg886")
                    url.set("https://github.com/magic-cucumber/")
                }
            }
            scm {
                url.set("https://github.com/magic-cucumber/multiprocess-datastore")
                connection.set("scm:git:git://github.com/magic-cucumber/multiprocess-datastore.git")
                developerConnection.set("scm:git:ssh://git@github.com/magic-cucumber/multiprocess-datastore.git")
            }
        }
    }
}
