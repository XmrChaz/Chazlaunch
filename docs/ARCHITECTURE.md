# Architecture

## Processes

Chazlaunch runs in two Android processes:

| Process | Holds |
|---------|-------|
| default | `MainActivity` (the HOME launcher), Compose UI, `MainViewModel`, `InstallManager` |
| `:guest` | `CompositorService`, the native Wayland compositor, PRoot sessions, and every `WaylandWindowActivity` |

Splitting them means a guest/compositor crash can't take the launcher down, and
the `WaylandWindowActivity` windows share the loaded `libchazcompositor.so`
directly with the service (same process ⇒ no IPC for surfaces/input). The two
processes coordinate through `SharedPreferences` (`Prefs`) and
`startForegroundService` intents.

## The window-per-task mechanism

1. A Wayland client in the guest maps an `xdg_toplevel`.
2. The compositor fires `nativeOnToplevelCreated(id, title, appId)` → `Compositor.Listener`.
3. `CompositorService` launches a new `WaylandWindowActivity` with
   `FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK` and a unique
   `chaz://window/<id>` data URI. With `documentLaunchMode="always"`, Android
   gives each one its own task + Recents card.
4. The activity's `SurfaceView` surface is passed to `Compositor.attachSurface`.
   Native `ANativeWindow_fromSurface` + `ANativeWindow_lock/unlockAndPost`
   composite the client's committed `wl_shm` buffer into that Android task.
5. Touch/key events on the `SurfaceView` are forwarded via
   `Compositor.sendPointer*/sendKey` → `wl_pointer`/`wl_keyboard`.
6. Swiping the task away → `onDestroy` → `Compositor.requestClose` →
   `xdg_toplevel.close`. The client closing → `nativeOnToplevelClosed` →
   `finishAndRemoveTask`.

## Threading

The Wayland event loop is single-threaded. JNI calls arrive on Android threads
(UI, binder), so `jni_bridge.c` wraps each op in a heap struct and `chaz_post`s
it onto a self-pipe registered with the `wl_event_loop`; the loop drains and
executes them on the compositor thread, then flushes clients. Nothing touches
`wl_resource`s off-thread.

## PRoot invocation

`PRootManager.buildProotCommand` assembles:

```
libproot.so --kill-on-exit -r <rootfs> -0 -w /root --link2symlink --sysvipc -L \
  -b /dev:/dev -b /proc:/proc -b /sys:/sys \
  -b <tmp>:/tmp -b <nix-cache>:/root/.cache/nix \
  -b <wayland-socket-dir>:/run/user/0 \
  /usr/bin/env -i HOME=/root ... WAYLAND_DISPLAY=wayland-0 <command>
```

`proot` and its loader ship as `jniLibs` (`libproot.so`,
`libproot_loader.so`) because that's the only place Android keeps an
executable bit across installs.

## Producing the rootfs tarball

`InstallManager.ROOTFS_URL` points at a **gzip** tarball (not xz — the in-app
`TarExtractor` is pure-JVM and has no xz decoder). Build one from the flake:

```sh
# On an aarch64 builder (or with binfmt/qemu):
nix build .#toplevel            # from assets/nixos via the flake
# Assemble a rootfs from the system closure, then:
tar -C rootfs -czf nixos-aarch64-rootfs.tar.gz .
```

Upload it to the release referenced by `ROOTFS_URL`, or change the URL to your
own mirror. A proper NixOS rootfs already contains `nix`; `bootstrap.sh` then
installs the toolchain + GUI apps into the profile.

## Supplying the native prerequisites

- **libwayland-server (arm64)** → `app/src/main/cpp/third_party/` — see
  `app/src/main/cpp/README.md`. An example NDK meson cross file is at
  `docs/ndk-arm64.ini`.
- **proot binaries (arm64)** → `app/src/main/jniLibs/arm64-v8a/libproot.so`
  and `libproot_loader.so`. Build from
  [proot-me/proot](https://github.com/proot-me/proot) with the NDK, or reuse
  Termux's `proot` build. They are not committed (see `.gitignore`).

## Security posture

- Everything runs as the app's own uid; PRoot only *presents* uid 0 to the
  guest. No real privilege is gained.
- SD-card use is via app-private dirs — no `MANAGE_EXTERNAL_STORAGE`.
- Agent installers (`curl | sh`) run inside the guest sandbox; the catalog is
  editable so you can pin to vendored copies instead of piping from the network.
