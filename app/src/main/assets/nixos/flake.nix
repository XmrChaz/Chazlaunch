{
  description = "Chazlaunch NixOS guest (rootless, PRoot, Wayland-only)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
  };

  outputs = { self, nixpkgs, ... }:
    let
      system = "aarch64-linux";
    in
    {
      # `nixos-rebuild switch --flake /etc/nixos#chaz` rebuilds the guest.
      nixosConfigurations.chaz = nixpkgs.lib.nixosSystem {
        inherit system;
        modules = [ ./configuration.nix ];
      };

      # The system closure, handy for `nix build .#toplevel` when prebuilding
      # a rootfs tarball in CI.
      packages.${system}.toplevel =
        self.nixosConfigurations.chaz.config.system.build.toplevel;
    };
}
