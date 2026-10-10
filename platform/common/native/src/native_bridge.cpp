#include "datastore/native_bridge.h"
#include "datastore/logger.h"

#include <atomic>

namespace {
std::atomic<JavaVM*> g_java_vm {nullptr};
jclass g_logger_class = nullptr;
jmethodID g_logger_method = nullptr;
}

JavaVM* native_bridge_get_vm() {
    return g_java_vm.load(std::memory_order_acquire);
}

jclass native_bridge_get_logger_class() {
    return g_logger_class;
}

jmethodID native_bridge_get_logger_method() {
    return g_logger_method;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local_class = env->FindClass(
        "top/kagg886/datastore/multiprocess/util/NativeLoggerBridge"
    );
    // The platform artifact can also be loaded without the Kotlin core artifact.
    if (local_class != nullptr) {
        g_logger_class = static_cast<jclass>(env->NewGlobalRef(local_class));
        env->DeleteLocalRef(local_class);
        if (g_logger_class == nullptr) return JNI_ERR;
        g_logger_method = env->GetStaticMethodID(
            g_logger_class, "onNativeLoggerPostedCallback", "(ILjava/lang/String;Ljava/lang/String;)V"
        );
        if (g_logger_method == nullptr) {
            env->DeleteGlobalRef(g_logger_class);
            g_logger_class = nullptr;
            return JNI_ERR;
        }
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    g_java_vm.store(vm, std::memory_order_release);
    try {
        logger_on_load();
    } catch (...) {
        g_java_vm.store(nullptr, std::memory_order_release);
        env->DeleteGlobalRef(g_logger_class);
        g_logger_class = nullptr;
        g_logger_method = nullptr;
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    logger_on_unload();
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK &&
        g_logger_class != nullptr) {
        env->DeleteGlobalRef(g_logger_class);
    }
    g_logger_class = nullptr;
    g_logger_method = nullptr;
    g_java_vm.store(nullptr, std::memory_order_release);
}
