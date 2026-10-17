#include "helper_logger.h"
#include <chrono>
#include <cerrno>
#include <iostream>
#include <map>
#include <stdexcept>
#include <system_error>
#include <string>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>
#endif

using namespace std::chrono;

// Independent OS lock implementation: deliberately does not link production code.
class Lock {
#ifdef _WIN32
    HANDLE handle = INVALID_HANDLE_VALUE;
    OVERLAPPED offset{};
#else
    int fd = -1;
#endif
public:
    explicit Lock(const std::string& path) {
        LOGGER_D_TAGGABLE("LockHelper", "Open lock path=%s", path.c_str());
#ifdef _WIN32
        const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(),
                                            static_cast<int>(path.size()), nullptr, 0);
        if (!size) throw std::runtime_error("Invalid UTF-8 path");
        std::wstring wide(size, L'\0');
        MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(),
                            static_cast<int>(path.size()), wide.data(), size);
        handle = CreateFileW(wide.c_str(), GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (handle == INVALID_HANDLE_VALUE) throw std::system_error(static_cast<int>(GetLastError()), std::system_category(), "CreateFileW.lock");
#else
        fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
        if (fd < 0) throw std::system_error(errno, std::generic_category(), "open.lock");
#endif
    }
    bool acquire(bool shared) {
#ifdef _WIN32
        if (LockFileEx(handle, LOCKFILE_FAIL_IMMEDIATELY |
            (shared ? 0 : LOCKFILE_EXCLUSIVE_LOCK), 0, MAXDWORD, MAXDWORD, &offset)) return true;
        const auto error = GetLastError();
        if (error != ERROR_LOCK_VIOLATION) throw std::system_error(static_cast<int>(error), std::system_category(), "LockFileEx");
        return false;
#else
        if (flock(fd, (shared ? LOCK_SH : LOCK_EX) | LOCK_NB) == 0) return true;
        if (errno != EWOULDBLOCK && errno != EAGAIN && errno != EINTR)
            throw std::system_error(errno, std::generic_category(), "flock");
        return false;
#endif
    }
    void release() {
#ifdef _WIN32
        if (handle != INVALID_HANDLE_VALUE) {
            if (!CloseHandle(handle)) LOGGER_E_TAGGABLE("LockHelper", "CloseHandle failed code=%lu", GetLastError());
            else LOGGER_D_TAGGABLE("LockHelper", "Lock handle closed");
            handle = INVALID_HANDLE_VALUE;
        }
#else
        if (fd >= 0) {
            if (close(fd) != 0) LOGGER_E_TAGGABLE("LockHelper", "close failed code=%d", errno);
            else LOGGER_D_TAGGABLE("LockHelper", "Lock descriptor closed");
            fd = -1;
        }
#endif
    }
    ~Lock() { release(); }
};

static void event(const char* name, steady_clock::time_point started) {
    LOGGER_D_TAGGABLE("LockHelper", "State=%s elapsed_ns=%lld", name, static_cast<long long>(duration_cast<nanoseconds>(steady_clock::now() - started).count()));
#ifdef _WIN32
    const auto pid = GetCurrentProcessId();
#else
    const auto pid = getpid();
#endif
    std::cout << "{\"event\":\"" << name << "\",\"pid\":" << pid
              << ",\"epoch_ms\":" << duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count()
              << ",\"elapsed_ns\":" << duration_cast<nanoseconds>(steady_clock::now() - started).count()
              << "}" << std::endl;
}

static long long number(const std::string& value) {
    size_t end = 0;
    const auto n = std::stoll(value, &end);
    if (end != value.size() || n < 0) throw std::invalid_argument("Invalid duration");
    return n;
}

#include "counter_helper.h"

static int run(int argc, char** argv) {
    if (argc >= 2 && std::string(argv[1]) == "counter") return runCounter(argc, argv);
    if (argc < 2) throw std::invalid_argument("Expected hold or hold-for");
    const std::string action = argv[1];
    if (action != "hold" && action != "hold-for") throw std::invalid_argument("Unknown action");
    std::map<std::string, std::string> args;
    for (int i = 2; i < argc; i += 2) {
        if (i + 1 >= argc) throw std::invalid_argument("Missing argument value");
        const std::string key = argv[i];
        if (key != "--path" && key != "--mode" && key != "--acquire-timeout-ms" && key != "--duration-ms")
            throw std::invalid_argument("Unknown argument");
        if (!args.emplace(key, argv[i + 1]).second) throw std::invalid_argument("Duplicate argument");
    }
    if (!args.count("--path") || !args.count("--mode")) throw std::invalid_argument("Missing path or mode");
    const auto mode = args.at("--mode");
    if (mode != "shared" && mode != "exclusive") throw std::invalid_argument("Invalid mode");
    const auto timeout = milliseconds(number(args.count("--acquire-timeout-ms") ? args.at("--acquire-timeout-ms") : "5000"));
    if (action == "hold-for" && !args.count("--duration-ms")) throw std::invalid_argument("Missing duration");
    const auto duration = milliseconds(action == "hold-for" ? number(args.at("--duration-ms")) : 0);
    Lock lock(args.at("--path"));
    const auto started = steady_clock::now();
    event("ATTEMPTING", started);
    LOGGER_D_TAGGABLE("LockHelper", "Acquire mode=%s timeout_ms=%lld", mode.c_str(), static_cast<long long>(timeout.count()));
    unsigned attempts = 0;
    while (!lock.acquire(mode == "shared")) {
        LOGGER_V_TAGGABLE("LockHelper", "Lock busy attempt=%u; retry in 2ms", ++attempts);
        if (steady_clock::now() - started >= timeout) { LOGGER_E_TAGGABLE("LockHelper", "Acquire timeout attempts=%u", attempts); return 3; }
        std::this_thread::sleep_for(milliseconds(2));
    }
    event("LOCKED", started);
    if (action == "hold-for") {
        LOGGER_D_TAGGABLE("LockHelper", "Holding for duration_ms=%lld", static_cast<long long>(duration.count()));
        std::this_thread::sleep_for(duration);
    } else {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            LOGGER_V_TAGGABLE("LockHelper", "Command received %s", line.c_str());
            if (line == "STOP") break;
            LOGGER_W_TAGGABLE("LockHelper", "Ignoring unknown command %s", line.c_str());
        }
    }
    if (std::cin.eof()) LOGGER_W_TAGGABLE("LockHelper", "stdin EOF; releasing lock");
    LOGGER_D_TAGGABLE("LockHelper", "Release requested");
    lock.release();
    event("RELEASED", started);
    return 0;
}

#ifdef _WIN32
int wmain(int argc, wchar_t** argv) {
    // Preserve Unicode arguments even on Windows hosts with a legacy code page.
    std::vector<std::string> text;
    std::vector<char*> pointers;
    for (int i = 0; i < argc; ++i) {
        const int size = WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, nullptr, 0, nullptr, nullptr);
        std::string value(size, '\0');
        WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, value.data(), size, nullptr, nullptr);
        value.pop_back();
        text.push_back(std::move(value));
    }
    for (auto& value : text) pointers.push_back(value.data());
    try { return runLoggedHelper("LockHelper", [&] { return run(argc, pointers.data()); }); }
#else
int main(int argc, char** argv) {
    try { return runLoggedHelper("LockHelper", [&] { return run(argc, argv); }); }
#endif
    catch (const std::invalid_argument& e) { std::cerr << e.what() << '\n'; return 2; }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 4; }
}
