# Native compositor build prerequisites

The compositor links `libwayland-server` statically. The NDK sysroot does not
ship it, so cross-compile it for `arm64-v8a` once and drop the results under
`third_party/` before building the APK. The layout CMake expects:

```
app/src/main/cpp/third_party/
├── host-tools/
│   └── wayland-scanner            # host build, used to generate xdg-shell glue
├── wayland-protocols/
│   └── stable/xdg-shell/xdg-shell.xml
└── wayland/arm64-v8a/
    ├── include/                   # wayland-server-core.h, wayland-server-protocol.h, …
    └── lib/
        ├── libwayland-server.a
        └── libffi.a
```

## Building libwayland-server for Android (meson)

```sh
# 1. Host wayland-scanner (needed to generate protocol code)
git clone https://gitlab.freedesktop.org/wayland/wayland
meson setup wayland/build-host wayland -Dscanner=true -Dlibraries=false
ninja -C wayland/build-host
cp wayland/build-host/src/wayland-scanner third_party/host-tools/

# 2. libffi for arm64 (wayland-server depends on it)
#    build with the NDK toolchain, install into $PREFIX

# 3. libwayland-server for arm64 with an NDK meson cross file
meson setup wayland/build-android wayland \
      --cross-file ndk-arm64.ini \
      -Dscanner=false -Ddocumentation=false -Dtests=false
ninja -C wayland/build-android
# copy the static lib + headers into third_party/wayland/arm64-v8a/

# 4. xdg-shell protocol
git clone https://gitlab.freedesktop.org/wayland/wayland-protocols \
      third_party/wayland-protocols
```

An example `ndk-arm64.ini` meson cross file and a `fetch-deps.sh` helper live in
`docs/`. Once `third_party/` is populated, `./gradlew assembleDebug` builds the
compositor as `libchazcompositor.so`.

## What the compositor implements

- `wl_compositor` v4, `wl_shm` (libwayland built-in), `wl_seat` v7
  (pointer/keyboard/touch), `xdg_wm_base` v3 (`xdg_surface` + `xdg_toplevel`,
  minimal `xdg_popup`).
- SHM buffer → `ANativeWindow` blit with ARGB/XRGB→RGBA conversion.
- A self-pipe task queue so JNI calls from Android threads run on the Wayland
  event-loop thread.

## Known limitations / next steps

- Keyboard uses `NO_KEYMAP` + evdev offset; wiring a real xkb keymap (shared via
  an fd) improves app compatibility.
- Only `wl_shm` buffers are presented. GPU clients using `linux-dmabuf` need an
  EGL/`AHardwareBuffer` import path.
- One `wl_pointer`/`wl_keyboard` focus is tracked globally; per-task focus
  routing is a planned refinement.
