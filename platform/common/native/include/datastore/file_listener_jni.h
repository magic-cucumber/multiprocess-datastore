#pragma once

#include "datastore/logger.h"

#include <jni.h>
#include <atomic>
#include <codecvt>
#include <locale>
#include <memory>
#include <stdexcept>
#include <string>
#include <system_error>

// Shared JNI plumbing only. OS callbacks and JNI exports live in each observer.cpp.
namespace datastore {
class FileListenerEnv {
    JavaVM* vm_;
    bool attached_ = false;
public:
    JNIEnv* env = nullptr;
    explicit FileListenerEnv(JavaVM* vm) : vm_(vm) {
        const auto result = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            attached_ = vm_->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK;
            if (!attached_) env = nullptr;
        } else if (result != JNI_OK) env = nullptr;
    }
    ~FileListenerEnv() { if (attached_) vm_->DetachCurrentThread(); }
};

class FileListenerJni {
    JavaVM* vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID changed_ = nullptr;
    jmethodID stopping_ = nullptr;
    jmethodID released_ = nullptr;
    bool terminated_ = false;
protected:
    std::atomic<bool> stopping {false};
    bool notifyChanged() noexcept {
        if (stopping.load()) return true;
        FileListenerEnv scope(vm_);
        if (!scope.env) {
            LOGGER_E("listener=%p unable to attach event thread to JVM", static_cast<void*>(this));
            return false;
        }
        LOGGER_V("listener=%p invoking JVM change callback", static_cast<void*>(this));
        const auto alive = scope.env->CallBooleanMethod(callback_, changed_);
        if (!scope.env->ExceptionCheck()) return alive == JNI_TRUE;
        LOGGER_E("listener=%p JVM change callback threw; cancelling", static_cast<void*>(this));
        scope.env->ExceptionClear();
        return false;
    }
    bool beginClose(bool terminated) noexcept {
        bool expected = false;
        if (!stopping.compare_exchange_strong(expected, true)) return false;
        LOGGER_D("listener=%p cancellation accepted terminated=%d", static_cast<void*>(this), terminated);
        terminated_ = terminated;
        // Detach the Java pointer before any asynchronous destruction can run.
        // Explicit close has already detached it and must not call back inline.
        if (terminated) {
            FileListenerEnv scope(vm_);
            if (scope.env) {
                scope.env->CallVoidMethod(callback_, stopping_);
                if (scope.env->ExceptionCheck()) scope.env->ExceptionClear();
            }
        }
        return true;
    }
    // Called by the final OS cancellation callback, after every OS resource is gone.
    void finish() noexcept {
        FileListenerEnv scope(vm_);
        const auto method = released_;
        const auto terminated = terminated_ ? JNI_TRUE : JNI_FALSE;
        jobject local = scope.env ? scope.env->NewLocalRef(callback_) : nullptr;
        if (scope.env && callback_) scope.env->DeleteGlobalRef(callback_);
        callback_ = nullptr;
        LOGGER_D("listener=%p native disposal complete; notifying JVM terminated=%d", static_cast<void*>(this), terminated);
        delete this;
        if (local) {
            scope.env->CallVoidMethod(local, method, terminated);
            if (scope.env->ExceptionCheck()) scope.env->ExceptionClear();
            scope.env->DeleteLocalRef(local);
        }
    }
public:
    virtual ~FileListenerJni() {
        if (callback_ && vm_) {
            FileListenerEnv scope(vm_);
            if (scope.env) scope.env->DeleteGlobalRef(callback_);
        }
    }
    void prepare(JNIEnv* env, jobject callback) {
        if (env->GetJavaVM(&vm_) != JNI_OK) throw std::runtime_error("Unable to obtain JVM");
        callback_ = env->NewGlobalRef(callback);
        if (!callback_) throw std::bad_alloc();
        const auto type = env->GetObjectClass(callback);
        if (!type) throw std::runtime_error("Unable to find file callback class");
        changed_ = env->GetMethodID(type, "nativeChanged", "()Z");
        if (!env->ExceptionCheck()) stopping_ = env->GetMethodID(type, "nativeStopping", "()V");
        if (!env->ExceptionCheck()) released_ = env->GetMethodID(type, "nativeReleased", "(Z)V");
        env->DeleteLocalRef(type);
        if (!changed_ || !stopping_ || !released_ || env->ExceptionCheck())
            throw std::runtime_error("Invalid file callback methods");
        LOGGER_D("listener=%p JNI callback reference and methods prepared", static_cast<void*>(this));
    }
    virtual void initialize() = 0; // Must not start callbacks.
    virtual void start() = 0;
    virtual void requestClose(bool terminated) noexcept = 0;
};

inline std::string file_listener_path(JNIEnv* env, jstring path) {
    const jchar* chars = env->GetStringChars(path, nullptr);
    if (!chars) throw std::bad_alloc();
    std::u16string value;
    try { value.assign(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(path)); }
    catch (...) { env->ReleaseStringChars(path, chars); throw; }
    env->ReleaseStringChars(path, chars);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>().to_bytes(value);
}

inline void file_listener_throw(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto klass = env->FindClass(type);
    if (klass) { env->ThrowNew(klass, message); env->DeleteLocalRef(klass); }
}

template<class T> jlong file_listener_create(JNIEnv* env, jstring path, jobject callback) noexcept {
    try {
        auto value = std::make_unique<T>(file_listener_path(env, path));
        value->prepare(env, callback);
        value->initialize();
        return reinterpret_cast<jlong>(value.release());
    } catch (const std::bad_alloc&) {
        file_listener_throw(env, "java/lang/OutOfMemoryError", "Unable to allocate file listener");
    } catch (const std::exception& error) {
        file_listener_throw(env, "java/io/IOException", error.what());
    } catch (...) {
        file_listener_throw(env, "java/io/IOException", "Unable to initialize file listener");
    }
    return 0;
}

inline void file_listener_start(JNIEnv* env, jlong pointer) noexcept {
    try { reinterpret_cast<FileListenerJni*>(pointer)->start(); }
    catch (const std::bad_alloc&) {
        file_listener_throw(env, "java/lang/OutOfMemoryError", "Unable to start file listener");
    } catch (const std::exception& error) {
        file_listener_throw(env, "java/io/IOException", error.what());
    } catch (...) {
        file_listener_throw(env, "java/io/IOException", "Unable to start file listener");
    }
}
}
