import org.gradle.api.Project

fun Project.version(): String {
    val key = "LIB_${name.replace("-", "_").uppercase()}_VERSION"
    val value = providers.environmentVariable(key)
        .orElse(providers.gradleProperty(key)).get()
    require(value.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?"))) {
        "$key must be a version prefixed with v, got: $value"
    }
    return value.removePrefix("v").also { version = it }
}
