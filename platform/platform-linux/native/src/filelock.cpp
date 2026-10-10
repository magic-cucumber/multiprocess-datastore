#include <jni.h>
#include <sys/file.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <codecvt>
#include <locale>
#include <string>
#include <thread>

using namespace std;
using namespace std::chrono;

namespace {
class FileLock {
public:
    int file;

    FileLock(JNIEnv* env, jstring path) {
        const jchar* text = env->GetStringChars(path, nullptr);
        wstring_convert<codecvt_utf8_utf16<char16_t>, char16_t> converter;
        const auto name = converter.to_bytes(
            reinterpret_cast<const char16_t*>(text),
            reinterpret_cast<const char16_t*>(text) + env->GetStringLength(path));
        env->ReleaseStringChars(path, text);
        file = open(name.c_str(), O_RDWR | O_CLOEXEC);
    }

    ~FileLock() { close(file); }

    bool acquire(bool shared) {
        return flock(file, (shared ? LOCK_SH : LOCK_EX) | LOCK_NB) == 0;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_create0(
    JNIEnv* env, jclass, jstring path, jboolean shared, jlong waitMills) {
    if (waitMills <= 0) {
        const auto exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) {
            env->ThrowNew(exception, "waitMills must be greater than 0");
        }
        return -1;
    }

    auto* lock = new FileLock(env, path);
    const auto started = steady_clock::now();
    const auto timeout = milliseconds(waitMills);
    auto interval = milliseconds(1);
    const auto maxInterval = milliseconds(64);
    bool acquired = false;
    while (true) {
        auto elapsed = duration_cast<milliseconds>(steady_clock::now() - started);
        if (elapsed >= timeout) break;
        if (lock->acquire(shared)) {
            acquired = true;
            break;
        }
        elapsed = duration_cast<milliseconds>(steady_clock::now() - started);
        if (elapsed >= timeout) break;
        this_thread::sleep_for(min(interval, timeout - elapsed));
        interval = min(interval * 2, maxInterval);
    }
    // Make one final nonblocking attempt after the timeout.
    if (!acquired) acquired = lock->acquire(shared);
    if (acquired) return reinterpret_cast<jlong>(lock);
    delete lock;
    return -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_tryCreate0(
    JNIEnv* env, jclass, jstring path, jboolean shared) {
    auto* lock = new FileLock(env, path);
    if (lock->acquire(shared)) return reinterpret_cast<jlong>(lock);
    delete lock;
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_release0(
    JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<FileLock*>(ptr);
}
