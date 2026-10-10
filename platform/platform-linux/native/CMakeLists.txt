cmake_minimum_required(VERSION 3.18)
project(datastore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR "platform-linux/native requires Linux.")
endif()

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../common/native"
    "${CMAKE_BINARY_DIR}/platform-common")

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
add_library(datastore SHARED ${SOURCES})
target_link_libraries(datastore PRIVATE datastore::platform_common)
target_include_directories(datastore PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

set_target_properties(datastore PROPERTIES
    OUTPUT_NAME "datastore"
    PREFIX "lib"
    SUFFIX ".so"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)

install(TARGETS datastore
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
