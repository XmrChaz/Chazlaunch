#!/bin/sh
# Runs once inside the freshly-unpacked NixOS guest (as root, via PRoot).
# Sets up Nix, installs the core toolchain the launcher + agents need, and
# applies the Chazlaunch NixOS tweaks.
set -eu

echo "[chaz] bootstrapping Nix guest…"

export NIX_CONFIG="experimental-features = nix-command flakes
sandbox = false"
export HOME=/root
export USER=root
# No nix-daemon under PRoot — operate the store directly as root.
export NIX_REMOTE=

# Find a usable CA bundle for TLS (NixOS path first, then the nix store copy).
for c in /etc/ssl/certs/ca-bundle.crt /etc/ssl/certs/ca-certificates.crt \
         "$(ls -1 /nix/store/*-nss-cacert-*/etc/ssl/certs/ca-bundle.crt 2>/dev/null | head -1)"; do
    if [ -n "$c" ] && [ -r "$c" ]; then NIX_SSL_CERT_FILE="$c"; break; fi
done
export NIX_SSL_CERT_FILE="${NIX_SSL_CERT_FILE:-/etc/ssl/certs/ca-bundle.crt}"
export SSL_CERT_FILE="$NIX_SSL_CERT_FILE"

# Nix needs its state/profile dirs to exist in standalone mode.
mkdir -p /nix/var/nix/profiles/per-user/root /nix/var/nix/gcroots/per-user/root 2>/dev/null || true

echo "[chaz] nix: $(command -v nix || echo MISSING)  NIX_REMOTE='${NIX_REMOTE}'  cert=$NIX_SSL_CERT_FILE"
if ! command -v nix >/dev/null 2>&1; then
    echo "[chaz] FATAL: nix not found in the rootfs PATH ($PATH)"; exit 1
fi

# DNS inside the guest.
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf 2>/dev/null || true

# npm global prefix on PATH, no root scripts blocked.
mkdir -p /root/.npm-global /root/.local/bin /root/.cargo/bin
printf 'prefix=/root/.npm-global\n' > /root/.npmrc

echo "[chaz] installing core toolchain into the default profile…"
# These make every agent install method (npm/pipx/cargo/curl) work afterwards,
# plus the Wayland terminal (foot) the launcher opens for CLI agents.
nix profile install \
    nixpkgs#nodejs_22 \
    nixpkgs#python3 \
    nixpkgs#python3Packages.pipx \
    nixpkgs#cargo \
    nixpkgs#rustc \
    nixpkgs#go \
    nixpkgs#git \
    nixpkgs#curl \
    nixpkgs#foot \
    nixpkgs#dbus \
    nixpkgs#cacert \
    nixpkgs#wayland \
    nixpkgs#libxkbcommon \
    || echo "[chaz] warning: some core packages failed to install"

# Try a full NixOS activation if the rootfs supports it (optional; the profile
# install above is enough for day-to-day use under PRoot).
if command -v nixos-rebuild >/dev/null 2>&1; then
    echo "[chaz] applying NixOS configuration (tweaks: no kernel/modules)…"
    nixos-rebuild switch --flake /etc/nixos#chaz --no-reexec 2>&1 | tail -n 20 || \
        echo "[chaz] nixos-rebuild skipped (expected under PRoot without systemd PID1)"
fi

echo "[chaz] bootstrap complete."
