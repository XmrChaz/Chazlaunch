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

  # Wayland session essentials + a couple of GUI apps for the launcher grid.
  environment.systemPackages = with pkgs; [
    # terminals / shell
    foot bashInteractive git curl wget openssh
    # Wayland client libs & helpers (clients talk to the Chazlaunch compositor)
    wayland wayland-utils libxkbcommon
    # a few GUI apps to prove the window-per-task model
    firefox
    nautilus
    gnome-text-editor
    # fonts so GUI apps render text
    dejavu_fonts noto-fonts
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
