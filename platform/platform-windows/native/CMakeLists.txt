cmake_minimum_required(VERSION 3.18)
project(datastore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT WIN32 OR NOT MSVC)
    message(FATAL_ERROR "platform-windows/native requires Windows and MSVC.")
endif()

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../common/native"
    "${CMAKE_BINARY_DIR}/platform-common")

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
add_library(datastore SHARED ${SOURCES})
target_link_libraries(datastore PRIVATE datastore::platform_common)
target_include_directories(datastore PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

target_compile_definitions(datastore PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
set_target_properties(datastore PROPERTIES
    OUTPUT_NAME "datastore"
    PREFIX ""
    SUFFIX ".dll"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
foreach(CONFIG DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set_target_properties(datastore PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY_${CONFIG} "${CMAKE_BINARY_DIR}"
        LIBRARY_OUTPUT_DIRECTORY_${CONFIG} "${CMAKE_BINARY_DIR}"
        ARCHIVE_OUTPUT_DIRECTORY_${CONFIG} "${CMAKE_BINARY_DIR}"
    )
endforeach()

install(TARGETS datastore
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
