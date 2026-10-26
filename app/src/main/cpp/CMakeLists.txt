cmake_minimum_required(VERSION 3.22.1)
project(chazcompositor C)

set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# libwayland-server for Android.
#
# The NDK sysroot does NOT ship libwayland, so it must be cross-compiled for
# arm64-v8a and placed under third_party/ before building. See cpp/README.md
# for the exact meson invocation. We link it statically so the APK is
# self-contained.
# ---------------------------------------------------------------------------
set(WAYLAND_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wayland/${ANDROID_ABI})
set(WAYLAND_PROTOCOLS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wayland-protocols)

# Generate the xdg-shell server glue with the vendored wayland-scanner.
find_program(WAYLAND_SCANNER wayland-scanner
        PATHS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/host-tools NO_DEFAULT_PATH)
set(XDG_SHELL_XML ${WAYLAND_PROTOCOLS}/stable/xdg-shell/xdg-shell.xml)
set(GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/gen)
file(MAKE_DIRECTORY ${GEN_DIR})

add_custom_command(
        OUTPUT ${GEN_DIR}/xdg-shell-protocol.c ${GEN_DIR}/xdg-shell-server-protocol.h
        COMMAND ${WAYLAND_SCANNER} private-code ${XDG_SHELL_XML} ${GEN_DIR}/xdg-shell-protocol.c
        COMMAND ${WAYLAND_SCANNER} server-header ${XDG_SHELL_XML} ${GEN_DIR}/xdg-shell-server-protocol.h
        DEPENDS ${XDG_SHELL_XML}
        COMMENT "Generating xdg-shell protocol code")

add_library(chazcompositor SHARED
        jni_bridge.c
        compositor.c
        ${GEN_DIR}/xdg-shell-protocol.c)

target_include_directories(chazcompositor PRIVATE
        ${WAYLAND_ROOT}/include
        ${GEN_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR})

target_link_directories(chazcompositor PRIVATE ${WAYLAND_ROOT}/lib)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(chazcompositor
        ${WAYLAND_ROOT}/lib/libwayland-server.a
        ${WAYLAND_ROOT}/lib/libffi.a
        ${log-lib}
        ${android-lib})
