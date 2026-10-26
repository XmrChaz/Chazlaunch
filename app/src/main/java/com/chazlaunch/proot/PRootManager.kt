package com.chazlaunch.proot

import android.content.Context
import android.system.Os
import android.util.Log
import com.chazlaunch.data.Prefs
import java.io.File

/**
 * Owns the on-disk layout of the guest and builds PRoot command lines.
 *
 * PRoot gives us a chroot-like environment with no root and no kernel support:
 * it ptrace-intercepts syscalls and rewrites path arguments so a full NixOS
 * aarch64 rootfs believes it is installed at `/`. We ship the `proot` binary
 * and its `libtalloc`/loader as native libs (jniLibs) because that is the only
 * place Android lets us keep an executable with the exec bit across installs.
 */
object PRootManager {
    private const val TAG = "PRootManager"

    lateinit var filesDir: File private set
    lateinit var nativeLibDir: File private set

    /** The guest root filesystem (an unpacked NixOS aarch64 tarball). */
    val rootfs: File get() = File(filesDir, "nixos-rootfs")

    /** Writable runtime dir bind-mounted to the guest as /run and friends. */
    val runtimeDir: File get() = File(filesDir, "runtime")

    /** Where the Wayland socket lives on the Android side. */
    val waylandSocketDir: File get() = File(runtimeDir, "wayland")

    private val prootBin: File get() = File(nativeLibDir, "libproot.so")
    private val prootLoader: File get() = File(nativeLibDir, "libproot_loader.so")

    fun init(context: Context) {
        filesDir = context.filesDir
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        runtimeDir.mkdirs()
        waylandSocketDir.mkdirs()
    }

    fun isReady(): Boolean = Prefs.rootfsInstalled && rootfs.resolve("etc").exists()

    /**
     * Build the argv for running [command] inside the guest.
     *
     * [sdCardTmp] / [sdCardCache], when non-null, are host directories on a
     * mounted SD card that we bind to the guest's /tmp and /nix cache so the
     * heavy, churny data lives on removable storage (see SdCardManager).
     */
    fun buildProotCommand(
        command: List<String>,
        waylandDisplay: String = "wayland-0",
        extraBinds: List<Bind> = emptyList(),
        sdCardTmp: File? = null,
        sdCardCache: File? = null,
        loginShell: Boolean = false,
    ): ProotInvocation {
        val binds = buildList {
            // Kernel pseudo-filesystems — proot forwards these to the real ones.
            add(Bind("/dev", "/dev"))
            add(Bind("/dev/urandom", "/dev/random"))
            add(Bind("/proc", "/proc"))
            add(Bind("/sys", "/sys"))
            add(Bind("/dev/pts", "/dev/pts"))
            // A tmpfs-ish writable area; SD card if the user enabled it.
            add(Bind((sdCardTmp ?: File(runtimeDir, "tmp")).ensure().absolutePath, "/tmp"))
            // Persist the Nix store cache / download cache off to the SD card.
            sdCardCache?.let { add(Bind(it.ensure().absolutePath, "/root/.cache/nix")) }
            // The compositor's Wayland socket, surfaced to the guest's XDG_RUNTIME_DIR.
            add(Bind(waylandSocketDir.absolutePath, "/run/user/0"))
            addAll(extraBinds)
        }

        val env = buildList {
            add("PROOT_TMP_DIR=${File(runtimeDir, "proot-tmp").ensure().absolutePath}")
            add("PROOT_LOADER=${prootLoader.absolutePath}")
            add("HOME=/root")
            add("USER=root")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=/run/current-system/sw/bin:/root/.nix-profile/bin:/usr/bin:/bin")
            add("XDG_RUNTIME_DIR=/run/user/0")
            add("WAYLAND_DISPLAY=$waylandDisplay")
            // PRoot needs to know where ptrace is unavailable (Android ≥ 10 is fine
            // in app's own process); seccomp off so nix-daemon's syscalls pass.
            add("PROOT_NO_SECCOMP=1")
        }

        val argv = buildList {
            add(prootBin.absolutePath)
            add("--kill-on-exit")
            add("-r"); add(rootfs.absolutePath)
            add("-0")                          // present as uid 0 inside guest
            add("-w"); add("/root")
            add("--link2symlink")              // let nix store hardlinks work
            add("--sysvipc")
            add("-L")                          // correct st_dev for overlay-ish fs
            binds.forEach { add("-b"); add("${it.host}:${it.guest}") }
            add("/usr/bin/env"); add("-i")
            addAll(env)
            if (loginShell) {
                add("/bin/sh"); add("-lc"); add(command.joinToString(" "))
            } else {
                addAll(command)
            }
        }

        return ProotInvocation(argv, env)
    }

    /** Launch [invocation] as a host process with stdio inherited by the caller. */
    fun start(invocation: ProotInvocation, workingDir: File = filesDir): Process {
        Log.d(TAG, "exec: ${invocation.argv.joinToString(" ")}")
        return ProcessBuilder(invocation.argv)
            .directory(workingDir)
            .redirectErrorStream(true)
            .apply {
                // proot resolves its loader relative to LD_LIBRARY_PATH too.
                environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
                environment()["PROOT_LOADER"] = prootLoader.absolutePath
            }
            .start()
    }

    /** Make the native proot binaries executable (needed once per install). */
    fun ensureExecutable() {
        listOf(prootBin, prootLoader).forEach {
            if (it.exists()) runCatching { Os.chmod(it.absolutePath, 0b111_101_101) }
        }
    }

    private fun File.ensure(): File = apply { mkdirs() }

    data class Bind(val host: String, val guest: String)
    data class ProotInvocation(val argv: List<String>, val env: List<String>)
}
