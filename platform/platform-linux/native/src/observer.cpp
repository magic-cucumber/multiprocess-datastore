#include "datastore/observer.h"

#include <sys/inotify.h>
#include <sys/eventfd.h>
#include <sys/stat.h>
#include <poll.h>
#include <unistd.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <thread>

namespace {
class LinuxDirectoryWatcher final : public DirectoryWatcher {
    std::string path_;
    DirectorySink sink_;
    int fd_ = -1;
    int wake_ = -1;
    int watch_ = -1;
    struct stat identity_ {};
    std::atomic<bool> stopped_ {false};
    std::thread thread_;

    void loop() noexcept {
        int failure = 0;
        try {
            alignas(inotify_event) std::array<char, 64 * 1024> buffer {};
            pollfd waits[] {{fd_, POLLIN, 0}, {wake_, POLLIN, 0}};
            while (!stopped_.load()) {
                const int polled = poll(waits, 2, 1000);
                if (polled < 0) { if (errno == EINTR) continue; failure = errno; break; }
                if (waits[1].revents & POLLIN) break;
                struct stat current {};
                if (stat(path_.c_str(), &current) != 0) { failure = errno; break; }
                if (current.st_dev != identity_.st_dev || current.st_ino != identity_.st_ino) { failure = ENOENT; break; }
                if (polled == 0) continue;
                if (waits[0].revents & (POLLERR | POLLHUP | POLLNVAL)) { failure = EIO; break; }
                const ssize_t length = read(fd_, buffer.data(), buffer.size());
                if (length < 0) { if (errno == EAGAIN || errno == EINTR) continue; failure = errno; break; }
                if (length == 0) { failure = EIO; break; }
                DirectoryEvents events;
                for (size_t offset = 0; offset < static_cast<size_t>(length);) {
                    if (static_cast<size_t>(length) - offset < sizeof(inotify_event)) { events.overflow = true; break; }
                    const auto* event = reinterpret_cast<const inotify_event*>(buffer.data() + offset);
                    if (event->len > static_cast<size_t>(length) - offset - sizeof(inotify_event)) { events.overflow = true; break; }
                    offset += sizeof(inotify_event) + event->len;
                    if (event->mask & IN_Q_OVERFLOW) { events.overflow = true; continue; }
                    if (event->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_IGNORED | IN_UNMOUNT)) { failure = ENOENT; break; }
                    if ((event->mask & IN_ISDIR) || event->len == 0) continue;
                    const std::string name(event->name, strnlen(event->name, event->len));
                    if (event->mask & IN_CREATE) events.names[DirectoryEvents::Create].push_back(name);
                    if (event->mask & IN_DELETE) events.names[DirectoryEvents::Remove].push_back(name);
                    if (event->mask & (IN_MODIFY | IN_CLOSE_WRITE | IN_ATTRIB)) events.names[DirectoryEvents::Modified].push_back(name);
                    if (event->mask & IN_MOVED_FROM) events.names[DirectoryEvents::RenameOld].push_back(name);
                    if (event->mask & IN_MOVED_TO) events.names[DirectoryEvents::RenameNew].push_back(name);
                }
                if (failure != 0) break;
                if (!stopped_.load()) sink_.events(events);
            }
        } catch (const std::bad_alloc&) { failure = ENOMEM; }
          catch (...) { failure = -1; }
        if (failure != 0 && !stopped_.load()) sink_.terminated(failure);
    }

public:
    LinuxDirectoryWatcher(std::string path, DirectorySink sink) : path_(std::move(path)), sink_(std::move(sink)) {}
    ~LinuxDirectoryWatcher() override { stop(); }
    int start() override {
        if (stat(path_.c_str(), &identity_) != 0) return errno;
        if (!S_ISDIR(identity_.st_mode)) return ENOTDIR;
        fd_ = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
        if (fd_ < 0) return errno;
        wake_ = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        if (wake_ < 0) return errno;
        watch_ = inotify_add_watch(fd_, path_.c_str(), IN_ONLYDIR | IN_DONT_FOLLOW | IN_MODIFY | IN_CLOSE_WRITE |
            IN_ATTRIB | IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO | IN_DELETE_SELF | IN_MOVE_SELF);
        if (watch_ < 0) return errno;
        thread_ = std::thread([this] { loop(); });
        return 0;
    }
    void stop() noexcept override {
        stopped_.store(true);
        if (wake_ >= 0) { const uint64_t value = 1; const auto ignored = write(wake_, &value, sizeof(value)); (void)ignored; }
        if (thread_.joinable()) thread_.join();
        if (fd_ >= 0) { close(fd_); fd_ = -1; watch_ = -1; }
        if (wake_ >= 0) { close(wake_); wake_ = -1; }
    }
};
}

std::unique_ptr<DirectoryWatcher> make_directory_watcher(std::string path, DirectorySink sink) {
    return std::make_unique<LinuxDirectoryWatcher>(std::move(path), std::move(sink));
}
