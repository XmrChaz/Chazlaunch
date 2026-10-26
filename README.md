# Chazlaunch

A **home-screen launcher APK** (Kotlin + Jetpack Compose) that runs a full
**NixOS (aarch64)** userland **rootless, under PRoot**, and acts as its **own
Wayland compositor** — so Linux GUI apps open as **native Android tasks in the
Recents switcher**, one task per window.

```
┌──────────────────────────────────────────────────────────────┐
│ Android (no root)                                              │
│                                                                │
│  ┌───────────────┐        ┌───────────────────────────────┐   │
│  │ MainActivity  │        │  :guest process               │   │
│  │ (HOME launcher│        │                                │   │
│  │  + menu, Compose)      │  CompositorService (foreground)│   │
│  └──────┬────────┘        │   ├─ native Wayland compositor │   │
│         │ launchApp()     │   │   (libchazcompositor.so)   │   │
│         └────────────────▶│   │     wl_compositor/shm/seat │   │
│                           │   │     xdg_shell              │   │
│                           │   └─ PRoot ─▶ NixOS aarch64    │   │
│                           │        rootfs (nix, foot,      │   │
│                           │        firefox, agents…)       │   │
│                           │                                │   │
│   one Android task  ◀─────┤  per mapped xdg_toplevel:      │   │
│   per Linux window        │  WaylandWindowActivity +       │   │
│   (Recents)               │  SurfaceView ⇄ ANativeWindow   │   │
│                           └───────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

## What it does

- **Launcher / home.** `MainActivity` registers `CATEGORY_HOME`, so Chazlaunch
  can be the device's default launcher. Compose draws a menu/app grid, a
  first-launch setup wizard, and an options screen.
- **Rootless NixOS under PRoot.** No root, no `su`, no custom kernel. `proot`
  (shipped as a native lib) ptrace-chroots into an unpacked NixOS aarch64
  rootfs. See `PRootManager`.
- **Its own Wayland compositor.** A small native compositor
  (`app/src/main/cpp`) built on `libwayland-server` exposes a `wayland-0` socket
  that is bind-mounted into the guest. It implements `wl_compositor`, `wl_shm`,
  `wl_seat`, and `xdg_shell`.
- **One Android task per Linux window.** Each mapped `xdg_toplevel` is surfaced
  as a fresh `WaylandWindowActivity` launched with
  `FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK` and
  `documentLaunchMode="always"`, so every Linux window gets its own Recents
  card. Its `SurfaceView` surface is handed to the compositor and the client's
  buffers are composited straight into it; touch/keys are forwarded back.
- **First-launch install wizard** that downloads + unpacks the rootfs,
  bootstraps Nix, and installs your chosen **AI agents**.
- **SD-card offload** for `/tmp` and the Nix cache, with no extra permissions.
- **NixOS tweaks**, including disabling kernel packages and kernel-module
  updates (`boot.isContainer`).

## AI agents

The install wizard reads `app/src/main/assets/agents.json`. Defaults on:
**Claude Code, opencode, OpenAI Codex, Grok, GitHub Copilot, Crush,
Cursor Agent, Kimi Code**. Also bundled (off by default): Gemini CLI, Aider,
Goose, Oh My Posh (`omp`), and placeholder entries for `agy`, `pi`, `ori`,
`muse` — edit their `package`/`method` in `agents.json` to match the real
upstream source. Install methods supported: `npm`, `pip` (pipx), `cargo`,
`nix`, and `curl` installer scripts. All run inside the guest via
`AgentInstaller`.

## SD card (Options)

`SdCardManager` finds removable volumes through `getExternalFilesDirs()` — an
app-private folder on each volume, writable with **no runtime permission**. When
"Mount SD card" is on, PRoot binds `chaz/tmp` → guest `/tmp` and
`chaz/nix-cache` → guest Nix cache, moving the heavy, churny data off internal
storage.

## NixOS tweaks (Options → NixOS tweaks)

`assets/nixos/chaz-tweaks.nix` is applied to the guest:

- **Disable kernel packages & module updates** — `boot.isContainer = true`
  drops the kernel, initrd, bootloader and module tree from the system closure
  and stops `nixos-rebuild` from building/upgrading modules (there is no real
  kernel under PRoot). `boot.kernelModules`/`extraModulePackages` are forced
  empty.
- `nix.settings.sandbox = false` (PRoot can't `CLONE_NEWUSER`), `nix-command`
  + `flakes` on.
- Documentation/man/info off, swap + zram off, `services.udev`/firewall off.
- Working in-guest DNS (`resolv.conf`), Wayland session env vars.

The toggle re-runs `nixos-rebuild switch --flake /etc/nixos#chaz`.

## Building

Requires the Android SDK + NDK and a cross-compiled `libwayland-server`
(the NDK doesn't ship it). See **`app/src/main/cpp/README.md`** for the
`third_party/` layout and the meson build, and **`docs/ARCHITECTURE.md`** for
how the rootfs tarball is produced.

```sh
# 1. populate app/src/main/cpp/third_party/ (see cpp/README.md)
# 2. ship proot binaries as jniLibs (see docs/ARCHITECTURE.md)
./gradlew assembleDebug
```

> The Gradle wrapper, Compose/Kotlin sources, native compositor, Nix configs
> and install scripts are all here. The two things that must be supplied before
> a device build are the **vendored `libwayland-server`** and the **`proot`
> native binaries**; both are documented, with build commands, in the files
> above. These are prebuilt blobs, deliberately not committed.

## Layout

| Path | What |
|------|------|
| `app/src/main/java/com/chazlaunch/` | Launcher UI, PRoot runtime, compositor bridge, agents, storage |
| `app/src/main/cpp/` | Native Wayland compositor (C) + JNI + CMake |
| `app/src/main/assets/nixos/` | `configuration.nix`, `flake.nix`, `agents.nix`, `chaz-tweaks.nix` |
| `app/src/main/assets/scripts/` | `bootstrap.sh`, `install-agents.sh`, `mount-sdcard.sh`, `start-wayland.sh` |
| `app/src/main/assets/agents.json` | AI-agent catalog read by the install wizard |
| `docs/` | Architecture, NDK cross file, dependency fetch helper |

## Status & limitations

This is a working architecture and a complete, buildable project skeleton; a few
native pieces are foundations with clearly-marked next steps (xkb keymap, GPU
`linux-dmabuf` buffers, per-task input focus). See `app/src/main/cpp/README.md`.
