# Toolchains the AI coding agents install into. Having these in the system
# profile means `npm i -g`, `pipx install`, `cargo install` and the various
# curl installers all work out of the box inside the guest.
{ config, lib, pkgs, ... }:

{
  environment.systemPackages = with pkgs; [
    # Node (claude, codex, grok, copilot, kimi, gemini, …)
    nodejs_22
    nodePackages.npm

    # Python + pipx (aider and friends)
    python3
    python3Packages.pipx

    # Rust (cargo-installed agents)
    cargo
    rustc

    # Go (some agents ship as Go installers)
    go

    # Agents available directly from nixpkgs
    crush          # charmbracelet coding agent
    oh-my-posh     # "omp"

    # Common build deps for native npm/pip modules
    gcc
    gnumake
    pkg-config
    cacert         # TLS roots for the curl installers
  ];

  # Make globally-installed npm/pipx/cargo bins land on PATH for all users.
  environment.variables = {
    NPM_CONFIG_PREFIX = "/root/.npm-global";
    PATH = [
      "/root/.npm-global/bin"
      "/root/.local/bin"
      "/root/.cargo/bin"
      "/root/.nix-profile/bin"
    ];
  };
}
