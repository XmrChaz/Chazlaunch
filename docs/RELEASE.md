# Cutting a functional release

The `Release (functional APK)` workflow (`.github/workflows/release.yml`)
produces an installable build with the compositor and NixOS guest wired up.

## Trigger

```sh
git tag v0.1.0
git push origin v0.1.0
```

or run it manually (Actions → *Release (functional APK)* → Run workflow) and
pass the tag. It creates/updates the GitHub Release for that tag and attaches:

- `nixos-aarch64-rootfs.tar.gz` — the guest root filesystem
- `chazlaunch-<tag>.apk` — the compositor-enabled debug-signed APK

The APK is baked (`-PchazRootfsUrl=…`) to download the rootfs asset from that
same release on first launch, so **install the APK, open it, and run the setup
wizard** — it fetches the rootfs, bootstraps Nix and installs the agents.

## Jobs

| Job | Does | Key tooling |
|-----|------|-------------|
| `natives` | cross-compiles `libwayland-server` + `libffi` for arm64 into `cpp/third_party`, and lays a patched Termux `proot`/`libtalloc` into `jniLibs` | NDK, meson, `ci/build-wayland.sh`, `ci/fetch-proot.sh` |
| `rootfs` | builds the NixOS aarch64 rootfs via `nixos-generators` under qemu, repacks to tar.gz, attaches to the release | Nix + qemu binfmt, `ci/pack-rootfs.sh` |
| `apk` | unpacks `natives`, builds `assembleDebug -Pchaz.native=true`, attaches the APK | AGP + NDK |

## Reproducing locally

```sh
export ANDROID_NDK_HOME=/path/to/ndk
./ci/build-wayland.sh          # -> app/src/main/cpp/third_party
./ci/fetch-proot.sh            # -> app/src/main/jniLibs/arm64-v8a
./ci/pack-rootfs.sh            # -> dist/nixos-aarch64-rootfs.tar.gz (needs nix+qemu)
./gradlew assembleDebug -Pchaz.native=true \
  -PchazRootfsUrl="https://…/nixos-aarch64-rootfs.tar.gz"
```

## Things that will need tuning on the first real run

These are the parts that depend on upstream details I can't exercise without a
runner; expect to pin/adjust versions on the first tagged build:

- **Upstream versions** — `WAYLAND_VERSION`, `LIBFFI_VERSION`,
  `WAYLAND_PROTOCOLS_VERSION` in `ci/build-wayland.sh`, and the Termux `proot`
  package name resolution in `ci/fetch-proot.sh` (override with `PROOT_DEB` /
  `TALLOC_DEB` if the pool index scrape misses).
- **proot linkage** — the patchelf soname rewrite assumes Termux proot links
  `libtalloc.so.2`. If a build ships a static proot instead, drop the libtalloc
  step (PRootManager already treats the separate loader as optional).
- **Rootfs size / build time** — a full NixOS closure with Firefox etc. is
  large and the qemu build is slow; most store paths come from
  `cache.nixos.org` (which has aarch64 binaries) so it's download-bound, but
  trim `configuration.nix` if it exceeds the release asset limit or the job
  timeout.
- **Wayland compatibility** — keyboard uses `NO_KEYMAP` and only `wl_shm`
  buffers are presented today (see `app/src/main/cpp/README.md`). GPU-only
  clients need the dmabuf path before they'll display.
