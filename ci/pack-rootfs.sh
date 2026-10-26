#!/usr/bin/env bash
# Build the NixOS aarch64 guest rootfs and emit a gzip tarball the in-app
# TarExtractor can unpack. Uses nixos-generators' "lxc" format (a plain rootfs)
# and cross-builds for aarch64 via the binary cache + qemu binfmt.
#
# Requires: nix (flakes), qemu-user-static + binfmt registered, and
# extra-platforms = aarch64-linux in nix.conf. The release workflow sets these.
# Run from the repo root. Output: $PWD/dist/nixos-aarch64-rootfs.tar.gz
set -euxo pipefail

CONFIG="$PWD/app/src/main/assets/nixos/configuration.nix"
DIST="$PWD/dist"
mkdir -p "$DIST"

# nixos-generators drives the eval with its own format modules + our config.
RESULT="$(nix --extra-experimental-features 'nix-command flakes' \
    run github:nix-community/nixos-generators -- \
    --format lxc \
    --system aarch64-linux \
    --configuration "$CONFIG")"

echo "generator output: $RESULT"

# The lxc format yields a rootfs tarball (tar.xz). Normalise to tar.gz.
TMP="$(mktemp -d)"
tar -C "$TMP" -xf "$RESULT"
tar -C "$TMP" -czf "$DIST/nixos-aarch64-rootfs.tar.gz" .
ls -lh "$DIST/nixos-aarch64-rootfs.tar.gz"
