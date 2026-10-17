#include "helper_logger.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include <cerrno>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

using namespace std::chrono;

struct SystemError : std::runtime_error {
    int code;
    explicit SystemError(const char* operation) : std::runtime_error(operation),
#ifdef _WIN32
        code(static_cast<int>(GetLastError())) {}
#else
        code(errno) {}
#endif
};

// Independent OS implementation. Never link the production library into this helper.
class CounterMapping {
#ifdef _WIN32
    HANDLE file = INVALID_HANDLE_VALUE;
    HANDLE mapping = nullptr;
    volatile LONG* address = nullptr;
    static_assert(sizeof(LONG) == 4, "Unexpected counter size");
#else
    int file = -1;
    void* address = MAP_FAILED;
    static_assert(__atomic_always_lock_free(4, nullptr), "Counter must be lock-free");
#endif
public:
    void openFile(const std::string& path) {
        LOGGER_D_TAGGABLE("CounterHelper", "Open mapping path=%s", path.c_str());
#ifdef _WIN32
        const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(),
                                             static_cast<int>(path.size()), nullptr, 0);
        if (!size) throw SystemError("Invalid UTF-8 path");
        std::wstring wide(size, L'\0');
        if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path.data(),
                                static_cast<int>(path.size()), wide.data(), size))
            throw SystemError("Path conversion failed");
        file = CreateFileW(wide.c_str(), GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) throw SystemError("CreateFileW failed");
        LARGE_INTEGER length{};
        if (!GetFileSizeEx(file, &length)) throw SystemError("GetFileSizeEx failed");
        if (length.QuadPart != 4) throw std::invalid_argument("Counter file must contain exactly 4 bytes");
        mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, 4, nullptr);
        if (!mapping) throw SystemError("CreateFileMappingW failed");
        address = static_cast<volatile LONG*>(MapViewOfFile(mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 4));
        if (!address) throw SystemError("MapViewOfFile failed");
#else
        file = open(path.c_str(), O_RDWR | O_CLOEXEC);
        if (file < 0) throw SystemError("open failed");
        struct stat info{};
        if (fstat(file, &info) != 0) throw SystemError("fstat failed");
        if (!S_ISREG(info.st_mode) || info.st_size != 4)
            throw std::invalid_argument("Counter file must contain exactly 4 bytes");
        address = mmap(nullptr, 4, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
        if (address == MAP_FAILED) throw SystemError("mmap failed");
#endif
        LOGGER_D_TAGGABLE("CounterHelper", "Mapping ready address=%p", static_cast<const volatile void*>(address));
    }
    int32_t read() const {
#ifdef _WIN32
        return static_cast<int32_t>(InterlockedCompareExchange(address, 0, 0));
#else
        const uint32_t bits = __atomic_load_n(static_cast<uint32_t*>(address), __ATOMIC_SEQ_CST);
        int32_t result;
        std::memcpy(&result, &bits, 4);
        return result;
#endif
    }
    int32_t increment() {
#ifdef _WIN32
        return static_cast<int32_t>(InterlockedIncrement(address));
#else
        const uint32_t bits = __atomic_add_fetch(static_cast<uint32_t*>(address), 1u, __ATOMIC_SEQ_CST);
        int32_t result;
        std::memcpy(&result, &bits, 4);
        return result;
#endif
    }
    void release() {
#ifdef _WIN32
        if (!address && !mapping && file == INVALID_HANDLE_VALUE) return;
#else
        if (address == MAP_FAILED && file < 0) return;
#endif
        LOGGER_D_TAGGABLE("CounterHelper", "Release mapping requested");
        int error = 0;
#ifdef _WIN32
        if (address && !UnmapViewOfFile(const_cast<LONG*>(address))) error = static_cast<int>(GetLastError());
        address = nullptr;
        if (mapping && !CloseHandle(mapping) && !error) error = static_cast<int>(GetLastError());
        mapping = nullptr;
        if (file != INVALID_HANDLE_VALUE && !CloseHandle(file) && !error) error = static_cast<int>(GetLastError());
        file = INVALID_HANDLE_VALUE;
#else
        if (address != MAP_FAILED && munmap(address, 4) != 0) error = errno;
        address = MAP_FAILED;
        if (file >= 0 && close(file) != 0 && !error) error = errno;
        file = -1;
#endif
        if (error) {
            SystemError failure("Counter release failed");
            failure.code = error;
            throw failure;
        }
        LOGGER_D_TAGGABLE("CounterHelper", "Mapping resources released");
    }
    ~CounterMapping() { try { release(); } catch (const std::exception& error) { LOGGER_E_TAGGABLE("CounterHelper", "Destructor cleanup failed: %s", error.what()); } }
};

static int64_t number(const std::string& text) {
    if (text.empty() || text.find_first_not_of("0123456789") != std::string::npos)
        throw std::invalid_argument("Expected nonnegative integer");
    try { return std::stoll(text); }
    catch (const std::exception&) { throw std::invalid_argument("Integer out of range"); }
}

static int64_t epochMillis() {
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

static steady_clock::time_point deadline(int64_t epoch) {
    const auto now = steady_clock::now();
    const auto wall = epochMillis();
    // Prevent chrono overflow and accidental indefinitely pending test commands.
    if (epoch > wall && epoch - wall > 60'000)
        throw std::invalid_argument("Target must be within the next 60000 milliseconds");
    return now + milliseconds(epoch - wall);
}

static void waitUntil(steady_clock::time_point target) {
    const auto spinStart = target - milliseconds(2);
    if (steady_clock::now() < spinStart) std::this_thread::sleep_until(spinStart);
    while (steady_clock::now() < target) std::atomic_signal_fence(std::memory_order_seq_cst);
}

static std::string prefix(const char* event, int64_t id) {
    return std::string("{\"event\":\"") + event + "\",\"id\":" + std::to_string(id);
}

static void output(const std::string& json) {
    LOGGER_V_TAGGABLE("CounterHelper", "Protocol response bytes=%zu preview=%.*s", json.size(), 256, json.c_str());
    std::cout << json << std::endl;
}

static std::string escape(const std::string& text) {
    std::string result;
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { result += '\\'; result += static_cast<char>(c); }
        else if (c < 32) result += '?';
        else result += static_cast<char>(c);
    }
    return result;
}

static int run(int argc, char** argv) {
    int64_t request = 0;
    bool commandPhase = false;
    try {
        if (argc == 2 && std::string(argv[1]) == "--help") {
            std::cout << "counter-helper --path PATH\n"
                         "stdin: ID READ | ID INC [EPOCH_MS] | ID RUN COUNT [EPOCH_MS] | ID CLOSE\n";
            return 0;
        }
        if (argc != 3 || std::string(argv[1]) != "--path")
            throw std::invalid_argument("Expected --path PATH");
        CounterMapping counter;
        counter.openFile(argv[2]);
#ifdef _WIN32
        const auto pid = GetCurrentProcessId();
#else
        const auto pid = getpid();
#endif
        output("{\"event\":\"READY\",\"protocol\":1,\"pid\":" + std::to_string(pid) + "}");
        LOGGER_I_TAGGABLE("CounterHelper", "Ready path=%s", argv[2]);
        commandPhase = true;
        int64_t previous = 0;
        std::string line;
        while (std::getline(std::cin, line)) {
            LOGGER_V_TAGGABLE("CounterHelper", "Command received %s", line.c_str());
            request = 0;
            std::istringstream input(line);
            std::vector<std::string> tokens;
            for (std::string token; input >> token;) tokens.push_back(token);
            if (tokens.size() < 2) throw std::invalid_argument("Expected ID COMMAND");
            request = number(tokens[0]);
            if (request <= previous) throw std::invalid_argument("Request IDs must be increasing positive integers");
            previous = request;
            const auto& action = tokens[1];
            LOGGER_D_TAGGABLE("CounterHelper", "Dispatch id=%lld action=%s", static_cast<long long>(request), action.c_str());
            if (action == "READ" && tokens.size() == 2) {
                output(prefix("VALUE", request) + ",\"value\":" + std::to_string(counter.read()) + "}");
            } else if (action == "CLOSE" && tokens.size() == 2) {
                LOGGER_I_TAGGABLE("CounterHelper", "Close requested id=%lld", static_cast<long long>(request));
                counter.release();
                output(prefix("CLOSED", request) + "}");
                return 0;
            } else if ((action == "INC" && (tokens.size() == 2 || tokens.size() == 3)) ||
                       (action == "RUN" && (tokens.size() == 3 || tokens.size() == 4))) {
                const bool batch = action == "RUN";
                const auto count = batch ? number(tokens[2]) : 1;
                if (count > 1'000'000) throw std::invalid_argument("Count exceeds 1000000");
                const size_t timeIndex = batch ? 3 : 2;
                const bool timed = tokens.size() > timeIndex;
                const auto epoch = timed ? number(tokens[timeIndex]) : epochMillis();
                const auto target = deadline(epoch);
                // Allocate before ARMED. No I/O or allocation in the timed increment loop.
                std::vector<int32_t> values(static_cast<size_t>(count));
                LOGGER_D_TAGGABLE("CounterHelper", "Increment prepared id=%lld count=%lld target_epoch_ms=%lld timed=%d", static_cast<long long>(request), static_cast<long long>(count), static_cast<long long>(epoch), timed);
                if (timed && epoch < epochMillis()) LOGGER_W_TAGGABLE("CounterHelper", "Target already passed id=%lld", static_cast<long long>(request));
                if (timed) output(prefix("ARMED", request) + ",\"target_epoch_ms\":" + std::to_string(epoch) + "}");
                if (batch) output(prefix("STARTED", request) + ",\"count\":" + std::to_string(count) + "}");
                waitUntil(target);
                const auto startedEpoch = epochMillis();
                const auto late = duration_cast<nanoseconds>(steady_clock::now() - target).count();
                for (auto& value : values) value = counter.increment();
                LOGGER_D_TAGGABLE("CounterHelper", "Increments complete id=%lld count=%lld started_epoch_ms=%lld late_ns=%lld", static_cast<long long>(request), static_cast<long long>(count), static_cast<long long>(startedEpoch), static_cast<long long>(late));
                const auto timing = ",\"started_epoch_ms\":" + std::to_string(startedEpoch) +
                    ",\"late_ns\":" + std::to_string(late);
                if (!batch) {
                    output(prefix("VALUE", request) + ",\"value\":" + std::to_string(values[0]) + timing + "}");
                } else {
                    // Report only after all increments, so stdout backpressure cannot distort contention.
                    for (size_t offset = 0; offset < values.size(); offset += 256) {
                        std::string result = prefix("VALUES", request) + ",\"offset\":" + std::to_string(offset) + ",\"values\":[";
                        const auto end = std::min(offset + 256, values.size());
                        for (size_t i = offset; i < end; ++i) {
                            if (i != offset) result += ',';
                            result += std::to_string(values[i]);
                        }
                        output(result + "]}");
                    }
                    output(prefix("DONE", request) + ",\"count\":" + std::to_string(count) + timing + "}");
                }
            } else throw std::invalid_argument("Invalid command or argument count");
        }
        if (!std::cin.eof()) throw std::runtime_error("stdin read failed");
        LOGGER_W_TAGGABLE("CounterHelper", "stdin EOF; releasing mapping");
        counter.release();
        output("{\"event\":\"CLOSED\",\"id\":null}");
        return 0;
    } catch (const std::exception& error) {
        const auto* system = dynamic_cast<const SystemError*>(&error);
        const bool invalid = dynamic_cast<const std::invalid_argument*>(&error) != nullptr;
        const std::string id = request > 0 ? std::to_string(request) : "null";
        output("{\"event\":\"ERROR\",\"id\":" + id + ",\"kind\":\"" +
            (invalid ? (commandPhase ? "COMMAND" : "ARGUMENT") : "SYSTEM") +
            "\",\"code\":" + (system ? std::to_string(system->code) : "null") +
            ",\"message\":\"" + escape(error.what()) + "\"}");
        LOGGER_E_TAGGABLE("CounterHelper", "Failed id=%lld kind=%s code=%d error=%s", static_cast<long long>(request), invalid ? "invalid" : "system", system ? system->code : 0, error.what());
        return invalid ? 2 : 4;
    }
}

#ifdef _WIN32
int wmain(int argc, wchar_t** argv) {
    std::vector<std::string> text;
    std::vector<char*> pointers;
    for (int i = 0; i < argc; ++i) {
        const int size = WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, nullptr, 0, nullptr, nullptr);
        if (!size) return 4;
        std::string value(size, '\0');
        if (!WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, value.data(), size, nullptr, nullptr)) return 4;
        value.pop_back();
        text.push_back(std::move(value));
    }
    for (auto& value : text) pointers.push_back(value.data());
    return runLoggedHelper("CounterHelper", [&] { return run(argc, pointers.data()); });
}
#else
int main(int argc, char** argv) { return runLoggedHelper("CounterHelper", [&] { return run(argc, argv); }); }
#endif
