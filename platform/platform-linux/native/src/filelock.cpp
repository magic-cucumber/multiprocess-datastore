#include "datastore/logger.h"
#include <jni.h>
#include <sys/file.h>
#include <cerrno>
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
    bool acquired_ = false;
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
        const int error = file < 0 ? errno : 0;
        if (error) LOGGER_E("lock=%p open path=%s failed errno=%d", static_cast<void*>(this), name.c_str(), error);
        else LOGGER_D("lock=%p opened path=%s fd=%d", static_cast<void*>(this), name.c_str(), file);
        errno = error;
    }

    ~FileLock() {
        if (file < 0) return;
        if (close(file) != 0) {
            const int error = errno;
            LOGGER_E("lock=%p close fd=%d failed errno=%d", static_cast<void*>(this), file, error);
        } else LOGGER_D("lock=%p %s fd=%d closed", static_cast<void*>(this), acquired_ ? "release" : "rollback", file);
    }

    jlong acquire(bool shared) {
        if (flock(file, (shared ? LOCK_SH : LOCK_EX) | LOCK_NB) == 0) {
            acquired_ = true;
            LOGGER_D("lock=%p acquired shared=%d", static_cast<void*>(this), shared);
            return 1;
        }
        const int error = errno;
        if (error == EWOULDBLOCK || error == EAGAIN) {
            LOGGER_V("lock=%p contention shared=%d", static_cast<void*>(this), shared);
            return 0;
        }
        LOGGER_E("lock=%p flock failed errno=%d", static_cast<void*>(this), error);
        return -static_cast<jlong>(error);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_create0(
    JNIEnv* env, jclass, jstring path, jboolean shared, jlong waitMills) {
    if (waitMills <= 0) {
        LOGGER_E("lock invalid timeout_ms=%lld", static_cast<long long>(waitMills));
        const auto exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) {
            env->ThrowNew(exception, "waitMills must be greater than 0");
        }
        return 0;
    }

    auto* lock = new FileLock(env, path);
    if (lock->file < 0) {
        const int error = errno;
        delete lock;
        return -static_cast<jlong>(error);
    }
    const auto started = steady_clock::now();
    const auto timeout = milliseconds(waitMills);
    auto interval = milliseconds(1);
    const auto maxInterval = milliseconds(64);
    bool acquired = false;
    bool finalAttempt = false;
    unsigned attempts = 0;
    while (true) {
        auto elapsed = steady_clock::now() - started;
        if (elapsed >= timeout) break;
        ++attempts;
        const jlong result = lock->acquire(shared);
        if (result > 0) {
            acquired = true;
            break;
        }
        if (result < 0) {
            delete lock;
            return result;
        }
        if (finalAttempt) break;
        elapsed = steady_clock::now() - started;
        if (elapsed >= timeout) break;
        const auto remaining = timeout - elapsed;
        finalAttempt = interval >= remaining;
        // Leave one millisecond for the final attempt before the timeout.
        const auto delay = finalAttempt
            ? max(remaining - milliseconds(1), steady_clock::duration::zero())
            : interval;
        LOGGER_V("lock=%p retry after attempt=%u delay_us=%lld remaining_us=%lld final=%d",
            static_cast<void*>(lock), attempts, static_cast<long long>(duration_cast<microseconds>(delay).count()),
            static_cast<long long>(duration_cast<microseconds>(remaining).count()), finalAttempt);
        this_thread::sleep_for(delay);
        interval = min(interval * 2, maxInterval);
    }
    if (acquired) return reinterpret_cast<jlong>(lock);
    LOGGER_W("lock=%p wait exhausted attempts=%u elapsed_us=%lld timeout_ms=%lld", static_cast<void*>(lock),
        attempts, static_cast<long long>(duration_cast<microseconds>(steady_clock::now() - started).count()),
        static_cast<long long>(waitMills));
    delete lock;
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_tryCreate0(
    JNIEnv* env, jclass, jstring path, jboolean shared) {
    auto* lock = new FileLock(env, path);
    if (lock->file < 0) {
        const int error = errno;
        delete lock;
        return -static_cast<jlong>(error);
    }
    const jlong result = lock->acquire(shared);
    if (result > 0) return reinterpret_cast<jlong>(lock);
    delete lock;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_release0(
    JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<FileLock*>(ptr);
}
