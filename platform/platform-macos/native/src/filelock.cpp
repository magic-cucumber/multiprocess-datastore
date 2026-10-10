#include <jni.h>
#include <sys/file.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <signal.h>
#include <chrono>
#include <condition_variable>
#include <codecvt>
#include <locale>
#include <mutex>
#include <string>
#include <thread>

namespace {
constexpr int cancellationSignal = SIGUSR1;
class FileLock {
public:
    int file;

    FileLock(JNIEnv* env, jstring path) {
        const jchar* text = env->GetStringChars(path, nullptr);
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> converter;
        const auto name = converter.to_bytes(
            reinterpret_cast<const char16_t*>(text),
            reinterpret_cast<const char16_t*>(text) + env->GetStringLength(path));
        env->ReleaseStringChars(path, text);
        file = open(name.c_str(), O_RDWR | O_CLOEXEC);
    }

    ~FileLock() { close(file); }

    bool acquire(bool shared, bool immediately) {
        return flock(file, (shared ? LOCK_SH : LOCK_EX) | (immediately ? LOCK_NB : 0)) == 0;
    }
};

// flock has no timed blocking API. The signal interrupts only the waiting worker.
// This signal is reserved for file-lock timeout cancellation.
void initializeCancellationSignal() {
    static std::once_flag initialized;
    std::call_once(initialized, [] {
        struct sigaction action{};
        action.sa_handler = [](int) {};
        sigemptyset(&action.sa_mask);
        sigaction(cancellationSignal, &action, nullptr); // No SA_RESTART: blocking flock returns on interruption.
    });
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_create0(
    JNIEnv* env, jclass, jstring path, jboolean shared, jlong waitMills) {
    auto* lock = new FileLock(env, path);
    bool acquired = false;
    if (waitMills <= 0) {
        acquired = lock->acquire(shared, true);
    } else {
        initializeCancellationSignal();
        std::mutex mutex;
        std::condition_variable completed;
        bool done = false;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(waitMills);
        std::thread worker([&] {
            sigset_t signals;
            sigemptyset(&signals);
            sigaddset(&signals, cancellationSignal);
            pthread_sigmask(SIG_UNBLOCK, &signals, nullptr);
            const bool result = lock->acquire(shared, false);
            std::lock_guard<std::mutex> guard(mutex);
            acquired = result;
            done = true;
            completed.notify_one();
        });
        std::unique_lock<std::mutex> guard(mutex);
        const bool timedOut = !completed.wait_until(guard, deadline, [&] { return done; });
        while (!done) {
            pthread_kill(worker.native_handle(), cancellationSignal);
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
    auto* lock = new FileLock(env, path);
    if (lock->acquire(shared, true)) return reinterpret_cast<jlong>(lock);
    delete lock;
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileLockImpl_release0(
    JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<FileLock*>(ptr);
}
