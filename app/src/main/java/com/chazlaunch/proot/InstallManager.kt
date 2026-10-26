package com.chazlaunch.proot

import android.content.Context
import android.util.Log
import com.chazlaunch.agents.AgentCatalog
import com.chazlaunch.agents.AgentInstaller
import com.chazlaunch.data.Prefs
import com.chazlaunch.storage.SdCardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Drives the end-to-end first-launch install:
 *   1. download + unpack the NixOS aarch64 rootfs,
 *   2. drop in our configuration.nix / flake and the "container" tweaks,
 *   3. nixos-rebuild / nix profile bootstrap inside the guest,
 *   4. install the selected AI agents.
 *
 * Progress is a hot [StateFlow] the setup wizard observes.
 */
class InstallManager(private val context: Context) {

    private val _state = MutableStateFlow<Phase>(Phase.Idle)
    val state: StateFlow<Phase> = _state.asStateFlow()

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val pct: Int, val mbDone: Long, val mbTotal: Long) : Phase
        data class Extracting(val entry: String) : Phase
        data class Bootstrapping(val line: String) : Phase
        data class InstallingAgents(val current: String, val done: Int, val total: Int) : Phase
        data object Done : Phase
        data class Failed(val reason: String) : Phase
    }

    suspend fun run(selectedAgentIds: Set<String>) = withContext(Dispatchers.IO) {
        try {
            PRootManager.ensureExecutable()
            if (!Prefs.rootfsInstalled) {
                downloadAndExtractRootfs()
                writeNixConfig()
                Prefs.rootfsInstalled = true
            }
            if (!Prefs.nixBootstrapped) {
                bootstrapNix()
                Prefs.nixBootstrapped = true
            }
            installAgents(selectedAgentIds)
            Prefs.setupComplete = true
            _state.value = Phase.Done
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            _state.value = Phase.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Build-time URL (set by the release workflow) wins; else the fallback. */
    private val rootfsUrl: String
        get() = com.chazlaunch.BuildConfig.ROOTFS_URL.ifEmpty { ROOTFS_URL }

    private fun downloadAndExtractRootfs() {
        val conn = (URL(rootfsUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) error("rootfs HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        var read = 0L
        val counting = object : BufferedInputStream(conn.inputStream, 1 shl 16) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    read += n
                    val pct = if (total > 0) ((read * 100) / total).toInt() else 0
                    _state.value = Phase.Downloading(pct, read shr 20, total shr 20)
                }
                return n
            }
        }
        PRootManager.rootfs.mkdirs()
        GZIPInputStream(counting).use { gz ->
            TarExtractor.extract(gz, PRootManager.rootfs) { entry, _ ->
                _state.value = Phase.Extracting(entry)
            }
        }
    }

    /** Copy asset templates into the guest's /etc/nixos. */
    private fun writeNixConfig() {
        val etcNixos = PRootManager.rootfs.resolve("etc/nixos").apply { mkdirs() }
        listOf("configuration.nix", "flake.nix", "agents.nix", "chaz-tweaks.nix").forEach { name ->
            context.assets.open("nixos/$name").use { input ->
                etcNixos.resolve(name).outputStream().use { input.copyTo(it) }
            }
        }
        // Make sure resolv.conf points somewhere usable inside the guest.
        PRootManager.rootfs.resolve("etc/resolv.conf")
            .writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    private fun bootstrapNix() = runGuestScript("bootstrap.sh") { line ->
        _state.value = Phase.Bootstrapping(line)
    }

    private suspend fun installAgents(ids: Set<String>) {
        if (ids.isEmpty()) return
        val agents = AgentCatalog.load(context).filter { it.id in ids }
        val installer = AgentInstaller(context)
        agents.forEachIndexed { index, agent ->
            _state.value = Phase.InstallingAgents(agent.displayName, index + 1, agents.size)
            installer.install(agent)
            Prefs.installedAgents = Prefs.installedAgents + agent.id
        }
    }

    /** Run one of our bundled shell scripts inside the guest, streaming output. */
    private fun runGuestScript(assetName: String, onLine: (String) -> Unit) {
        val scriptPath = PRootManager.rootfs.resolve("tmp/$assetName").apply {
            parentFile?.mkdirs()
            context.assets.open("scripts/$assetName").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        val sd = SdCardManager(context)
        val invocation = PRootManager.buildProotCommand(
            command = listOf("/bin/sh", "/tmp/$assetName"),
            sdCardTmp = sd.tmpDir(),
            sdCardCache = sd.cacheDir(),
            loginShell = false,
        )
        val proc = PRootManager.start(invocation)
        proc.inputStream.bufferedReader().forEachLine(onLine)
        val code = proc.waitFor()
        scriptPath.delete()
        if (code != 0) error("$assetName exited with $code")
    }

    companion object {
        private const val TAG = "InstallManager"

        /**
         * A minimal NixOS aarch64 rootfs tarball (gzip). Swap for your own
         * mirror; the CI job in docs/ARCHITECTURE.md builds one with
         * `nix build .#rootfsTarball`. Must be tar.gz (not tar.xz) so the
         * in-app [TarExtractor] can read it without a native xz dependency.
         */
        const val ROOTFS_URL =
            "https://github.com/xmrchaz/chazlaunch/releases/download/rootfs-v1/nixos-aarch64-rootfs.tar.gz"
    }
}
