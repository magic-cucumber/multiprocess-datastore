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

    bool acquire(bool shared) {
        return LockFileEx(file, (shared ? 0 : LOCKFILE_EXCLUSIVE_LOCK) |
            LOCKFILE_FAIL_IMMEDIATELY, 0, MAXDWORD, MAXDWORD, &overlapped);
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
        elapsed = duration_cast<milliseconds>(
            steady_clock::now() - started);
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
