#include "datastore/observer.h"

#include <jni.h>
#include <atomic>
#include <codecvt>
#include <locale>
#include <stdexcept>
#include <system_error>

namespace {
struct EnvScope {
    JavaVM* vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    explicit EnvScope(JavaVM* value) : vm(value) {
        const jint result = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            attached = vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        } else if (result != JNI_OK) env = nullptr;
    }
    ~EnvScope() { if (attached) vm->DetachCurrentThread(); }
};

using UtfCodec = std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>;

class ObserverBridge {
public:
    JavaVM* vm = nullptr;
    jobject callback = nullptr;
    std::u16string directory_prefix;
    jclass string_class = nullptr;
    jmethodID handle = nullptr;
    jmethodID terminate = nullptr;
    std::unique_ptr<DirectoryWatcher> watcher;
    std::atomic<bool> stopped {false};
    std::atomic<bool> terminated {false};

    ~ObserverBridge() {
        stopped.store(true);
        if (watcher) watcher->stop();
        if (!vm) return;
        EnvScope scope(vm);
        if (!scope.env) return;
        if (callback) scope.env->DeleteGlobalRef(callback);
        if (string_class) scope.env->DeleteGlobalRef(string_class);
    }

    jobjectArray paths(JNIEnv* env, const std::vector<std::string>& names) {
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(names.size()), string_class, nullptr);
        if (!array) return nullptr;
        for (jsize i = 0; i < static_cast<jsize>(names.size()); ++i) {
            const std::u16string utf16 = directory_prefix + UtfCodec().from_bytes(names[i]);
            jstring path = env->NewString(reinterpret_cast<const jchar*>(utf16.data()), static_cast<jsize>(utf16.size()));
            if (!path) return nullptr;
            env->SetObjectArrayElement(array, i, path);
            env->DeleteLocalRef(path);
            if (env->ExceptionCheck()) return nullptr;
        }
        return array;
    }

    void post(const DirectoryEvents& events) noexcept {
        if (stopped.load() || terminated.load()) return;
        EnvScope scope(vm);
        JNIEnv* env = scope.env;
        if (!env) return;
        bool failed = false;
        if (env->PushLocalFrame(32) != JNI_OK) {
            env->ExceptionClear();
            failed = true;
        } else {
            try {
                std::array<jobjectArray, 5> arrays {};
                for (size_t i = 0; i < arrays.size(); ++i) {
                    arrays[i] = paths(env, events.names[i]);
                    if (!arrays[i] || env->ExceptionCheck()) { failed = true; break; }
                }
                if (!failed) env->CallVoidMethod(callback, handle,
                    arrays[0], arrays[1], arrays[2], arrays[3], arrays[4],
                    events.overflow ? JNI_TRUE : JNI_FALSE);
                failed = failed || env->ExceptionCheck();
            } catch (...) { failed = true; }
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->PopLocalFrame(nullptr);
        }
        if (failed) fail(-1);
    }

    void fail(int error) noexcept {
        if (stopped.load() || terminated.exchange(true)) return;
        EnvScope scope(vm);
        if (!scope.env) return;
        scope.env->CallVoidMethod(callback, terminate, static_cast<jint>(error));
        if (scope.env->ExceptionCheck()) scope.env->ExceptionClear();
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_InternalDictionaryObserver_observe0(
    JNIEnv* env, jobject, jstring path, jobject callback) {
    try {
        auto value = std::make_unique<ObserverBridge>();
        if (env->GetJavaVM(&value->vm) != JNI_OK) return 0;
        value->callback = env->NewGlobalRef(callback);
        jclass string_class = env->FindClass("java/lang/String");
        if (!value->callback || !string_class) return 0;
        value->string_class = static_cast<jclass>(env->NewGlobalRef(string_class));
        env->DeleteLocalRef(string_class);
        if (!value->string_class) return 0;
        jclass callback_class = env->GetObjectClass(callback);
        value->handle = env->GetMethodID(callback_class, "handle",
            "([Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Z)V");
        value->terminate = env->GetMethodID(callback_class, "terminate", "(I)V");
        env->DeleteLocalRef(callback_class);
        if (env->ExceptionCheck() || !value->handle || !value->terminate) return 0;
        const jchar* chars = env->GetStringChars(path, nullptr);
        if (!chars) return 0;
        const jsize length = env->GetStringLength(path);
        std::u16string utf16;
        try {
            utf16.assign(reinterpret_cast<const char16_t*>(chars), length);
        } catch (...) {
            env->ReleaseStringChars(path, chars);
            throw;
        }
        env->ReleaseStringChars(path, chars);
        value->directory_prefix = utf16;
#ifdef _WIN32
        if (!utf16.empty() && utf16.back() != u'\\' && utf16.back() != u'/') value->directory_prefix += u'\\';
#else
        if (!utf16.empty() && utf16.back() != u'/') value->directory_prefix += u'/';
#endif
        ObserverBridge* bridge = value.get();
        value->watcher = make_directory_watcher(UtfCodec().to_bytes(utf16), DirectorySink {
            [bridge](const DirectoryEvents& events) { bridge->post(events); },
            [bridge](int error) { bridge->fail(error); }
        });
        const int error = value->watcher->start();
        if (error != 0) return -static_cast<jlong>(static_cast<unsigned int>(error));
        return reinterpret_cast<jlong>(value.release());
    } catch (const std::bad_alloc&) {
        jclass type = env->FindClass("java/lang/OutOfMemoryError");
        if (type) env->ThrowNew(type, "Unable to allocate directory observer");
    } catch (const std::system_error& error) {
        return -static_cast<jlong>(error.code().value());
    } catch (...) {
        jclass type = env->FindClass("java/io/IOException");
        if (type) env->ThrowNew(type, "Unable to initialize directory observer");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_InternalDictionaryObserver_stopObserve0(
    JNIEnv*, jobject, jlong pointer) {
    delete reinterpret_cast<ObserverBridge*>(pointer);
}
