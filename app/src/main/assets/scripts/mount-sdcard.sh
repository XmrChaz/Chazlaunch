#!/bin/sh
# Reference for how the SD card is wired into the guest. The actual bind mounts
# are done by PRoot (-b host:guest) from PRootManager.buildProotCommand when the
# "Mount SD card" option is on; this script just documents/verifies the layout
# and is safe to run inside the guest to check it took effect.
set -u

echo "[chaz] SD card bind check:"
for p in /tmp /root/.cache/nix; do
    if mountpoint -q "$p" 2>/dev/null || [ -d "$p" ]; then
        df -h "$p" 2>/dev/null | tail -n1
    fi
done

# Nix is told to use the (SD-backed) cache dir for downloads/temporary builds.
export TMPDIR=/tmp
export XDG_CACHE_HOME=/root/.cache
echo "[chaz] TMPDIR=$TMPDIR  nix cache=/root/.cache/nix"
