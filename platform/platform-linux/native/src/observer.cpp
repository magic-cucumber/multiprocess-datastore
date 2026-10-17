#include "datastore/file_listener_jni.h"
#include "datastore/logger.h"

#include <sys/inotify.h>
#include <sys/eventfd.h>
#include <sys/epoll.h>
#include <unistd.h>
#include <array>
#include <cerrno>
#include <cstdint>
#include <condition_variable>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <thread>
#include <unordered_map>

namespace {
class LinuxFileListener;
struct Command {
    LinuxFileListener* listener;
    bool close;
    Command* next = nullptr;
};

// One process-wide event service. No per-listener thread or file polling.
class InotifyEvents {
    int events_ = -1;
    int wake_ = -1;
    std::thread thread_;
    std::mutex commands_mutex_;
    std::condition_variable commands_ready_;
    std::atomic<bool> failed_ {false};
    Command* first_ = nullptr;
    Command* last_ = nullptr;
    std::unordered_map<uint64_t, LinuxFileListener*> listeners_; // Only touched by the event thread.
    void run() noexcept;
    void commands() noexcept;
    InotifyEvents() {
        try {
            events_ = epoll_create1(EPOLL_CLOEXEC);
            if (events_ < 0) throw std::system_error(errno, std::generic_category(), "epoll_create1");
            wake_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
            if (wake_ < 0) throw std::system_error(errno, std::generic_category(), "eventfd");
            epoll_event event {};
            event.events = EPOLLIN;
            event.data.u64 = 0;
            if (epoll_ctl(events_, EPOLL_CTL_ADD, wake_, &event) < 0)
                throw std::system_error(errno, std::generic_category(), "epoll_ctl.ADD");
            thread_ = std::thread([this] { run(); });
            LOGGER_I("inotify service started epoll=%d wake=%d", events_, wake_);
        } catch (...) {
            if (wake_ >= 0) close(wake_);
            if (events_ >= 0) close(events_);
            throw;
        }
    }
public:
    static InotifyEvents& instance() {
        // The shared service lives until process exit, independent of individual native observers.
        static auto* service = new InotifyEvents();
        return *service;
    }
    bool healthy() const noexcept { return !failed_.load(); }
    void post(Command* command) noexcept {
        LOGGER_D("inotify enqueue listener=%p close=%d", static_cast<void*>(command->listener), command->close);
        {
            std::lock_guard<std::mutex> guard(commands_mutex_);
            if (last_) last_->next = command; else first_ = command;
            last_ = command;
        }
        commands_ready_.notify_one();
        const uint64_t value = 1;
        ssize_t result;
        do { result = write(wake_, &value, sizeof(value)); } while (result < 0 && errno == EINTR);
    }
};

class LinuxFileListener final : public datastore::FileListenerJni {
    friend class InotifyEvents;
    static std::atomic<uint64_t> next_id_;
    const uint64_t id_ = next_id_.fetch_add(1);
    std::string directory_path_;
    std::string name_;
    int queue_ = -1;
    int watch_ = -1;
    InotifyEvents* loop_ = nullptr;
    Command start_command_ {this, false};
    Command close_command_ {this, true};
    static void fail(const char* operation) { throw std::system_error(errno, std::generic_category(), operation); }

    void event(uint32_t flags) noexcept {
        if (stopping.load()) return;
        LOGGER_V("listener=%p epoll flags=0x%x", static_cast<void*>(this), flags);
        if (flags & (EPOLLERR | EPOLLHUP)) {
            LOGGER_E("listener=%p inotify queue failed flags=0x%x", static_cast<void*>(this), flags);
            requestClose(true); return;
        }
        alignas(inotify_event) std::array<char, 65536> buffer {};
        bool relevant = false;
        // Bounded draining lets all watches progress even under sustained writes.
        for (int batch = 0; batch < 8; ++batch) {
            const auto bytes = read(queue_, buffer.data(), buffer.size());
            if (bytes < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN) break;
                const int error = errno;
                LOGGER_E("listener=%p inotify read failed errno=%d", static_cast<void*>(this), error);
                requestClose(true); return;
            }
            if (bytes == 0) {
                LOGGER_E("listener=%p inotify unexpected EOF", static_cast<void*>(this));
                requestClose(true); return;
            }
            LOGGER_V("listener=%p drain batch=%d bytes=%lld", static_cast<void*>(this), batch, static_cast<long long>(bytes));
            for (size_t offset = 0; offset < static_cast<size_t>(bytes);) {
                if (static_cast<size_t>(bytes) - offset < sizeof(inotify_event)) {
                    LOGGER_E("listener=%p truncated inotify header offset=%zu", static_cast<void*>(this), offset);
                    requestClose(true); return;
                }
                const auto* item = reinterpret_cast<const inotify_event*>(buffer.data() + offset);
                if (item->len > static_cast<size_t>(bytes) - offset - sizeof(inotify_event)) {
                    LOGGER_E("listener=%p truncated inotify name offset=%zu length=%u", static_cast<void*>(this), offset, item->len);
                    requestClose(true); return;
                }
                LOGGER_V("listener=%p event wd=%d mask=0x%x target_match=%d", static_cast<void*>(this),
                    item->wd, item->mask, item->len && std::strncmp(item->name, name_.c_str(), item->len) == 0);
                if (item->mask & IN_Q_OVERFLOW) {
                    LOGGER_W("listener=%p inotify overflow; reconciling target", static_cast<void*>(this));
                    relevant = true;
                }
                if (item->wd == watch_) {
                    if (item->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED | IN_UNMOUNT)) {
                        LOGGER_W("listener=%p directory watch invalidated mask=0x%x", static_cast<void*>(this), item->mask);
                        requestClose(true); return;
                    }
                    if (item->len && std::strncmp(item->name, name_.c_str(), item->len) == 0) relevant = true;
                }
                offset += sizeof(inotify_event) + item->len;
            }
        }
        LOGGER_V("listener=%p drain decision relevant=%d", static_cast<void*>(this), relevant);
        if (relevant && !notifyChanged()) requestClose(true);
    }
    void dispose() noexcept {
        if (queue_ >= 0) {
            if (close(queue_) != 0) {
                const int error = errno;
                LOGGER_E("listener=%p close queue failed errno=%d", static_cast<void*>(this), error);
            } else LOGGER_D("listener=%p queue=%d closed", static_cast<void*>(this), queue_);
            queue_ = -1;
        }
        watch_ = -1;
        finish();
    }
public:
    explicit LinuxFileListener(const std::string& file) {
        const auto path = std::filesystem::u8path(file);
        directory_path_ = path.parent_path().u8string();
        name_ = path.filename().u8string();
    }
    ~LinuxFileListener() override { if (queue_ >= 0) close(queue_); }
    void initialize() override {
        queue_ = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
        if (queue_ < 0) fail("inotify_init1");
        watch_ = inotify_add_watch(queue_, directory_path_.c_str(), IN_ONLYDIR |
            IN_MODIFY | IN_CLOSE_WRITE | IN_ATTRIB | IN_CREATE | IN_DELETE |
            IN_MOVED_FROM | IN_MOVED_TO | IN_DELETE_SELF | IN_MOVE_SELF);
        if (watch_ < 0) fail("inotify_add_watch");
        LOGGER_D("listener=%p inotify watch prepared directory=%s name=%s queue=%d watch=%d",
            static_cast<void*>(this), directory_path_.c_str(), name_.c_str(), queue_, watch_);
    }
    void start() override {
        loop_ = &InotifyEvents::instance();
        if (!loop_->healthy()) throw std::runtime_error("File event service is unavailable");
        loop_->post(&start_command_);
    }
    void requestClose(bool terminated) noexcept override {
        if (!beginClose(terminated)) return;
        if (loop_) loop_->post(&close_command_);
        else dispose(); // Inert startup failure, no event thread ever took ownership.
    }
};
std::atomic<uint64_t> LinuxFileListener::next_id_ {1};

void InotifyEvents::commands() noexcept {
    Command* command;
    {
        std::lock_guard<std::mutex> guard(commands_mutex_);
        command = first_;
        first_ = last_ = nullptr;
    }
    while (command) {
        auto* next = command->next; // The close command deletes its containing observer.
        auto* listener = command->listener;
        LOGGER_D("inotify execute listener=%p close=%d", static_cast<void*>(listener), command->close);
        if (command->close) {
            epoll_ctl(events_, EPOLL_CTL_DEL, listener->queue_, nullptr);
            listeners_.erase(listener->id_);
            listener->dispose();
        } else if (!listener->stopping.load()) {
            if (failed_.load()) {
                LOGGER_E("listener=%p registration rejected: event service failed", static_cast<void*>(listener));
                listener->requestClose(true);
                command = next;
                continue;
            }
            try {
                listeners_.emplace(listener->id_, listener);
                epoll_event event {};
                event.events = EPOLLIN;
                event.data.u64 = listener->id_; // IDs prevent stale events from referring to freed objects.
                if (epoll_ctl(events_, EPOLL_CTL_ADD, listener->queue_, &event) < 0)
                    throw std::system_error(errno, std::generic_category(), "epoll_ctl.ADD");
                LOGGER_I("listener=%p registered id=%llu", static_cast<void*>(listener), static_cast<unsigned long long>(listener->id_));
            } catch (const std::exception& error) {
                LOGGER_E("listener=%p epoll registration failed: %s", static_cast<void*>(listener), error.what());
                listener->requestClose(true);
            } catch (...) {
                LOGGER_E("listener=%p epoll registration failed: unknown exception", static_cast<void*>(listener));
                listener->requestClose(true);
            }
        }
        command = next;
    }
}

void InotifyEvents::run() noexcept {
    while (true) {
        epoll_event events[64];
        const auto count = epoll_wait(events_, events, 64, -1);
        if (count < 0) {
            if (errno == EINTR) continue;
            // A failed service rejects new watches but keeps draining cancellation commands.
            const int error = errno;
            LOGGER_E("inotify epoll_wait failed errno=%d; rejecting new watches", error);
            failed_.store(true);
            for (auto& item : listeners_) item.second->requestClose(true);
            commands();
            while (true) {
                std::unique_lock<std::mutex> guard(commands_mutex_);
                commands_ready_.wait(guard, [this] { return first_ != nullptr; });
                guard.unlock();
                commands();
            }
        }
        LOGGER_V("inotify epoll wake count=%d", count);
        for (int index = 0; index < count; ++index) if (events[index].data.u64 == 0) {
            uint64_t value;
            while (read(wake_, &value, sizeof(value)) > 0) {}
            commands();
            break;
        }
        for (int index = 0; index < count; ++index) {
            if (events[index].data.u64 == 0) continue;
            const auto found = listeners_.find(events[index].data.u64);
            if (found != listeners_.end()) found->second->event(events[index].events);
        }
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_kagg886_datastore_multiprocess_util_FileChangeListenerNative_create0(
    JNIEnv* env, jobject, jstring file, jobject callback) {
    return datastore::file_listener_create<LinuxFileListener>(env, file, callback);
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
