#include <chrono>
#include <cerrno>
#include <iostream>
#include <map>
#include <stdexcept>
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
        if (handle == INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot open lock file");
#else
        fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
        if (fd < 0) throw std::runtime_error("Cannot open lock file");
#endif
    }
    bool acquire(bool shared) {
#ifdef _WIN32
        if (LockFileEx(handle, LOCKFILE_FAIL_IMMEDIATELY |
            (shared ? 0 : LOCKFILE_EXCLUSIVE_LOCK), 0, MAXDWORD, MAXDWORD, &offset)) return true;
        if (GetLastError() != ERROR_LOCK_VIOLATION) throw std::runtime_error("LockFileEx failed");
        return false;
#else
        if (flock(fd, (shared ? LOCK_SH : LOCK_EX) | LOCK_NB) == 0) return true;
        if (errno != EWOULDBLOCK && errno != EAGAIN && errno != EINTR)
            throw std::runtime_error("flock failed");
        return false;
#endif
    }
    void release() {
#ifdef _WIN32
        if (handle != INVALID_HANDLE_VALUE) { CloseHandle(handle); handle = INVALID_HANDLE_VALUE; }
#else
        if (fd >= 0) { close(fd); fd = -1; }
#endif
    }
    ~Lock() { release(); }
};

static void event(const char* name, steady_clock::time_point started) {
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
    while (!lock.acquire(mode == "shared")) {
        if (steady_clock::now() - started >= timeout) { std::cerr << "Acquire timeout\n"; return 3; }
        std::this_thread::sleep_for(milliseconds(2));
    }
    event("LOCKED", started);
    if (action == "hold-for") {
        std::this_thread::sleep_for(duration);
    } else {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line == "STOP") break;
        }
    }
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
    try { return run(argc, pointers.data()); }
#else
int main(int argc, char** argv) {
    try { return run(argc, argv); }
#endif
    catch (const std::invalid_argument& e) { std::cerr << e.what() << '\n'; return 2; }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 4; }
}
