#include "datastore/file_listener_jni.h"
#include "datastore/logger.h"

#include <dispatch/dispatch.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <exception>
#include <filesystem>

namespace {
class MacFileListener final : public datastore::FileListenerJni {
    struct Watch {
        MacFileListener* owner;
        dispatch_source_t source;
        int fd;
        bool directory;
        dev_t device;
        ino_t inode;
    };
    std::string path_;
    std::string directory_path_;
    dispatch_queue_t queue_ = nullptr;
    int directory_fd_ = -1;
    Watch* directory_ = nullptr;
    Watch* file_ = nullptr;
    size_t active_ = 0; // Includes retired sources waiting for their cancellation handler.
    std::exception_ptr startup_error_;
    static void fail(const char* operation) { throw std::system_error(errno, std::generic_category(), operation); }

    Watch* watch(int fd, bool directory) {
        struct stat opened {};
        if (fstat(fd, &opened) < 0) { const int error = errno; close(fd); throw std::system_error(error, std::generic_category(), "fstat"); }
        if ((!directory && !S_ISREG(opened.st_mode)) || (directory && !S_ISDIR(opened.st_mode))) {
            close(fd); throw std::runtime_error("Invalid file listener target");
        }
        std::unique_ptr<Watch> value;
        try { value = std::make_unique<Watch>(Watch {this, nullptr, fd, directory, opened.st_dev, opened.st_ino}); }
        catch (...) { close(fd); throw; }
        value->source = dispatch_source_create(DISPATCH_SOURCE_TYPE_VNODE, fd,
            DISPATCH_VNODE_WRITE | DISPATCH_VNODE_EXTEND | DISPATCH_VNODE_ATTRIB |
            DISPATCH_VNODE_DELETE | DISPATCH_VNODE_RENAME | DISPATCH_VNODE_REVOKE, queue_);
        if (!value->source) { close(fd); throw std::bad_alloc(); }
        dispatch_set_context(value->source, value.get());
        dispatch_source_set_event_handler_f(value->source, changed);
        dispatch_source_set_cancel_handler_f(value->source, cancelled);
        ++active_;
        LOGGER_D("listener=%p watch created fd=%d directory=%d device=%llu inode=%llu", static_cast<void*>(this), fd,
            directory, static_cast<unsigned long long>(opened.st_dev), static_cast<unsigned long long>(opened.st_ino));
        dispatch_resume(value->source);
        return value.release();
    }
    void retire(Watch*& value) {
        if (!value) return;
        LOGGER_D("listener=%p retiring watch fd=%d directory=%d", static_cast<void*>(this), value->fd, value->directory);
        dispatch_source_cancel(value->source);
        value = nullptr;
    }
    bool rebind() {
        for (int attempt = 0; attempt < 3; ++attempt) {
            struct stat current {};
            if (lstat(path_.c_str(), &current) < 0) { if (errno == ENOENT) return false; fail("lstat.target"); }
            if (!S_ISREG(current.st_mode)) throw std::runtime_error("Listener target is no longer a regular file");
            if (file_ && file_->device == current.st_dev && file_->inode == current.st_ino) return true;
            if (file_) LOGGER_W("listener=%p target identity changed; rebinding", static_cast<void*>(this));
            LOGGER_D("listener=%p bind target path=%s attempt=%d", static_cast<void*>(this), path_.c_str(), attempt + 1);
            retire(file_);
            const int fd = open(path_.c_str(), O_EVTONLY | O_CLOEXEC | O_NOFOLLOW);
            if (fd < 0) { if (errno == ENOENT) continue; fail("open.target"); }
            file_ = watch(fd, false);
            struct stat after {};
            const int result = lstat(path_.c_str(), &after);
            if (result == 0 && file_->device == after.st_dev && file_->inode == after.st_ino) return true;
            if (result < 0 && errno != ENOENT) fail("lstat.rebind");
        }
        throw std::runtime_error("Listener target kept changing while rebinding");
    }
    static void changed(void* context) noexcept {
        auto* value = static_cast<Watch*>(context);
        auto* self = value->owner;
        if (self->stopping.load()) return;
        try {
            const auto flags = dispatch_source_get_data(value->source);
            LOGGER_V("listener=%p vnode fd=%d directory=%d flags=0x%lx", static_cast<void*>(self), value->fd, value->directory, flags);
            if (value->directory && (flags & (DISPATCH_VNODE_DELETE | DISPATCH_VNODE_RENAME | DISPATCH_VNODE_REVOKE))) {
                LOGGER_W("listener=%p directory invalidated flags=0x%lx", static_cast<void*>(self), flags);
                self->requestClose(true); return;
            }
            // An old vnode's DELETE can mean an atomic replacement; inspect the current path first.
            if (!self->rebind()) {
                LOGGER_W("listener=%p target disappeared path=%s", static_cast<void*>(self), self->path_.c_str());
                self->requestClose(true);
            } else if (!self->notifyChanged()) self->requestClose(true);
        } catch (const std::exception& error) {
            LOGGER_E("listener=%p vnode processing failed: %s", static_cast<void*>(self), error.what());
            self->requestClose(true);
        } catch (...) {
            LOGGER_E("listener=%p vnode processing failed: unknown exception", static_cast<void*>(self));
            self->requestClose(true);
        }
    }
    static void cancelled(void* context) noexcept {
        auto* value = static_cast<Watch*>(context);
        auto* self = value->owner;
        if (close(value->fd) != 0) {
            const int error = errno;
            LOGGER_E("listener=%p close watch fd=%d failed errno=%d", static_cast<void*>(self), value->fd, error);
        } else LOGGER_D("listener=%p watch closed fd=%d remaining=%zu", static_cast<void*>(self), value->fd, self->active_ - 1);
        dispatch_release(value->source);
        delete value;
        if (--self->active_ == 0 && self->stopping.load()) self->finish();
    }
    static void cancelAll(void* context) noexcept {
        auto* self = static_cast<MacFileListener*>(context);
        self->retire(self->directory_);
        self->retire(self->file_);
        if (self->active_ == 0) self->finish();
    }
    static void startOnQueue(void* context) noexcept {
        auto* self = static_cast<MacFileListener*>(context);
        try {
            const int fd = self->directory_fd_;
            self->directory_fd_ = -1;
            self->directory_ = self->watch(fd, true);
            if (!self->rebind()) throw std::runtime_error("Listener file disappeared during startup");
            LOGGER_I("listener=%p started path=%s", static_cast<void*>(self), self->path_.c_str());
        } catch (...) { self->startup_error_ = std::current_exception(); }
    }
public:
    explicit MacFileListener(std::string file) : path_(std::move(file)) {
        directory_path_ = std::filesystem::u8path(path_).parent_path().u8string();
    }
    ~MacFileListener() override {
        if (directory_fd_ >= 0) close(directory_fd_);
        if (queue_) dispatch_release(queue_);
    }
    void initialize() override {
        directory_fd_ = open(directory_path_.c_str(), O_EVTONLY | O_CLOEXEC | O_NOFOLLOW);
        if (directory_fd_ < 0) fail("open.directory");
        queue_ = dispatch_queue_create("datastore-file-listener", DISPATCH_QUEUE_SERIAL);
        if (!queue_) throw std::bad_alloc();
        LOGGER_D("listener=%p initialized path=%s directory_fd=%d", static_cast<void*>(this), path_.c_str(), directory_fd_);
    }
    void start() override {
        dispatch_sync_f(queue_, this, startOnQueue);
        if (startup_error_) std::rethrow_exception(startup_error_);
    }
    void requestClose(bool terminated) noexcept override {
        if (!beginClose(terminated)) return;
        dispatch_async_f(queue_, this, cancelAll);
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileChangeListenerNative_create0(
    JNIEnv* env, jobject, jstring file, jobject callback) {
    return datastore::file_listener_create<MacFileListener>(env, file, callback);
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
