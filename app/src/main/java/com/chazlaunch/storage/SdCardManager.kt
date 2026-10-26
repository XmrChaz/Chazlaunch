package com.chazlaunch.storage

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.chazlaunch.data.Prefs
import java.io.File

/**
 * Locates removable (SD card) storage and exposes app-specific directories on
 * it for the guest's /tmp and Nix cache. We use `getExternalFilesDirs()` which
 * returns an app-private folder on every mounted volume — writable with no
 * runtime permission and auto-cleaned on uninstall, so no root and no broad
 * storage grants are needed.
 */
class SdCardManager(private val context: Context) {

    data class Volume(val label: String, val path: File, val isRemovable: Boolean, val freeBytes: Long)

    /** All mounted volumes we can write to, primary first. */
    fun volumes(): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull().map { dir ->
            val vol = runCatching { sm.getStorageVolume(dir) }.getOrNull()
            Volume(
                label = vol?.getDescription(context) ?: dir.absolutePath,
                path = dir,
                isRemovable = vol?.isRemovable ?: false,
                freeBytes = runCatching { dir.usableSpace }.getOrDefault(0L),
            )
        }
    }

    /** The first removable volume, or null if the device has none mounted. */
    fun removableVolume(): Volume? = volumes().firstOrNull {
        it.isRemovable && Environment.getExternalStorageState(it.path) == Environment.MEDIA_MOUNTED
    }

    fun isAvailable(): Boolean = removableVolume() != null

    /** Host dir to bind as the guest /tmp, when SD offloading is enabled. */
    fun tmpDir(): File? =
        if (Prefs.sdCardEnabled) removableVolume()?.path?.resolve("chaz/tmp")?.apply { mkdirs() }
        else null

    /** Host dir to bind as the guest Nix download/cache dir. */
    fun cacheDir(): File? =
        if (Prefs.sdCardEnabled) removableVolume()?.path?.resolve("chaz/nix-cache")?.apply { mkdirs() }
        else null

    /** Toggle SD offloading; returns false if no removable volume is present. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && !isAvailable()) return false
        Prefs.sdCardEnabled = enabled
        return true
    }
}
