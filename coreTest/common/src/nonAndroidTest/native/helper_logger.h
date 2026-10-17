#pragma once
#include "datastore/logger.h"
#include <exception>
#include <stdexcept>

template<class Action> int runLoggedHelper(const char* tag, Action action) {
    LOGGER_I_TAGGABLE(tag, "Process started");
    int code;
    try { code = action(); }
    catch (const std::invalid_argument& error) {
        LOGGER_E_TAGGABLE(tag, "Arguments/command rejected: %s", error.what());
        code = 2;
    } catch (const std::exception& error) {
        LOGGER_E_TAGGABLE(tag, "Operation failed: %s", error.what());
        code = 4;
    }
    LOGGER_I_TAGGABLE(tag, "Process exiting code=%d", code);
    return code;
}
