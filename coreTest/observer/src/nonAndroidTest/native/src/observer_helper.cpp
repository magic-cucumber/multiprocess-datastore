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
static void output(const std::string& text) { std::cout << text << std::endl; }
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
    std::ofstream file;
    file.exceptions(std::ios::failbit | std::ios::badbit);
    file.open(path, std::ios::binary | (append ? std::ios::app : std::ios::trunc));
    file.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    file.close();
}
static void replace(const fs::path& path, const std::string& bytes) {
    auto temporary = path; temporary += ".observer-replace";
    try {
        write(temporary, bytes);
#ifdef _WIN32
        if (!ReplaceFileW(path.c_str(), temporary.c_str(), nullptr, 0, nullptr, nullptr))
            throw std::system_error(static_cast<int>(GetLastError()), std::system_category(), "replace");
#else
        fs::rename(temporary, path);
#endif
    } catch (...) { std::error_code ignored; fs::remove(temporary, ignored); throw; }
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
        output("{\"event\":\"READY\",\"protocol\":1,\"pid\":" + std::to_string(pid) + "}");
        commands = true;
        uint64_t previous = 0;
        std::string line;
        while (std::getline(std::cin, line)) {
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
            const auto prefix = ",\"id\":" + std::to_string(id);
            if (action == "CLOSE" && tokens.size() == 2) {
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
                std::fstream file;
                file.exceptions(std::ios::failbit | std::ios::badbit);
                file.open(path, std::ios::binary | std::ios::in | std::ios::out);
                file.seekp(static_cast<std::streamoff>(offset));
                file.write(bytes.data(), static_cast<std::streamsize>(bytes.size())); file.close();
            } else if (action == "RESIZE" && tokens.size() == 4) {
                const auto size = number(tokens[3]);
                operation = "resize"; fs::resize_file(path, size);
            } else if (action == "SET_MTIME" && tokens.size() == 4) {
                const auto millis = number(tokens[3], 4102444800000ULL);
                operation = "mtime"; setMtime(path, millis);
            } else if (action == "DELETE" && tokens.size() == 3) {
                operation = "delete";
                if (!fs::remove(path)) throw std::system_error(std::make_error_code(std::errc::no_such_file_or_directory), "delete");
            } else if (action == "MOVE_OUT" && tokens.size() == 3) {
                operation = "rename";
                auto moved = path; moved += ".moved"; fs::rename(path, moved);
            } else if (action == "BURST" && tokens.size() == 4) {
                const auto count = number(tokens[3], 10000);
                if (!count) throw std::invalid_argument("Empty burst");
                operation = "burst";
                for (uint64_t i = 1; i <= count; ++i) write(path, "version:" + std::to_string(i));
            } else throw std::invalid_argument("Invalid command or arguments");
            const auto size = fs::exists(path) ? std::to_string(fs::file_size(path)) : "null";
            output("{\"event\":\"DONE\"" + prefix + ",\"size\":" + size + "}");
        }
        if (!std::cin.eof()) throw std::runtime_error("stdin read failed");
        output("{\"event\":\"CLOSED\",\"id\":null}"); return 0;
    } catch (const std::exception& error) {
        const auto invalid = dynamic_cast<const std::invalid_argument*>(&error) != nullptr;
        const auto* system = dynamic_cast<const std::system_error*>(&error);
        output("{\"event\":\"ERROR\",\"id\":" + (id ? std::to_string(id) : "null") +
            ",\"kind\":\"" + (invalid ? (commands ? "COMMAND" : "ARGUMENT") : "OS") +
            "\",\"operation\":\"" + operation + "\",\"code\":" + (system ? std::to_string(system->code().value()) : "null") + "}");
        std::cerr << error.what() << std::endl;
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
    return run(argc, pointers.data());
}
#else
int main(int argc, char** argv) { return run(argc, argv); }
#endif
