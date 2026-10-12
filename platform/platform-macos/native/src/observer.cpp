#include "datastore/observer.h"

#include <CoreServices/CoreServices.h>
#include <dispatch/dispatch.h>
#include <sys/stat.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace {
class MacDirectoryWatcher final : public DirectoryWatcher {
    std::string path_;
    DirectorySink sink_;
    FSEventStreamRef stream_ = nullptr;
    dispatch_queue_t queue_ = nullptr;
    struct stat identity_ {};
    std::atomic<bool> stopped_ {false};
    std::thread monitor_;
    std::mutex mutex_;
    std::condition_variable changed_;

    static void callback(ConstFSEventStreamRef, void* context, size_t count, void* raw_paths,
        const FSEventStreamEventFlags flags[], const FSEventStreamEventId[]) {
        auto* self = static_cast<MacDirectoryWatcher*>(context);
        if (self->stopped_.load()) return;
        try {
            DirectoryEvents events;
            const auto paths = static_cast<char**>(raw_paths);
            const std::string prefix = self->path_ == "/" ? "/" : self->path_ + "/";
            for (size_t i = 0; i < count; ++i) {
                const auto flag = flags[i];
                if (flag & (kFSEventStreamEventFlagRootChanged | kFSEventStreamEventFlagUnmount)) {
                    self->sink_.terminated(ENOENT);
                    return;
                }
                if (flag & (kFSEventStreamEventFlagMustScanSubDirs | kFSEventStreamEventFlagKernelDropped |
                    kFSEventStreamEventFlagUserDropped | kFSEventStreamEventFlagEventIdsWrapped)) events.overflow = true;
                const std::string path(paths[i]);
                if (path.compare(0, prefix.size(), prefix) != 0) continue;
                const std::string name = path.substr(prefix.size());
                if (name.empty() || name.find('/') != std::string::npos) continue;
                if (flag & kFSEventStreamEventFlagItemIsDir) continue;
                // FSEvents cannot supply a reliable old/new pair. Kotlin performs a full name-based diff.
                if (flag & kFSEventStreamEventFlagItemRenamed) events.overflow = true;
                if (flag & kFSEventStreamEventFlagItemCreated) events.names[DirectoryEvents::Create].push_back(name);
                if (flag & kFSEventStreamEventFlagItemRemoved) events.names[DirectoryEvents::Remove].push_back(name);
                if (flag & (kFSEventStreamEventFlagItemModified | kFSEventStreamEventFlagItemInodeMetaMod))
                    events.names[DirectoryEvents::Modified].push_back(name);
            }
            if (!self->stopped_.load()) self->sink_.events(events);
        } catch (const std::bad_alloc&) { self->sink_.terminated(ENOMEM); }
          catch (...) { self->sink_.terminated(-1); }
    }

    void monitor_root() noexcept {
        try {
            std::unique_lock<std::mutex> lock(mutex_);
            while (!changed_.wait_for(lock, std::chrono::seconds(1), [this] { return stopped_.load(); })) {
                struct stat current {};
                const int result = stat(path_.c_str(), &current);
                const int error = result != 0 ? errno :
                    (current.st_dev != identity_.st_dev || current.st_ino != identity_.st_ino ? ENOENT : 0);
                if (error != 0) {
                    lock.unlock();
                    if (!stopped_.load()) sink_.terminated(error);
                    return;
                }
            }
        } catch (...) { if (!stopped_.load()) sink_.terminated(-1); }
    }

public:
    MacDirectoryWatcher(std::string path, DirectorySink sink) : path_(std::move(path)), sink_(std::move(sink)) {}
    ~MacDirectoryWatcher() override { stop(); }
    int start() override {
        if (stat(path_.c_str(), &identity_) != 0) return errno;
        if (!S_ISDIR(identity_.st_mode)) return ENOTDIR;
        CFStringRef path = CFStringCreateWithBytes(kCFAllocatorDefault,
            reinterpret_cast<const UInt8*>(path_.data()), static_cast<CFIndex>(path_.size()), kCFStringEncodingUTF8, false);
        if (!path) return EINVAL;
        const void* item = path;
        CFArrayRef paths = CFArrayCreate(kCFAllocatorDefault, &item, 1, &kCFTypeArrayCallBacks);
        CFRelease(path);
        if (!paths) return ENOMEM;
        FSEventStreamContext context {0, this, nullptr, nullptr, nullptr};
        stream_ = FSEventStreamCreate(kCFAllocatorDefault, callback, &context, paths,
            kFSEventStreamEventIdSinceNow, 0.05,
            kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagWatchRoot);
        CFRelease(paths);
        if (!stream_) return ENOMEM;
        queue_ = dispatch_queue_create("datastore.directory-observer", DISPATCH_QUEUE_SERIAL);
        if (!queue_) return ENOMEM;
        FSEventStreamSetDispatchQueue(stream_, queue_);
        if (!FSEventStreamStart(stream_)) return EIO;
        monitor_ = std::thread([this] { monitor_root(); });
        return 0;
    }
    void stop() noexcept override {
        stopped_.store(true);
        changed_.notify_all();
        if (monitor_.joinable()) monitor_.join();
        if (stream_) {
            FSEventStreamStop(stream_);
            FSEventStreamInvalidate(stream_);
            // Called from Kotlin's processing thread, never from this dispatch queue.
            if (queue_) dispatch_sync_f(queue_, nullptr, [](void*) {});
            FSEventStreamRelease(stream_);
            stream_ = nullptr;
        }
        if (queue_) { dispatch_release(queue_); queue_ = nullptr; }
    }
};
}

std::unique_ptr<DirectoryWatcher> make_directory_watcher(std::string path, DirectorySink sink) {
    return std::make_unique<MacDirectoryWatcher>(std::move(path), std::move(sink));
}
