#include <cstdint>
#ifndef _WIN32
#include <sys/mman.h>
#endif

// A separate process with an independent atomic implementation, not linked to the library.
class CounterMapping {
#ifdef _WIN32
    HANDLE file = INVALID_HANDLE_VALUE;
    HANDLE mapping = nullptr;
    volatile LONG* address = nullptr;
#else
    int file = -1;
    void* address = MAP_FAILED;
    static_assert(__atomic_always_lock_free(4, nullptr), "Counter must be lock-free");
#endif
public:
    bool openFile(const std::string& path) {
        LOGGER_D_TAGGABLE("LockHelper.Counter", "Open mapping path=%s", path.c_str());
#ifdef _WIN32
        const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(),
                                            static_cast<int>(path.size()), nullptr, 0);
        if (!size) return false;
        std::wstring wide(size, L'\0');
        MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(),
                            static_cast<int>(path.size()), wide.data(), size);
        file = CreateFileW(wide.c_str(), GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) return false;
        mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, 4, nullptr);
        if (!mapping) return false;
        address = static_cast<volatile LONG*>(MapViewOfFile(mapping,
            FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 4));
        return address != nullptr;
#else
        file = open(path.c_str(), O_RDWR | O_CLOEXEC);
        if (file < 0) return false;
        address = mmap(nullptr, 4, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
        return address != MAP_FAILED;
#endif
    }
    void increment() {
#ifdef _WIN32
        InterlockedIncrement(address);
#else
        __atomic_fetch_add(static_cast<uint32_t*>(address), 1u, __ATOMIC_SEQ_CST);
#endif
    }
    ~CounterMapping() {
        LOGGER_D_TAGGABLE("LockHelper.Counter", "Release mapping requested");
#ifdef _WIN32
        if (address) UnmapViewOfFile(const_cast<LONG*>(address));
        if (mapping) CloseHandle(mapping);
        if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
#else
        if (address != MAP_FAILED) munmap(address, 4);
        if (file >= 0) close(file);
#endif
        LOGGER_D_TAGGABLE("LockHelper.Counter", "Mapping cleanup complete");
    }
};

static int runCounter(int argc, char** argv) {
    if (argc != 6 || std::string(argv[2]) != "--path" || std::string(argv[4]) != "--count")
        throw std::invalid_argument("Expected counter --path PATH --count COUNT");
    const auto count = number(argv[5]);
    CounterMapping counter;
    if (!counter.openFile(argv[3])) throw std::runtime_error("Cannot map counter file");
    const auto started = steady_clock::now();
    event("READY", started);
    std::string command;
    if (!std::getline(std::cin, command) || command != "STOP") {
        LOGGER_W_TAGGABLE("LockHelper.Counter", "Missing STOP handshake eof=%d", std::cin.eof());
        return 2;
    }
    LOGGER_D_TAGGABLE("LockHelper.Counter", "Increment batch begin count=%lld", count);
    event("INCREMENTING", started);
    for (long long i = 0; i < count; ++i) counter.increment();
    LOGGER_D_TAGGABLE("LockHelper.Counter", "Increment batch complete count=%lld", count);
    event("DONE", started);
    return 0;
}
