#include "helper_logger.h"
#include <cerrno>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

// Independent file operations only; never link the production observer library.
namespace fs = std::filesystem;
constexpr uint64_t limit = 1024 * 1024;
static void output(const std::string& text) {
    LOGGER_V_TAGGABLE("ObserverHelper", "Protocol response %s", text.c_str());
    std::cout << text << std::endl;
}
static uint64_t number(const std::string& text, uint64_t maximum = limit) {
    if (text.empty() || text.find_first_not_of("0123456789") != std::string::npos)
        throw std::invalid_argument("Expected unsigned decimal");
    uint64_t value = 0;
    for (char ch : text) {
        const auto digit = static_cast<unsigned>(ch - '0');
        if (digit > maximum || value > (maximum - digit) / 10) throw std::invalid_argument("Number out of range");
        value = value * 10 + digit;
    }
    return value;
}
static std::string decode(const std::string& hex) {
    if (hex == "-") return {};
    if (hex.size() % 2 || hex.size() > limit * 2) throw std::invalid_argument("Invalid hex length");
    auto digit = [](char ch) -> int {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
        if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
        throw std::invalid_argument("Invalid hex digit");
    };
    std::string bytes;
    for (size_t i = 0; i < hex.size(); i += 2) bytes += static_cast<char>(digit(hex[i]) * 16 + digit(hex[i + 1]));
    return bytes;
}
static void write(const fs::path& path, const std::string& bytes, bool append = false) {
    LOGGER_V_TAGGABLE("ObserverHelper", "Open write path=%s append=%d bytes=%zu", path.u8string().c_str(), append, bytes.size());
    std::ofstream file;
    file.exceptions(std::ios::failbit | std::ios::badbit);
    file.open(path, std::ios::binary | (append ? std::ios::app : std::ios::trunc));
    LOGGER_D_TAGGABLE("ObserverHelper", "File opened path=%s; writing bytes=%zu", path.u8string().c_str(), bytes.size());
    file.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    LOGGER_V_TAGGABLE("ObserverHelper", "Write returned path=%s; closing", path.u8string().c_str());
    file.close();
    LOGGER_D_TAGGABLE("ObserverHelper", "File closed path=%s bytes=%zu", path.u8string().c_str(), bytes.size());
}
static void replace(const fs::path& path, const std::string& bytes) {
    auto temporary = path; temporary += ".observer-replace";
    try {
        write(temporary, bytes);
        LOGGER_D_TAGGABLE("ObserverHelper", "Replace begin target=%s temporary=%s", path.u8string().c_str(), temporary.u8string().c_str());
#ifdef _WIN32
        if (!ReplaceFileW(path.c_str(), temporary.c_str(), nullptr, 0, nullptr, nullptr))
            throw std::system_error(static_cast<int>(GetLastError()), std::system_category(), "replace");
#else
        fs::rename(temporary, path);
#endif
        LOGGER_D_TAGGABLE("ObserverHelper", "Replace complete target=%s", path.u8string().c_str());
    } catch (...) {
        std::error_code ignored;
        fs::remove(temporary, ignored);
        LOGGER_W_TAGGABLE("ObserverHelper", "Replace cleanup temporary=%s cleanup_error=%d", temporary.u8string().c_str(), ignored.value());
        throw;
    }
}
static void setMtime(const fs::path& path, uint64_t millis) {
#ifdef _WIN32
    const auto handle = CreateFileW(path.c_str(), FILE_WRITE_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (handle == INVALID_HANDLE_VALUE) throw std::system_error(static_cast<int>(GetLastError()), std::system_category(), "open");
    const uint64_t ticks = millis * 10000 + 116444736000000000ULL;
    FILETIME time{static_cast<DWORD>(ticks), static_cast<DWORD>(ticks >> 32)};
    const auto ok = SetFileTime(handle, nullptr, nullptr, &time);
    const auto code = GetLastError(); CloseHandle(handle);
    if (!ok) throw std::system_error(static_cast<int>(code), std::system_category(), "mtime");
#else
    timespec times[2]{};
    times[0].tv_nsec = UTIME_OMIT;
    times[1].tv_sec = static_cast<time_t>(millis / 1000);
    times[1].tv_nsec = static_cast<long>((millis % 1000) * 1000000);
    if (utimensat(AT_FDCWD, path.c_str(), times, 0) != 0)
        throw std::system_error(errno, std::generic_category(), "mtime");
#endif
}
static int run(int argc, char** argv) {
    uint64_t id = 0;
    bool commands = false;
    const char* operation = "startup";
    try {
        if (argc != 5 || std::string(argv[1]) != "--path" || std::string(argv[3]) != "--other")
            throw std::invalid_argument("Expected --path and --other");
        const fs::path target = fs::u8path(argv[2]), other = fs::u8path(argv[4]);
        if (!target.is_absolute() || !other.is_absolute() || target == other || target.parent_path() != other.parent_path())
            throw std::invalid_argument("Expected distinct absolute sibling paths");
        if (!fs::is_regular_file(target) || !fs::is_regular_file(other))
            throw std::invalid_argument("Expected existing regular files");
#ifdef _WIN32
        const auto pid = GetCurrentProcessId();
#else
        const auto pid = getpid();
#endif
        LOGGER_I_TAGGABLE("ObserverHelper", "Ready target=%s other=%s", argv[2], argv[4]);
        output("{\"event\":\"READY\",\"protocol\":1,\"pid\":" + std::to_string(pid) + "}");
        commands = true;
        uint64_t previous = 0;
        std::string line;
        while (std::getline(std::cin, line)) {
            LOGGER_V_TAGGABLE("ObserverHelper", "Command received bytes=%zu preview=%.*s", line.size(), 256, line.c_str());
            id = 0;
            operation = "command";
            if (line.size() > limit * 2 + 128) throw std::invalid_argument("Command too long");
            std::istringstream input(line);
            std::vector<std::string> tokens;
            std::string token;
            while (input >> token) tokens.push_back(token);
            if (tokens.size() < 2) throw std::invalid_argument("Missing command");
            id = number(tokens[0], INT64_MAX);
            if (!id || id <= previous) throw std::invalid_argument("Request ids must increase");
            previous = id;
            const auto& action = tokens[1];
            LOGGER_D_TAGGABLE("ObserverHelper", "Dispatch id=%llu action=%s", static_cast<unsigned long long>(id), action.c_str());
            const auto prefix = ",\"id\":" + std::to_string(id);
            if (action == "CLOSE" && tokens.size() == 2) {
                LOGGER_I_TAGGABLE("ObserverHelper", "Close requested id=%llu", static_cast<unsigned long long>(id));
                output("{\"event\":\"CLOSED\"" + prefix + "}"); return 0;
            }
            if (action == "PING" && tokens.size() == 2) {
                output("{\"event\":\"PONG\"" + prefix + "}"); continue;
            }
            if (tokens.size() < 3 || (tokens[2] != "TARGET" && tokens[2] != "OTHER"))
                throw std::invalid_argument("Expected TARGET or OTHER");
            const auto& path = tokens[2] == "TARGET" ? target : other;
            // Validate all arguments before mutating the file.
            if ((action == "WRITE" || action == "APPEND" || action == "REPLACE") && tokens.size() == 4) {
                const auto bytes = decode(tokens[3]);
                if (action == "REPLACE") { operation = "replace"; replace(path, bytes); }
                else {
                    if (action == "APPEND" && fs::file_size(path) + bytes.size() > limit)
                        throw std::invalid_argument("File too large");
                    operation = "write";
                    write(path, bytes, action == "APPEND");
                }
            } else if (action == "PATCH" && tokens.size() == 5) {
                const auto offset = number(tokens[3]); const auto bytes = decode(tokens[4]);
                if (offset + bytes.size() > fs::file_size(path)) throw std::invalid_argument("Patch outside file");
                operation = "patch";
                LOGGER_D_TAGGABLE("ObserverHelper", "Patch begin path=%s offset=%llu bytes=%zu", path.u8string().c_str(), static_cast<unsigned long long>(offset), bytes.size());
                std::fstream file;
                file.exceptions(std::ios::failbit | std::ios::badbit);
                file.open(path, std::ios::binary | std::ios::in | std::ios::out);
                file.seekp(static_cast<std::streamoff>(offset));
                file.write(bytes.data(), static_cast<std::streamsize>(bytes.size())); file.close();
            } else if (action == "RESIZE" && tokens.size() == 4) {
                const auto size = number(tokens[3]);
                operation = "resize";
                LOGGER_D_TAGGABLE("ObserverHelper", "Resize path=%s size=%llu", path.u8string().c_str(), static_cast<unsigned long long>(size));
                fs::resize_file(path, size);
            } else if (action == "SET_MTIME" && tokens.size() == 4) {
                const auto millis = number(tokens[3], 4102444800000ULL);
                operation = "mtime";
                LOGGER_D_TAGGABLE("ObserverHelper", "Set mtime path=%s epoch_ms=%llu", path.u8string().c_str(), static_cast<unsigned long long>(millis));
                setMtime(path, millis);
            } else if (action == "DELETE" && tokens.size() == 3) {
                operation = "delete";
                LOGGER_D_TAGGABLE("ObserverHelper", "Delete path=%s", path.u8string().c_str());
                if (!fs::remove(path)) throw std::system_error(std::make_error_code(std::errc::no_such_file_or_directory), "delete");
            } else if (action == "MOVE_OUT" && tokens.size() == 3) {
                operation = "rename";
                auto moved = path; moved += ".moved";
                LOGGER_D_TAGGABLE("ObserverHelper", "Move path=%s to=%s", path.u8string().c_str(), moved.u8string().c_str());
                fs::rename(path, moved);
            } else if (action == "BURST" && tokens.size() == 4) {
                const auto count = number(tokens[3], 10000);
                if (!count) throw std::invalid_argument("Empty burst");
                operation = "burst";
                LOGGER_D_TAGGABLE("ObserverHelper", "Burst begin path=%s count=%llu", path.u8string().c_str(), static_cast<unsigned long long>(count));
                for (uint64_t i = 1; i <= count; ++i) {
                    LOGGER_V_TAGGABLE("ObserverHelper", "Burst iteration=%llu", static_cast<unsigned long long>(i));
                    write(path, "version:" + std::to_string(i));
                }
            } else throw std::invalid_argument("Invalid command or arguments");
            LOGGER_D_TAGGABLE("ObserverHelper", "Command complete id=%llu operation=%s path=%s", static_cast<unsigned long long>(id), operation, path.u8string().c_str());
            const auto size = fs::exists(path) ? std::to_string(fs::file_size(path)) : "null";
            output("{\"event\":\"DONE\"" + prefix + ",\"size\":" + size + "}");
        }
        if (!std::cin.eof()) throw std::runtime_error("stdin read failed");
        LOGGER_W_TAGGABLE("ObserverHelper", "stdin EOF; closing command session");
        output("{\"event\":\"CLOSED\",\"id\":null}"); return 0;
    } catch (const std::exception& error) {
        const auto invalid = dynamic_cast<const std::invalid_argument*>(&error) != nullptr;
        const auto* system = dynamic_cast<const std::system_error*>(&error);
        output("{\"event\":\"ERROR\",\"id\":" + (id ? std::to_string(id) : "null") +
            ",\"kind\":\"" + (invalid ? (commands ? "COMMAND" : "ARGUMENT") : "OS") +
            "\",\"operation\":\"" + operation + "\",\"code\":" + (system ? std::to_string(system->code().value()) : "null") + "}");
        LOGGER_E_TAGGABLE("ObserverHelper", "Failed id=%llu operation=%s kind=%s code=%d error=%s", static_cast<unsigned long long>(id), operation, invalid ? "invalid" : "OS", system ? system->code().value() : 0, error.what());
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
    return runLoggedHelper("ObserverHelper", [&] { return run(argc, pointers.data()); });
}
#else
int main(int argc, char** argv) { return runLoggedHelper("ObserverHelper", [&] { return run(argc, argv); }); }
#endif
