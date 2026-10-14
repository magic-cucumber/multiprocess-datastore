#include "datastore/file_listener_jni.h"

#include <windows.h>
#include <array>
#include <cstddef>
#include <filesystem>
#include <mutex>

namespace {
class WindowsFileListener final : public datastore::FileListenerJni {
    std::wstring directory_path_;
    std::wstring name_;
    HANDLE directory_ = INVALID_HANDLE_VALUE;
    PTP_IO io_ = nullptr;
    PTP_WORK cleanup_ = nullptr;
    OVERLAPPED overlapped_ {};
    std::array<DWORD, 8192> buffer_ {};
    std::mutex io_mutex_;

    static void fail() { throw std::system_error(static_cast<int>(GetLastError()), std::system_category()); }
    bool arm() {
        std::lock_guard<std::mutex> guard(io_mutex_);
        if (stopping.load()) return true;
        overlapped_ = {};
        StartThreadpoolIo(io_);
        if (ReadDirectoryChangesW(directory_, buffer_.data(), static_cast<DWORD>(sizeof(buffer_)), FALSE,
                FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_LAST_WRITE | FILE_NOTIFY_CHANGE_SIZE |
                FILE_NOTIFY_CHANGE_ATTRIBUTES | FILE_NOTIFY_CHANGE_CREATION,
                nullptr, &overlapped_, nullptr)) return true;
        const auto error = GetLastError();
        if (error == ERROR_IO_PENDING) return true;
        CancelThreadpoolIo(io_); // No completion callback will be generated for a failed submission.
        SetLastError(error);
        return false;
    }
    bool relevant(DWORD bytes) const {
        if (bytes == 0 || bytes > sizeof(buffer_)) return true; // Overflow: reconcile the target.
        size_t offset = 0;
        constexpr size_t header = offsetof(FILE_NOTIFY_INFORMATION, FileName);
        while (offset < bytes) {
            if (bytes - offset < header) return true;
            const auto* item = reinterpret_cast<const FILE_NOTIFY_INFORMATION*>(
                reinterpret_cast<const char*>(buffer_.data()) + offset);
            if (item->FileNameLength % sizeof(wchar_t) != 0 || item->FileNameLength > bytes - offset - header) return true;
            if (CompareStringOrdinal(item->FileName, static_cast<int>(item->FileNameLength / sizeof(wchar_t)),
                    name_.data(), static_cast<int>(name_.size()), TRUE) == CSTR_EQUAL) return true;
            if (item->NextEntryOffset == 0) break;
            if (item->NextEntryOffset < header || item->NextEntryOffset > bytes - offset) return true;
            offset += item->NextEntryOffset;
        }
        return false;
    }
    static void CALLBACK completed(PTP_CALLBACK_INSTANCE, void* context, void*, ULONG result, ULONG_PTR bytes, PTP_IO) {
        auto* self = static_cast<WindowsFileListener*>(context);
        if (self->stopping.load()) return;
        if (result != ERROR_SUCCESS && result != ERROR_NOTIFY_ENUM_DIR) { self->requestClose(true); return; }
        const bool changed = result == ERROR_NOTIFY_ENUM_DIR || self->relevant(static_cast<DWORD>(bytes));
        if (changed && !self->notifyChanged()) { self->requestClose(true); return; }
        // Only one operation is armed per listener, so callbacks remain serial.
        if (!self->arm()) self->requestClose(true);
    }
    static void CALLBACK disposed(PTP_CALLBACK_INSTANCE instance, void* context, PTP_WORK) {
        auto* self = static_cast<WindowsFileListener*>(context);
        CallbackMayRunLong(instance);
        // Cancellation is asynchronous; only this final cleanup task waits for in-flight callbacks.
        WaitForThreadpoolIoCallbacks(self->io_, FALSE);
        CloseThreadpoolIo(self->io_);
        self->io_ = nullptr;
        CloseHandle(self->directory_);
        self->directory_ = INVALID_HANDLE_VALUE;
        CloseThreadpoolWork(self->cleanup_);
        self->cleanup_ = nullptr;
        self->finish(); // Deletes native object/global ref before calling Java nativeReleased.
    }
public:
    explicit WindowsFileListener(const std::string& file) {
        const auto path = std::filesystem::u8path(file);
        directory_path_ = path.parent_path().wstring();
        name_ = path.filename().wstring();
    }
    ~WindowsFileListener() override {
        // Only inert initialization failures reach these branches.
        if (io_) CloseThreadpoolIo(io_);
        if (cleanup_) CloseThreadpoolWork(cleanup_);
        if (directory_ != INVALID_HANDLE_VALUE) CloseHandle(directory_);
    }
    void initialize() override {
        directory_ = CreateFileW(directory_path_.c_str(), FILE_LIST_DIRECTORY,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OVERLAPPED, nullptr);
        if (directory_ == INVALID_HANDLE_VALUE) fail();
        io_ = CreateThreadpoolIo(directory_, completed, this, nullptr);
        if (!io_) fail();
        cleanup_ = CreateThreadpoolWork(disposed, this, nullptr);
        if (!cleanup_) fail();
    }
    void start() override { if (!arm()) fail(); }
    void requestClose(bool terminated) noexcept override {
        if (!beginClose(terminated)) return;
        {
            std::lock_guard<std::mutex> guard(io_mutex_);
            CancelIoEx(directory_, &overlapped_);
        }
        SubmitThreadpoolWork(cleanup_);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileChangeListenerNative_create0(
    JNIEnv* env, jobject, jstring file, jobject callback) {
    return datastore::file_listener_create<WindowsFileListener>(env, file, callback);
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileChangeListenerNative_start0(JNIEnv* env, jobject, jlong pointer) {
    datastore::file_listener_start(env, pointer);
}

extern "C" JNIEXPORT void JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileChangeListenerNative_close0(
    JNIEnv*, jobject, jlong pointer, jboolean terminated) {
    reinterpret_cast<datastore::FileListenerJni*>(pointer)->requestClose(terminated == JNI_TRUE);
}
