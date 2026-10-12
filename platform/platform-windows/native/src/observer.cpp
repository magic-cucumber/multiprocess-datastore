#include "datastore/observer.h"

#include <Windows.h>
#include <array>
#include <atomic>
#include <codecvt>
#include <locale>
#include <thread>

namespace {
class WindowsDirectoryWatcher final : public DirectoryWatcher {
    std::wstring path_;
    DirectorySink sink_;
    HANDLE directory_ = INVALID_HANDLE_VALUE;
    HANDLE changed_ = nullptr;
    HANDLE stop_event_ = nullptr;
    OVERLAPPED overlapped_ {};
    BY_HANDLE_FILE_INFORMATION identity_ {};
    alignas(DWORD) std::array<BYTE, 64 * 1024> buffer_ {};
    std::atomic<bool> stopped_ {false};
    std::thread thread_;
    bool pending_ = false;

    int arm() {
        ResetEvent(changed_);
        overlapped_ = {};
        overlapped_.hEvent = changed_;
        if (!ReadDirectoryChangesW(directory_, buffer_.data(), static_cast<DWORD>(buffer_.size()), FALSE,
            FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_LAST_WRITE | FILE_NOTIFY_CHANGE_SIZE,
            nullptr, &overlapped_, nullptr)) {
            return static_cast<int>(GetLastError());
        }
        pending_ = true;
        return 0;
    }

    int validate_root() {
        HANDLE current = CreateFileW(path_.c_str(), 0,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS, nullptr);
        if (current == INVALID_HANDLE_VALUE) return static_cast<int>(GetLastError());
        BY_HANDLE_FILE_INFORMATION info {};
        const BOOL success = GetFileInformationByHandle(current, &info);
        const int error = success ? 0 : static_cast<int>(GetLastError());
        CloseHandle(current);
        if (error != 0) return error;
        return (info.dwVolumeSerialNumber == identity_.dwVolumeSerialNumber &&
            info.nFileIndexHigh == identity_.nFileIndexHigh && info.nFileIndexLow == identity_.nFileIndexLow)
            ? 0 : ERROR_PATH_NOT_FOUND;
    }

    void cancel_pending() noexcept {
        if (!pending_ || directory_ == INVALID_HANDLE_VALUE) return;
        CancelIoEx(directory_, &overlapped_);
        DWORD ignored = 0;
        GetOverlappedResult(directory_, &overlapped_, &ignored, TRUE);
        pending_ = false;
    }

    void loop() noexcept {
        int failure = 0;
        try {
            const HANDLE waits[] {stop_event_, changed_};
            while (!stopped_.load()) {
                const DWORD result = WaitForMultipleObjects(2, waits, FALSE, 1000);
                if (result == WAIT_OBJECT_0) break;
                if (result == WAIT_FAILED) { failure = static_cast<int>(GetLastError()); break; }
                if ((failure = validate_root()) != 0) break;
                if (result == WAIT_TIMEOUT) continue;
                if (result != WAIT_OBJECT_0 + 1) { failure = ERROR_INVALID_HANDLE; break; }
                DWORD bytes = 0;
                const BOOL success = GetOverlappedResult(directory_, &overlapped_, &bytes, FALSE);
                const DWORD error = success ? ERROR_SUCCESS : GetLastError();
                pending_ = false;
                if (error != ERROR_SUCCESS && error != ERROR_NOTIFY_ENUM_DIR) {
                    if (!stopped_.load()) failure = static_cast<int>(error);
                    break;
                }
                DirectoryEvents events;
                events.overflow = bytes == 0 || error == ERROR_NOTIFY_ENUM_DIR;
                if (!events.overflow) {
                    size_t offset = 0;
                    while (offset < bytes) {
                        constexpr size_t header = offsetof(FILE_NOTIFY_INFORMATION, FileName);
                        if (bytes - offset < header) { events.overflow = true; break; }
                        const auto* notification = reinterpret_cast<const FILE_NOTIFY_INFORMATION*>(buffer_.data() + offset);
                        if (notification->FileNameLength > bytes - offset - header || notification->FileNameLength % sizeof(wchar_t) != 0) {
                            events.overflow = true; break;
                        }
                        if (notification->Action >= FILE_ACTION_ADDED && notification->Action <= FILE_ACTION_RENAMED_NEW_NAME) {
                            const std::wstring name(notification->FileName, notification->FileNameLength / sizeof(wchar_t));
                            events.names[notification->Action - FILE_ACTION_ADDED].push_back(
                                std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>>().to_bytes(name));
                        }
                        if (notification->NextEntryOffset == 0) break;
                        if (notification->NextEntryOffset < header || notification->NextEntryOffset % alignof(DWORD) != 0 ||
                            notification->NextEntryOffset > bytes - offset) { events.overflow = true; break; }
                        offset += notification->NextEntryOffset;
                    }
                }
                // Rearm before crossing JNI so slow Kotlin processing cannot stall collection.
                if ((failure = arm()) != 0) break;
                if (!stopped_.load()) sink_.events(events);
            }
        } catch (const std::bad_alloc&) { failure = ERROR_NOT_ENOUGH_MEMORY; }
          catch (...) { failure = -1; }
        cancel_pending();
        if (failure != 0 && !stopped_.load()) sink_.terminated(failure);
    }

public:
    WindowsDirectoryWatcher(std::string path, DirectorySink sink)
        : path_(std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>>().from_bytes(path)), sink_(std::move(sink)) {}
    ~WindowsDirectoryWatcher() override { stop(); }

    int start() override {
        directory_ = CreateFileW(path_.c_str(), FILE_LIST_DIRECTORY,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OVERLAPPED, nullptr);
        if (directory_ == INVALID_HANDLE_VALUE) return static_cast<int>(GetLastError());
        if (!GetFileInformationByHandle(directory_, &identity_)) return static_cast<int>(GetLastError());
        if (!(identity_.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) return ERROR_DIRECTORY;
        changed_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!changed_) return static_cast<int>(GetLastError());
        stop_event_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!stop_event_) return static_cast<int>(GetLastError());
        const int error = arm();
        if (error != 0) return error;
        thread_ = std::thread([this] { loop(); });
        return 0;
    }

    void stop() noexcept override {
        stopped_.store(true);
        if (stop_event_) SetEvent(stop_event_);
        if (thread_.joinable()) thread_.join();
        cancel_pending();
        if (directory_ != INVALID_HANDLE_VALUE) { CloseHandle(directory_); directory_ = INVALID_HANDLE_VALUE; }
        if (changed_) { CloseHandle(changed_); changed_ = nullptr; }
        if (stop_event_) { CloseHandle(stop_event_); stop_event_ = nullptr; }
    }
};
}

std::unique_ptr<DirectoryWatcher> make_directory_watcher(std::string path, DirectorySink sink) {
    return std::make_unique<WindowsDirectoryWatcher>(std::move(path), std::move(sink));
}
