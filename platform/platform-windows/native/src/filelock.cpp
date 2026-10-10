#include <jni.h>
#include <windows.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

namespace {
class FileLock {
public:
    HANDLE file;
    OVERLAPPED overlapped{};

    FileLock(JNIEnv* env, jstring path, bool asynchronous) {
        const jchar* text = env->GetStringChars(path, nullptr);
        const std::wstring name(reinterpret_cast<const wchar_t*>(text), env->GetStringLength(path));
        env->ReleaseStringChars(path, text);
        file = CreateFileW(name.c_str(), GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
            OPEN_EXISTING, asynchronous ? FILE_FLAG_OVERLAPPED : FILE_ATTRIBUTE_NORMAL, nullptr);
    }

    ~FileLock() { CloseHandle(file); }

    bool acquire(bool shared, bool immediately) {
        return LockFileEx(file, (shared ? 0 : LOCKFILE_EXCLUSIVE_LOCK) |
            (immediately ? LOCKFILE_FAIL_IMMEDIATELY : 0), 0, MAXDWORD, MAXDWORD, &overlapped);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_create0(
    JNIEnv* env, jclass, jstring path, jboolean shared, jlong waitMills) {
    auto* lock = new FileLock(env, path, false);
    bool acquired = false;
    if (waitMills <= 0) {
        acquired = lock->acquire(shared, true);
    } else {
        std::mutex mutex;
        std::condition_variable completed;
        bool done = false;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(waitMills);
        std::thread worker([&] {
            const bool result = lock->acquire(shared, false);
            std::lock_guard<std::mutex> guard(mutex);
            acquired = result;
            done = true;
            completed.notify_one();
        });
        std::unique_lock<std::mutex> guard(mutex);
        const bool timedOut = !completed.wait_until(guard, deadline, [&] { return done; });
        while (!done) {
            CancelSynchronousIo(worker.native_handle());
            completed.wait_for(guard, std::chrono::milliseconds(1), [&] { return done; });
        }
        guard.unlock();
        worker.join();
        if (timedOut) acquired = false;
    }
    if (acquired) return reinterpret_cast<jlong>(lock);
    delete lock;
    return -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_tryCreate0(
    JNIEnv* env, jclass, jstring path, jboolean shared) {
    auto* lock = new FileLock(env, path, true);
    if (lock->acquire(shared, true)) return reinterpret_cast<jlong>(lock);
    delete lock;
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_release0(
    JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<FileLock*>(ptr);
}
