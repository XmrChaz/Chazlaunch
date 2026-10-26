#!/usr/bin/env bash
# Cross-compile libwayland-server (+ libffi) for Android arm64 and populate
# app/src/main/cpp/third_party so the compositor can be built with
# -Pchaz.native=true. Run from the repo root.
#
# Requires: $ANDROID_NDK_HOME, meson, ninja, autoconf/libtool, and host
# wayland-scanner build deps (libexpat1-dev). The release workflow installs
# these; see .github/workflows/release.yml.
set -euxo pipefail

NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
API="${ANDROID_API:-29}"
ABI="arm64-v8a"
TRIPLE="aarch64-linux-android"
HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
WORK="$(mktemp -d)"
OUT="$PWD/app/src/main/cpp/third_party"
PREFIX="$WORK/prefix"       # cross sysroot-ish install prefix (arm64)
mkdir -p "$PREFIX" "$OUT/host-tools" "$OUT/wayland/$ABI"

export PATH="$TOOLCHAIN/bin:$PATH"
# NOTE: do NOT export the cross CC/AR/... yet — step 1 builds the *host*
# wayland-scanner and must use the host compiler. The cross toolchain is set
# only for the arm64 steps (2 and 3) below.
CROSS_CC="$TOOLCHAIN/bin/${TRIPLE}${API}-clang"
CROSS_AR="$TOOLCHAIN/bin/llvm-ar"
CROSS_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
CROSS_STRIP="$TOOLCHAIN/bin/llvm-strip"

WAYLAND_VERSION="${WAYLAND_VERSION:-1.23.0}"
LIBFFI_VERSION="${LIBFFI_VERSION:-3.4.6}"
WLP_VERSION="${WAYLAND_PROTOCOLS_VERSION:-1.38}"

cd "$WORK"

# --- 1. host wayland-scanner (needs expat) --------------------------------
curl -fsSL "https://gitlab.freedesktop.org/wayland/wayland/-/releases/${WAYLAND_VERSION}/downloads/wayland-${WAYLAND_VERSION}.tar.xz" -o wayland.tar.xz
tar xf wayland.tar.xz
meson setup wl-host "wayland-${WAYLAND_VERSION}" \
    -Dscanner=true -Dlibraries=false -Ddocumentation=false -Dtests=false -Ddtd_validation=false
ninja -C wl-host
cp wl-host/src/wayland-scanner "$OUT/host-tools/wayland-scanner"

# From here on everything is a cross build for arm64.
export CC="$CROSS_CC" AR="$CROSS_AR" RANLIB="$CROSS_RANLIB" STRIP="$CROSS_STRIP"

# --- 2. libffi for arm64 (wayland-server depends on it) -------------------
curl -fsSL "https://github.com/libffi/libffi/releases/download/v${LIBFFI_VERSION}/libffi-${LIBFFI_VERSION}.tar.gz" -o libffi.tar.gz
tar xf libffi.tar.gz
pushd "libffi-${LIBFFI_VERSION}"
./configure --host="$TRIPLE" --prefix="$PREFIX" --enable-static --disable-shared \
    CC="$CC" AR="$AR" RANLIB="$RANLIB"
make -j"$(nproc)"
make install
popd

# --- 3. libwayland-server for arm64 (meson cross) -------------------------
cat > cross-arm64.ini <<EOF
[binaries]
c = '$CC'
ar = '$AR'
strip = '$STRIP'
pkgconfig = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[properties]
needs_exe_wrapper = true
EOF

export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
meson setup wl-arm64 "wayland-${WAYLAND_VERSION}" \
    --cross-file cross-arm64.ini \
    --prefix="$PREFIX" \
    --default-library=static \
    -Dscanner=false -Dlibraries=true -Ddocumentation=false -Dtests=false -Ddtd_validation=false
ninja -C wl-arm64
ninja -C wl-arm64 install

# --- 4. assemble third_party/ --------------------------------------------
mkdir -p "$OUT/wayland/$ABI/include" "$OUT/wayland/$ABI/lib"
cp -r "$PREFIX/include/." "$OUT/wayland/$ABI/include/"
cp "$PREFIX/lib/libwayland-server.a" "$OUT/wayland/$ABI/lib/"
cp "$PREFIX/lib/libffi.a" "$OUT/wayland/$ABI/lib/"

# wayland-protocols (xdg-shell.xml)
curl -fsSL "https://gitlab.freedesktop.org/wayland/wayland-protocols/-/releases/${WLP_VERSION}/downloads/wayland-protocols-${WLP_VERSION}.tar.xz" -o wlp.tar.xz
tar xf wlp.tar.xz
rm -rf "$OUT/wayland-protocols"
mkdir -p "$OUT/wayland-protocols/stable/xdg-shell"
cp "wayland-protocols-${WLP_VERSION}/stable/xdg-shell/xdg-shell.xml" \
   "$OUT/wayland-protocols/stable/xdg-shell/xdg-shell.xml"

echo "third_party populated:"
find "$OUT" -maxdepth 3 -type f | sort
