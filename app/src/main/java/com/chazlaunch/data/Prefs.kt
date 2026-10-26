package com.chazlaunch.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin, synchronous preference store shared across both processes
 * (`MODE_MULTI_PROCESS` is deprecated but still works for the handful of
 * flags the launcher and the `:guest` process both read).
 */
object Prefs {
    private const val FILE = "chaz_prefs"

    const val KEY_SETUP_COMPLETE = "setup_complete"
    const val KEY_ROOTFS_INSTALLED = "rootfs_installed"
    const val KEY_NIX_BOOTSTRAPPED = "nix_bootstrapped"
    const val KEY_SDCARD_ENABLED = "sdcard_enabled"
    const val KEY_SDCARD_TREE_URI = "sdcard_tree_uri"
    const val KEY_SELECTED_AGENTS = "selected_agents"      // comma-joined ids
    const val KEY_INSTALLED_AGENTS = "installed_agents"    // comma-joined ids
    const val KEY_COMPOSITOR_SCALE = "compositor_scale"    // float, default 1.0
    const val KEY_DISABLE_KERNEL_PKGS = "nix_disable_kernel"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        @Suppress("DEPRECATION")
        prefs = context.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS)
    }

    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(v) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, v).apply()

    var rootfsInstalled: Boolean
        get() = prefs.getBoolean(KEY_ROOTFS_INSTALLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ROOTFS_INSTALLED, v).apply()

    var nixBootstrapped: Boolean
        get() = prefs.getBoolean(KEY_NIX_BOOTSTRAPPED, false)
        set(v) = prefs.edit().putBoolean(KEY_NIX_BOOTSTRAPPED, v).apply()

    var sdCardEnabled: Boolean
        get() = prefs.getBoolean(KEY_SDCARD_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_SDCARD_ENABLED, v).apply()

    var sdCardTreeUri: String?
        get() = prefs.getString(KEY_SDCARD_TREE_URI, null)
        set(v) = prefs.edit().putString(KEY_SDCARD_TREE_URI, v).apply()

    var disableKernelPackages: Boolean
        get() = prefs.getBoolean(KEY_DISABLE_KERNEL_PKGS, true)  // default on, per spec
        set(v) = prefs.edit().putBoolean(KEY_DISABLE_KERNEL_PKGS, v).apply()

    var compositorScale: Float
        get() = prefs.getFloat(KEY_COMPOSITOR_SCALE, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_COMPOSITOR_SCALE, v).apply()

    var selectedAgents: Set<String>
        get() = prefs.getString(KEY_SELECTED_AGENTS, "")!!.splitCsv()
        set(v) = prefs.edit().putString(KEY_SELECTED_AGENTS, v.joinToString(",")).apply()

    var installedAgents: Set<String>
        get() = prefs.getString(KEY_INSTALLED_AGENTS, "")!!.splitCsv()
        set(v) = prefs.edit().putString(KEY_INSTALLED_AGENTS, v.joinToString(",")).apply()

    private fun String.splitCsv(): Set<String> =
        split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
