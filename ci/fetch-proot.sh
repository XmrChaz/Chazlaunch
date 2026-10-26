#!/usr/bin/env bash
# Fetch a working Android-arm64 `proot` (+ its loader and libtalloc) from the
# Termux package repo and lay it into app/src/main/jniLibs/arm64-v8a as
# lib*.so files. Termux binaries are built for Android/bionic and use
# /system/bin/linker64, so they run inside our app process.
#
# patchelf rewrites the libtalloc soname (.so.2 -> .so) so Android's packager
# keeps it and the dynamic linker resolves it from nativeLibraryDir (which
# PRootManager puts on LD_LIBRARY_PATH).
#
# Requires: curl, dpkg-deb, patchelf. Run from the repo root.
set -euxo pipefail

ABI="arm64-v8a"
OUT="$PWD/app/src/main/jniLibs/$ABI"
WORK="$(mktemp -d)"
BASE="https://packages.termux.dev/apt/termux-main/pool/main"
mkdir -p "$OUT"
cd "$WORK"

find_deb() { # $1 = pool subpath, $2 = package filename prefix
    local idx; idx="$(curl -fsSL "$BASE/$1/")"
    echo "$idx" | grep -oE "${2}_[^\"]+_aarch64\.deb" | sort -V | tail -1
}

PROOT_DEB="${PROOT_DEB:-$(find_deb p/proot proot)}"
TALLOC_DEB="${TALLOC_DEB:-$(find_deb libt/libtalloc libtalloc)}"
echo "proot=$PROOT_DEB talloc=$TALLOC_DEB"

curl -fsSL "$BASE/p/proot/$PROOT_DEB" -o proot.deb
curl -fsSL "$BASE/libt/libtalloc/$TALLOC_DEB" -o talloc.deb
dpkg-deb -x proot.deb proot-root
dpkg-deb -x talloc.deb talloc-root

TP="data/data/com.termux/files/usr"
proot_bin="$(find proot-root/$TP -name proot -type f | head -1)"
loader_bin="$(find proot-root/$TP -path '*libexec/proot/loader' -type f | head -1 || true)"
talloc_lib="$(find talloc-root/$TP/lib -name 'libtalloc.so.*' -type f | head -1)"

cp "$talloc_lib" libtalloc.so
patchelf --set-soname libtalloc.so libtalloc.so
cp "$proot_bin" libproot.so
patchelf --replace-needed libtalloc.so.2 libtalloc.so libproot.so || true

install -m 0755 libproot.so  "$OUT/libproot.so"
install -m 0755 libtalloc.so "$OUT/libtalloc.so"
if [ -n "${loader_bin:-}" ] && [ -f "$loader_bin" ]; then
    install -m 0755 "$loader_bin" "$OUT/libproot_loader.so"
fi

echo "jniLibs/$ABI:"
ls -l "$OUT"
file "$OUT"/*.so || true
