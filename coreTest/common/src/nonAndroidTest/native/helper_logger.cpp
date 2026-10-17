#include "datastore/logger.h"

#include <cerrno>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <string>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

// Standalone helpers use the logger.h API without linking production storage or JNI code.
// LOG LEVEL EPOCH_US TAG CONTENT; JSON responses remain a separate line protocol.
extern "C" void notify_jvm_logger(uint8_t level, const char* tag, const char* format, ...) {
    const int savedErrno = errno;
#ifdef _WIN32
    const DWORD savedError = GetLastError();
#endif
    static std::mutex mutex;
    static unsigned long long sequence = 0;
    static const int minimum = [] {
        const char* value = std::getenv("DATASTORE_TEST_LOG_LEVEL");
        return value && std::atoi(value) <= 1 ? 0 : 2;
    }();
    if (level >= minimum && level <= 4) {
        std::lock_guard<std::mutex> guard(mutex);
        const auto epoch = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        const auto monotonic = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        va_list arguments;
        va_start(arguments, format);
        va_list copy;
        va_copy(copy, arguments);
        const int size = std::vsnprintf(nullptr, 0, format, copy);
        va_end(copy);
        std::vector<char> buffer(size >= 0 ? static_cast<size_t>(size) + 1 : 1);
        if (size >= 0) std::vsnprintf(buffer.data(), buffer.size(), format, arguments);
        va_end(arguments);
        std::string content = size >= 0 ? buffer.data() : "<log formatting failed>";
        std::string escaped;
        for (char ch : content) {
            if (ch == '\n') escaped += "\\n";
            else if (ch == '\r') escaped += "\\r";
            else escaped += ch;
        }
#ifdef _WIN32
        const auto pid = GetCurrentProcessId();
#else
        const auto pid = getpid();
#endif
        std::cout << "LOG " << "VDIWE"[level] << ' ' << epoch << ' ' << tag
                  << " pid=" << pid << " seq=" << ++sequence << " mono_ns=" << monotonic
                  << ' ' << escaped << std::endl;
    }
    errno = savedErrno;
#ifdef _WIN32
    SetLastError(savedError);
#endif
}

extern "C" const char* logger_location_tag(const char* file, int line) {
    thread_local std::string location;
    location = std::string(file) + ':' + std::to_string(line);
    return location.c_str();
}
