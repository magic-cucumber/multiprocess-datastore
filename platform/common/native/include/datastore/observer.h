#pragma once

#include <array>
#include <functional>
#include <memory>
#include <string>
#include <vector>

// Raw platform notifications. Rename interpretation belongs exclusively to Kotlin.
struct DirectoryEvents {
    enum Kind { Create, Remove, Modified, RenameOld, RenameNew };
    std::array<std::vector<std::string>, 5> names;
    bool overflow = false;
};

struct DirectorySink {
    std::function<void(const DirectoryEvents&)> events;
    std::function<void(int)> terminated;
};

class DirectoryWatcher {
public:
    virtual ~DirectoryWatcher() = default;
    // Success means the OS watcher is armed, not merely that a thread was spawned.
    virtual int start() = 0;
    virtual void stop() noexcept = 0;
};

std::unique_ptr<DirectoryWatcher> make_directory_watcher(std::string path, DirectorySink sink);
