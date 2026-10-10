cmake_minimum_required(VERSION 3.18)
project(datastore_platform_common LANGUAGES CXX)

add_library(datastore_platform_common OBJECT src/native_bridge.cpp src/logger.cpp)
add_library(datastore::platform_common ALIAS datastore_platform_common)
set_target_properties(datastore_platform_common PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_features(datastore_platform_common PUBLIC cxx_std_17)

if(MSVC)
    # JDKs can preload an older msvcp140.dll. Use runtime mutex initialization
    # to remain compatible with that runtime (microsoft/STL#3824).
    target_compile_definitions(datastore_platform_common PUBLIC _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
endif()

if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME not set")
endif()
file(TO_CMAKE_PATH "$ENV{JAVA_HOME}" DATASTORE_JAVA_HOME)
if(WIN32)
    set(DATASTORE_JNI_PLATFORM win32)
elseif(APPLE)
    set(DATASTORE_JNI_PLATFORM darwin)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(DATASTORE_JNI_PLATFORM linux)
else()
    message(FATAL_ERROR "Unsupported desktop platform")
endif()

target_include_directories(datastore_platform_common PUBLIC
    "${DATASTORE_JAVA_HOME}/include"
    "${DATASTORE_JAVA_HOME}/include/${DATASTORE_JNI_PLATFORM}"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
find_package(Threads REQUIRED)
target_link_libraries(datastore_platform_common PUBLIC Threads::Threads)
