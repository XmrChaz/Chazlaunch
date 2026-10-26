#!/bin/sh
# Exports the environment a Wayland client needs to connect to the Chazlaunch
# compositor, then execs whatever command it is given. CompositorService runs
# GUI apps through a login shell that already sets these, so this is mostly for
# launching apps by hand from a guest terminal:
#   sh /tmp/start-wayland.sh firefox
set -u

export XDG_RUNTIME_DIR=/run/user/0
export WAYLAND_DISPLAY=wayland-0
export XDG_SESSION_TYPE=wayland
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM=wayland
export MOZ_ENABLE_WAYLAND=1
export SDL_VIDEODRIVER=wayland
export CLUTTER_BACKEND=wayland
export PATH="/root/.npm-global/bin:/root/.local/bin:/root/.cargo/bin:/run/current-system/sw/bin:$PATH"

exec "$@"
