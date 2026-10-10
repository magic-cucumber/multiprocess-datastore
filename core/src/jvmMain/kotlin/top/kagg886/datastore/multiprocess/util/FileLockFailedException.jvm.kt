package top.kagg886.datastore.multiprocess.util

internal actual fun platform(): String {
    val os = System.getProperty("os.name")

    if (os.startsWith("Mac")) {
        return "Apple $os"
    }
    return os
}
