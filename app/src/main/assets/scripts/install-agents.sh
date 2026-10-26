#!/bin/sh
# Optional helper: install every agent id passed as an argument, using the same
# rules as agents.json. The app normally drives installs through AgentInstaller
# (one command per agent), but this is handy for re-running by hand inside a
# guest shell: `sh /tmp/install-agents.sh claude codex crush`.
set -u

export HOME=/root
export NIX_CONFIG="experimental-features = nix-command flakes"
export PATH="/root/.npm-global/bin:/root/.local/bin:/root/.cargo/bin:$PATH"

install_one() {
    case "$1" in
        claude)       npm install -g @anthropic-ai/claude-code ;;
        opencode)     curl -fsSL https://opencode.ai/install | sh ;;
        codex)        npm install -g @openai/codex ;;
        grok)         npm install -g @xai-official/grok ;;
        copilot)      npm_config_ignore_scripts=false npm install -g @github/copilot ;;
        crush)        nix profile install nixpkgs#crush ;;
        cursor-agent) curl -fsSL https://cursor.com/install | sh ;;
        kimi)         npm install -g @moonshot-ai/kimi-code ;;
        gemini)       npm install -g @google/gemini-cli ;;
        aider)        pipx install aider-chat ;;
        goose)        curl -fsSL https://github.com/block/goose/releases/download/stable/download_cli.sh | sh ;;
        omp)          nix profile install nixpkgs#oh-my-posh ;;
        *)            echo "[chaz] unknown/unmapped agent '$1' — edit agents.json"; return 1 ;;
    esac
}

for agent in "$@"; do
    echo "[chaz] installing $agent…"
    install_one "$agent" && echo "[chaz] $agent ok" || echo "[chaz] $agent FAILED"
done
