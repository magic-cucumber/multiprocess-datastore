#include <jni.h>
#include <windows.h>
#include <memory>
#include <new>
#include <string>

namespace {
constexpr DWORD COUNTER_BYTES = 4;
static_assert(sizeof(LONG) == COUNTER_BYTES, "Unexpected counter size");

class SharedCounter {
    HANDLE file = INVALID_HANDLE_VALUE;
    HANDLE mapping = nullptr;
    volatile LONG* address = nullptr;

    DWORD initializeFile() {
        OVERLAPPED offset{};
        if (!LockFileEx(file, LOCKFILE_EXCLUSIVE_LOCK, 0, COUNTER_BYTES, 0, &offset))
            return GetLastError();
        DWORD error = 0;
        LARGE_INTEGER size{};
        if (!GetFileSizeEx(file, &size)) {
            error = GetLastError();
        } else if (size.QuadPart < COUNTER_BYTES) {
            // File mapping extension does not guarantee zero-filled bytes on Windows.
            const DWORD zero = 0;
            DWORD written = 0;
            if (!WriteFile(file, &zero, COUNTER_BYTES, &written, nullptr)) error = GetLastError();
            else if (written != COUNTER_BYTES) error = ERROR_WRITE_FAULT;
        }
        if (!UnlockFileEx(file, 0, COUNTER_BYTES, 0, &offset) && error == 0) error = GetLastError();
        return error;
    }
public:
    DWORD initialize(JNIEnv* env, jstring path) {
        const jchar* text = env->GetStringChars(path, nullptr);
        if (text == nullptr) return ERROR_NOT_ENOUGH_MEMORY;
        std::wstring name;
        try {
            name.assign(reinterpret_cast<const wchar_t*>(text), env->GetStringLength(path));
        } catch (...) {
            env->ReleaseStringChars(path, text);
            throw;
        }
        env->ReleaseStringChars(path, text);
        file = CreateFileW(name.c_str(), GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
            OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) return GetLastError();
        if (const DWORD error = initializeFile()) return error;
        mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, COUNTER_BYTES, nullptr);
        if (mapping == nullptr) return GetLastError();
        address = static_cast<volatile LONG*>(MapViewOfFile(mapping, FILE_MAP_READ | FILE_MAP_WRITE,
            0, 0, COUNTER_BYTES));
        return address == nullptr ? GetLastError() : 0;
    }

    jint getValue() const { return static_cast<jint>(InterlockedCompareExchange(address, 0, 0)); }
    jint incrementAndGetValue() { return static_cast<jint>(InterlockedIncrement(address)); }

    DWORD release() {
        DWORD error = 0;
        if (address != nullptr) {
            if (!UnmapViewOfFile(const_cast<LONG*>(address))) error = GetLastError();
            address = nullptr;
        }
        if (mapping != nullptr) {
            if (!CloseHandle(mapping) && error == 0) error = GetLastError();
            mapping = nullptr;
        }
        if (file != INVALID_HANDLE_VALUE) {
            if (!CloseHandle(file) && error == 0) error = GetLastError();
            file = INVALID_HANDLE_VALUE;
        }
        return error;
    }

    ~SharedCounter() { release(); }
};

void throwCounterException(JNIEnv* env, DWORD error) {
    const auto type = env->FindClass("top/kagg886/datastore/multiprocess/util/SharedCounterException");
    if (type == nullptr) return;
    const auto constructor = env->GetMethodID(type, "<init>", "(I)V");
    if (constructor == nullptr) return;
    const auto exception = static_cast<jthrowable>(env->NewObject(type, constructor, static_cast<jint>(error)));
    if (exception != nullptr) env->Throw(exception);
}

jfieldID pointerField(JNIEnv* env, jobject instance) {
    return env->GetFieldID(env->GetObjectClass(instance), "ptr", "J");
}

SharedCounter* counter(JNIEnv* env, jobject instance) {
    const auto ptr = env->GetLongField(instance, pointerField(env, instance));
    if (ptr == 0) {
        throwCounterException(env, ERROR_INVALID_HANDLE);
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
    if (env->ExceptionCheck()) return 0;
    try {
        auto value = std::make_unique<SharedCounter>();
        const DWORD error = value->initialize(env, name);
        if (error != 0) return -static_cast<jlong>(error);
        return reinterpret_cast<jlong>(value.release());
    } catch (const std::bad_alloc&) {
        return -static_cast<jlong>(ERROR_NOT_ENOUGH_MEMORY);
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
    const DWORD error = value->release();
    if (error != 0) throwCounterException(env, error);
}
