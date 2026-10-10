#include <jni.h>

// Bootstrap only; cross-process notification support is implemented separately.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}
