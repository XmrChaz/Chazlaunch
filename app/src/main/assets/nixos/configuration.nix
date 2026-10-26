# Base system for the Chazlaunch NixOS guest.
{ config, lib, pkgs, ... }:

{
  imports = [
    ./chaz-tweaks.nix   # rootless/PRoot + "no kernel" tweaks
    ./agents.nix        # toolchains the AI agents need (node/python/rust/…)
  ];

  networking.hostName = "chaz";
  time.timeZone = lib.mkDefault "UTC";
  i18n.defaultLocale = "C.UTF-8";

  # The login user the PRoot session drops into.
  users.users.chaz = {
    isNormalUser = true;
    extraGroups = [ "wheel" ];
    initialPassword = "chaz";
    shell = pkgs.bash;
  };
  security.sudo.wheelNeedsPassword = false;

  # Minimal Wayland-capable base. Keeps the rootfs small so the (qemu) build is
  # tractable; GUI apps like Firefox are installed later via `nix profile`.
  environment.systemPackages = with pkgs; [
    foot            # Wayland terminal (the launcher's default window)
    bashInteractive
    git curl        # needed by the agent installers
    wayland libxkbcommon
    dejavu_fonts    # one font so terminals/apps render text
  ];

  # D-Bus is needed by many GTK apps even without a full desktop.
  services.dbus.enable = true;

  # Point GUI clients at our compositor socket (bind-mounted at /run/user/0).
  environment.sessionVariables = {
    WAYLAND_DISPLAY = "wayland-0";
    XDG_RUNTIME_DIR = "/run/user/0";
    XDG_SESSION_TYPE = "wayland";
    MOZ_ENABLE_WAYLAND = "1";
    QT_QPA_PLATFORM = "wayland";
    GDK_BACKEND = "wayland";
  };
}
