package com.chazlaunch.proot

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream

/**
 * Minimal streaming USTAR/GNU extractor. Enough for a rootfs tarball: regular
 * files, directories, symlinks, hardlinks and long names. We avoid a native
 * `tar`/`libarchive` dependency so the very first unpack (before any guest
 * exists) runs in pure JVM code.
 *
 * The stream is expected to be already decompressed (caller wraps gzip).
 */
object TarExtractor {

    fun interface Progress {
        fun onEntry(path: String, bytesSoFar: Long)
    }

    private const val BLOCK = 512

    fun extract(input: InputStream, dest: File, progress: Progress? = null) {
        dest.mkdirs()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var total = 0L

        while (true) {
            if (!input.readFully(header)) break
            if (header.all { it.toInt() == 0 }) break // end-of-archive marker

            val name = longName ?: parseString(header, 0, 100)
            val sizeField = parseOctal(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = longLink ?: parseString(header, 157, 100)
            val prefix = parseString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty() && typeFlag != 'L' && typeFlag != 'K')
                "$prefix/$name" else name

            when (typeFlag) {
                'L' -> { // GNU long name — next entry's real name is this payload
                    longName = readPayloadString(input, sizeField)
                    continue
                }
                'K' -> { // GNU long link target
                    longLink = readPayloadString(input, sizeField)
                    continue
                }
            }
            longName = null
            longLink = null

            // Guard against path-traversal entries; skip the whole record
            // (content + block padding) so the stream stays aligned.
            val outFile = File(dest, fullName).safeResolve(dest)
            if (outFile == null) {
                skip(input, sizeField)
                skip(input, padding(sizeField))
                continue
            }
            val mode = (parseOctal(header, 100, 8) and 0xFFF).toInt()

            when (typeFlag) {
                '5' -> outFile.mkdirs()
                '2' -> createSymlink(linkName, outFile)
                '1' -> createHardlink(File(dest, linkName), outFile)
                '0', '\u0000', '7' -> {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { copyExact(input, it, sizeField) }
                    runCatching { Os.chmod(outFile.absolutePath, if (mode == 0) 0b110_100_100 else mode) }
                }
                else -> skip(input, sizeField) // char/block/fifo — not meaningful in PRoot
            }

            total += sizeField
            progress?.onEntry(fullName, total)
            skip(input, padding(sizeField))
        }
    }

    private fun createSymlink(target: String, link: File) {
        link.parentFile?.mkdirs()
        link.delete()
        runCatching { Os.symlink(target, link.absolutePath) }
    }

    private fun createHardlink(target: File, link: File) {
        link.parentFile?.mkdirs()
        link.delete()
        runCatching { Os.link(target.absolutePath, link.absolutePath) }
            .onFailure { target.copyTo(link, overwrite = true) }
    }

    // --- header field helpers -------------------------------------------------

    private fun parseString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        var result = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) continue
            if (c < '0'.code || c > '7'.code) continue
            result = (result shl 3) + (c - '0'.code)
        }
        return result
    }

    private fun readPayloadString(input: InputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        input.readFully(buf)
        skip(input, padding(size))
        return String(buf, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    private fun copyExact(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    /** @return true only if the whole buffer was filled; false at clean EOF. */
    private fun InputStream.readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) break   // EOF — truncated (off < size) or clean (off == 0)
            off += n
        }
        return off == buf.size
    }

    /** Guard against path-traversal entries ("../../etc/passwd"). */
    private fun File.safeResolve(base: File): File? {
        val canonicalBase = base.canonicalPath
        val resolved = this
        return if (resolved.canonicalPath.startsWith(canonicalBase)) resolved else null
    }

    @Suppress("unused")
    private fun isSymlinkSupported() = OsConstants.S_IFLNK != 0
}
