#include "datastore/logger.h"
#include <jni.h>
#include <atomic>
#include <cerrno>
#include <codecvt>
#include <cstdint>
#include <fcntl.h>
#include <locale>
#include <memory>
#include <new>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

namespace {
int counterError(const char* operation, int error, const void* value) {
    LOGGER_E("counter=%p operation=%s failed error=%lu", value, operation, static_cast<unsigned long>(error));
    return error;
}
constexpr size_t COUNTER_BYTES = 4;
static_assert(sizeof(std::atomic<uint32_t>) == COUNTER_BYTES, "Unexpected counter size");
static_assert(std::atomic<uint32_t>::is_always_lock_free, "Counter operations must be lock-free");

class SharedCounter {
    int file = -1;
    void* address = MAP_FAILED;
public:
    int initialize(JNIEnv* env, jstring path) {
        const jchar* text = env->GetStringChars(path, nullptr);
        if (text == nullptr) return counterError("GetStringChars", ENOMEM, this);
        std::string name;
        try {
            std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> converter;
            name = converter.to_bytes(reinterpret_cast<const char16_t*>(text),
                reinterpret_cast<const char16_t*>(text) + env->GetStringLength(path));
        } catch (...) {
            env->ReleaseStringChars(path, text);
            throw;
        }
        env->ReleaseStringChars(path, text);
        file = open(name.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0600);
        if (file < 0) return counterError("open", errno, this);
        LOGGER_D("counter=%p opened path=%s fd=%d", static_cast<void*>(this), name.c_str(), file);
        // Extending an empty file supplies zeroes; an existing counter is not reset.
        if (ftruncate(file, COUNTER_BYTES) != 0) return counterError("ftruncate", errno, this);
        LOGGER_D("counter=%p file resized bytes=%zu", static_cast<void*>(this), COUNTER_BYTES);
        address = mmap(nullptr, COUNTER_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
        if (address == MAP_FAILED) return counterError("mmap", errno, this);
        LOGGER_D("counter=%p mapped address=%p bytes=%zu", static_cast<void*>(this), address, COUNTER_BYTES);
        return 0;
    }

    jint getValue() const {
        const auto result = static_cast<jint>(reinterpret_cast<std::atomic<uint32_t>*>(address)->load());
        LOGGER_V("counter=%p read value=%d", static_cast<const void*>(this), result);
        return result;
    }

    jint incrementAndGetValue() {
        const auto result = static_cast<jint>(reinterpret_cast<std::atomic<uint32_t>*>(address)->fetch_add(1) + 1);
        LOGGER_V("counter=%p increment value=%d", static_cast<const void*>(this), result);
        return result;
    }

    int release() {
        int error = 0;
        if (address != MAP_FAILED) {
            if (munmap(address, COUNTER_BYTES) != 0) error = counterError("munmap", errno, this);
            else LOGGER_D("counter=%p unmapped address=%p", static_cast<void*>(this), address);
            address = MAP_FAILED;
        }
        if (file >= 0) {
            if (close(file) != 0) {
                const int failure = counterError("close", errno, this);
                if (error == 0) error = failure;
            } else LOGGER_D("counter=%p fd=%d closed", static_cast<void*>(this), file);
            file = -1;
        }
        return error;
    }

    ~SharedCounter() {
        if (address != MAP_FAILED || file >= 0) LOGGER_D("counter=%p rollback resources", static_cast<void*>(this));
        release();
    }
};

void throwCounterException(JNIEnv* env, int error) {
    const auto type = env->FindClass("top/kagg886/datastore/multiprocess/util/SharedCounterException");
    if (type == nullptr) return;
    const auto constructor = env->GetMethodID(type, "<init>", "(I)V");
    if (constructor == nullptr) return;
    const auto exception = static_cast<jthrowable>(env->NewObject(type, constructor, error));
    if (exception != nullptr) env->Throw(exception);
}

jfieldID pointerField(JNIEnv* env, jobject instance) {
    return env->GetFieldID(env->GetObjectClass(instance), "ptr", "J");
}

SharedCounter* counter(JNIEnv* env, jobject instance) {
    const auto ptr = env->GetLongField(instance, pointerField(env, instance));
    if (ptr == 0) {
        LOGGER_E("counter access rejected: pointer is closed");
        throwCounterException(env, EBADF);
        return nullptr;
    }
    return reinterpret_cast<SharedCounter*>(ptr);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_SharedCounterImpl_create0(
    JNIEnv* env, jclass, jobject path) {
    const auto toString = env->GetMethodID(env->GetObjectClass(path), "toString", "()Ljava/lang/String;");
    const auto name = static_cast<jstring>(env->CallObjectMethod(path, toString));
    if (env->ExceptionCheck()) {
        LOGGER_E("counter create: path.toString threw a JVM exception");
        return 0;
    }
    try {
        auto value = std::make_unique<SharedCounter>();
        const int error = value->initialize(env, name);
        if (error != 0) return -static_cast<jlong>(error);
        LOGGER_I("counter=%p created", static_cast<void*>(value.get()));
        return reinterpret_cast<jlong>(value.release());
    } catch (const std::bad_alloc&) {
        LOGGER_E("counter create: allocation failed");
        return -static_cast<jlong>(ENOMEM);
    } catch (const std::range_error&) {
        LOGGER_E("counter create: invalid UTF-16 path");
        return -static_cast<jlong>(EINVAL);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_kagg886_datastore_multiprocess_util_SharedCounterImpl_getValue(JNIEnv* env, jobject instance) {
    auto* value = counter(env, instance);
    return value == nullptr ? 0 : value->getValue();
}

extern "C" JNIEXPORT jint JNICALL
Java_top_kagg886_datastore_multiprocess_util_SharedCounterImpl_incrementAndGetValue(
    JNIEnv* env, jobject instance) {
    auto* value = counter(env, instance);
    return value == nullptr ? 0 : value->incrementAndGetValue();
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_SharedCounterImpl_close0(JNIEnv* env, jobject instance) {
    const auto field = pointerField(env, instance);
    std::unique_ptr<SharedCounter> value(reinterpret_cast<SharedCounter*>(env->GetLongField(instance, field)));
    env->SetLongField(instance, field, 0);
    LOGGER_D("counter=%p close requested", static_cast<void*>(value.get()));
    const int error = value->release();
    if (error != 0) throwCounterException(env, error);
}
