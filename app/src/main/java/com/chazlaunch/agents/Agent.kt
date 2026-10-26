package com.chazlaunch.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How an agent gets onto the guest. Each maps to a command run in a guest login shell. */
@Serializable
enum class InstallMethod {
    @SerialName("nix") NIX,       // nix profile install nixpkgs#<pkg>
    @SerialName("npm") NPM,       // npm install -g <pkg>  (node from nix)
    @SerialName("pip") PIP,       // pipx install <pkg>
    @SerialName("cargo") CARGO,   // cargo install <pkg>
    @SerialName("curl") CURL,     // vendor's shell installer, piped
}

@Serializable
data class Agent(
    val id: String,
    @SerialName("name") val displayName: String,
    val description: String = "",
    val method: InstallMethod,
    /** Package/crate/module name, or full URL for CURL installers. */
    @SerialName("package") val pkg: String,
    /** Executable the guest exposes once installed (for the launcher grid). */
    @SerialName("bin") val binary: String = id,
    @SerialName("default") val enabledByDefault: Boolean = false,
    /** Overrides the derived install command when a vendor needs something odd. */
    @SerialName("command") val customCommand: String? = null,
) {
    /** The shell line executed inside the NixOS guest to install this agent. */
    fun installCommand(): String = customCommand ?: when (method) {
        InstallMethod.NIX -> "nix --extra-experimental-features 'nix-command flakes' " +
            "profile install nixpkgs#$pkg"
        InstallMethod.NPM -> "npm install -g $pkg"
        InstallMethod.PIP -> "pipx install $pkg"
        InstallMethod.CARGO -> "cargo install $pkg"
        InstallMethod.CURL -> "curl -fsSL $pkg | sh"
    }
}
