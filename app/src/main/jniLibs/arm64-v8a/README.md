# jniLibs/arm64-v8a

Drop the PRoot runtime here before building a device APK:

- `libproot.so`        — the `proot` executable, renamed to a `lib*.so` so
  Android keeps its exec bit and extracts it on install.
- `libproot_loader.so` — PRoot's `loader` helper, same renaming.

Build them with the Android NDK from
<https://github.com/proot-me/proot> (needs `libtalloc`), or reuse Termux's
`proot` build for aarch64. They are intentionally **not committed** (see the
repo `.gitignore`) because they are prebuilt binary blobs.

`PRootManager` resolves them from `applicationInfo.nativeLibraryDir` and
`chmod`s them executable at install time.
