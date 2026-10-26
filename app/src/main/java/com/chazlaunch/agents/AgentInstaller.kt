package com.chazlaunch.agents

import android.content.Context
import android.util.Log
import com.chazlaunch.proot.PRootManager
import com.chazlaunch.storage.SdCardManager

/** Runs an [Agent]'s install command inside the NixOS guest via PRoot. */
class AgentInstaller(private val context: Context) {

    /** @return the install log (stdout+stderr merged). Throws on non-zero exit. */
    fun install(agent: Agent): String {
        val sd = SdCardManager(context)
        // A login shell so the Nix profile PATH (node, cargo, pipx) is set up.
        val invocation = PRootManager.buildProotCommand(
            command = listOf(agent.installCommand()),
            sdCardTmp = sd.tmpDir(),
            sdCardCache = sd.cacheDir(),
            loginShell = true,
        )
        val proc = PRootManager.start(invocation)
        val log = StringBuilder()
        proc.inputStream.bufferedReader().forEachLine { line ->
            log.appendLine(line)
            Log.d(TAG, "[${agent.id}] $line")
        }
        val code = proc.waitFor()
        if (code != 0) error("${agent.displayName} install failed (exit $code):\n$log")
        return log.toString()
    }

    companion object {
        private const val TAG = "AgentInstaller"
    }
}
