cmake_minimum_required(VERSION 3.18)
project(datastore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT APPLE)
    message(FATAL_ERROR "platform-macos/native requires macOS.")
endif()
enable_language(OBJCXX)

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../common/native"
    "${CMAKE_BINARY_DIR}/platform-common")

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp" "${CMAKE_CURRENT_SOURCE_DIR}/src/*.mm")
add_library(datastore SHARED ${SOURCES})
target_link_libraries(datastore PRIVATE datastore::platform_common)
target_include_directories(datastore PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

set_target_properties(datastore PROPERTIES
    OUTPUT_NAME "datastore"
    PREFIX "lib"
    SUFFIX ".dylib"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)

install(TARGETS datastore
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
