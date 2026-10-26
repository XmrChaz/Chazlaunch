# Chazlaunch tweaks for running NixOS rootless under PRoot.
#
# PRoot gives us a userspace chroot with NO real kernel, NO bootloader and NO
# module loading, so anything that builds or manages those is pure waste (and
# usually fails). These options strip the system down to "a userland that boots
# a Wayland session" and keep the Nix store small.
{ config, lib, pkgs, ... }:

{
  ######################################################################
  # Disable kernel packages & kernel module handling (per Chazlaunch spec)
  ######################################################################
  # boot.isContainer flips NixOS into "no kernel" mode: it drops the kernel,
  # initrd, bootloader AND kernel-module tree/services from the system closure,
  # and stops nixos-rebuild from ever building or upgrading modules. This is the
  # single switch that satisfies "disable kernel packages and kernel module
  # updates" — the mkForce lines below just keep it that way if another imported
  # module tries to turn any of it back on.
  boot.isContainer = lib.mkDefault true;

  boot.loader.grub.enable = lib.mkForce false;
  boot.loader.systemd-boot.enable = lib.mkForce false;
  boot.kernelModules = lib.mkForce [ ];
  boot.extraModulePackages = lib.mkForce [ ];

  ######################################################################
  # Nix: PRoot can't sandbox builds (no CLONE_NEWUSER); enable flakes.
  ######################################################################
  nix.settings = {
    sandbox = lib.mkForce false;
    experimental-features = [ "nix-command" "flakes" ];
    cores = 0;
  };
  nix.channel.enable = true;
  nixpkgs.config.allowUnfree = true;

  ######################################################################
  # Trim everything a phone-side container does not need.
  ######################################################################
  documentation.enable = lib.mkDefault false;
  documentation.nixos.enable = lib.mkDefault false;
  documentation.man.enable = lib.mkDefault false;
  documentation.info.enable = lib.mkDefault false;

  # No swap / zram inside a guest — Android owns memory management.
  swapDevices = lib.mkForce [ ];
  zramSwap.enable = lib.mkForce false;

  # No real firewall under PRoot (there is no netfilter to program).
  networking.firewall.enable = lib.mkForce false;

  # DNS that works inside the guest (Android's resolver isn't visible).
  networking.nameservers = [ "1.1.1.1" "8.8.8.8" ];
  environment.etc."resolv.conf".text = ''
    nameserver 1.1.1.1
    nameserver 8.8.8.8
  '';

  fonts.fontDir.enable = true;

  system.stateVersion = lib.mkDefault "24.11";
}
