enum class JvmTarget {
    MACOS,
    WINDOWS,
    LINUX
}

val hostTarget by lazy {
    val osName = System.getProperty("os.name")
    when {
        osName.startsWith("Mac") -> JvmTarget.MACOS
        osName.startsWith("Win") -> JvmTarget.WINDOWS
        osName.startsWith("Linux") -> JvmTarget.LINUX
        else -> error("Unsupported OS: $osName")
    }
}

fun cmakeCommandLine(arguments: List<String>): List<String> = when (hostTarget) {
    JvmTarget.MACOS -> listOf("zsh", "-c", "exec cmake \"\$@\"", "cmake") + arguments
    JvmTarget.LINUX -> listOf("bash", "-c", "exec cmake \"\$@\"", "cmake") + arguments
    JvmTarget.WINDOWS -> listOf("cmake") + arguments
}
