#include <jni.h>
#include <windows.h>
#include <algorithm>
#include <chrono>
#include <string>
#include <thread>

using namespace std;
using namespace std::chrono;

namespace {
class FileLock {
public:
    HANDLE file;
    OVERLAPPED overlapped{};

    FileLock(JNIEnv* env, jstring path) {
        const jchar* text = env->GetStringChars(path, nullptr);
        const wstring name(reinterpret_cast<const wchar_t*>(text), env->GetStringLength(path));
        env->ReleaseStringChars(path, text);
        file = CreateFileW(name.c_str(), GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    }

    ~FileLock() { CloseHandle(file); }

    jlong acquire(bool shared) {
        if (LockFileEx(file, (shared ? 0 : LOCKFILE_EXCLUSIVE_LOCK) |
            LOCKFILE_FAIL_IMMEDIATELY, 0, MAXDWORD, MAXDWORD, &overlapped)) return 1;
        const DWORD error = GetLastError();
        if (error == ERROR_LOCK_VIOLATION) return 0;
        return -static_cast<jlong>(error);
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
        return 0;
    }

    auto* lock = new FileLock(env, path);
    if (lock->file == INVALID_HANDLE_VALUE) {
        const DWORD error = GetLastError();
        delete lock;
        return -static_cast<jlong>(error);
    }
    const auto started = steady_clock::now();
    const auto timeout = milliseconds(waitMills);
    auto interval = milliseconds(1);
    const auto maxInterval = milliseconds(64);
    bool acquired = false;
    bool finalAttempt = false;
    while (true) {
        auto elapsed = steady_clock::now() - started;
        if (elapsed >= timeout) break;
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
        this_thread::sleep_for(delay);
        interval = min(interval * 2, maxInterval);
    }
    if (acquired) return reinterpret_cast<jlong>(lock);
    delete lock;
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_tryCreate0(
    JNIEnv* env, jclass, jstring path, jboolean shared) {
    auto* lock = new FileLock(env, path);
    if (lock->file == INVALID_HANDLE_VALUE) {
        const DWORD error = GetLastError();
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
