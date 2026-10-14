#include "datastore/file_listener_jni.h"

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
            if (events_ < 0) throw std::system_error(errno, std::generic_category());
            wake_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
            if (wake_ < 0) throw std::system_error(errno, std::generic_category());
            epoll_event event {};
            event.events = EPOLLIN;
            event.data.u64 = 0;
            if (epoll_ctl(events_, EPOLL_CTL_ADD, wake_, &event) < 0)
                throw std::system_error(errno, std::generic_category());
            thread_ = std::thread([this] { run(); });
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
    static void fail() { throw std::system_error(errno, std::generic_category()); }

    void event(uint32_t flags) noexcept {
        if (stopping.load()) return;
        if (flags & (EPOLLERR | EPOLLHUP)) { requestClose(true); return; }
        alignas(inotify_event) std::array<char, 65536> buffer {};
        bool relevant = false;
        // Bounded draining lets all watches progress even under sustained writes.
        for (int batch = 0; batch < 8; ++batch) {
            const auto bytes = read(queue_, buffer.data(), buffer.size());
            if (bytes < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN) break;
                requestClose(true); return;
            }
            if (bytes == 0) { requestClose(true); return; }
            for (size_t offset = 0; offset < static_cast<size_t>(bytes);) {
                if (static_cast<size_t>(bytes) - offset < sizeof(inotify_event)) { requestClose(true); return; }
                const auto* item = reinterpret_cast<const inotify_event*>(buffer.data() + offset);
                if (item->len > static_cast<size_t>(bytes) - offset - sizeof(inotify_event)) { requestClose(true); return; }
                if (item->mask & IN_Q_OVERFLOW) relevant = true;
                if (item->wd == watch_) {
                    if (item->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED | IN_UNMOUNT)) {
                        requestClose(true); return;
                    }
                    if (item->len && std::strncmp(item->name, name_.c_str(), item->len) == 0) relevant = true;
                }
                offset += sizeof(inotify_event) + item->len;
            }
        }
        if (relevant && !notifyChanged()) requestClose(true);
    }
    void dispose() noexcept {
        if (queue_ >= 0) { close(queue_); queue_ = -1; }
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
        if (queue_ < 0) fail();
        watch_ = inotify_add_watch(queue_, directory_path_.c_str(), IN_ONLYDIR |
            IN_MODIFY | IN_CLOSE_WRITE | IN_ATTRIB | IN_CREATE | IN_DELETE |
            IN_MOVED_FROM | IN_MOVED_TO | IN_DELETE_SELF | IN_MOVE_SELF);
        if (watch_ < 0) fail();
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
        if (command->close) {
            epoll_ctl(events_, EPOLL_CTL_DEL, listener->queue_, nullptr);
            listeners_.erase(listener->id_);
            listener->dispose();
        } else if (!listener->stopping.load()) {
            if (failed_.load()) {
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
                    throw std::system_error(errno, std::generic_category());
            } catch (...) { listener->requestClose(true); }
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
