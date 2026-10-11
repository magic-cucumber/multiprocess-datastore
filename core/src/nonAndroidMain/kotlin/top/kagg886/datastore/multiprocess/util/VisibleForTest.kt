package top.kagg886.datastore.multiprocess.util

/** Internal implementation exposed only for tests; not a supported public API. */
@RequiresOptIn(
    message = "This API exposes internal implementation for tests and may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class VisibleForTest
