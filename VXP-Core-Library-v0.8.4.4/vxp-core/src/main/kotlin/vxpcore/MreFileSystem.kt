package vxpcore

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Sandboxed VXP guest filesystem compatibility layer implemented independently. */
class MreFileSystem(private val root: File) : AutoCloseable {
    companion object {
        const val MODE_READ = 1
        const val MODE_WRITE = 2
        const val MODE_CREATE_ALWAYS = 4
        const val MODE_APPEND = 8

        /** Hard ceiling for one guest I/O request. Keeps malformed guests from allocating host-sized buffers. */
        const val MAX_IO_SIZE = 32 * 1024 * 1024

        const val ATTR_READ_ONLY = 0x01
        const val ATTR_HIDDEN = 0x02
        const val ATTR_SYSTEM = 0x04
        const val ATTR_VOLUME = 0x08
        const val ATTR_DIR = 0x10
        const val ATTR_ARCHIVE = 0x20
    }

    private data class OpenFile(
        val file: RandomAccessFile,
        val path: File,
        val mode: Int,
        val readable: Boolean,
        val writable: Boolean,
        val append: Boolean
    )
    private data class FindState(val names: List<String>, var index: Int = 0)

    private val handles = linkedMapOf<Int, OpenFile>()
    private val findHandles = linkedMapOf<Int, FindState>()
    /** Guest-visible DOS-ish attribute overrides. Kept host-side so Linux/Android hosts behave consistently. */
    private val attributeOverrides = linkedMapOf<String, Int>()
    private var nextHandle = 3
    private var nextFindHandle = 0x10000

    val internalRoot = File(root, "C")
    val removableRoot = File(root, "E")

    init {
        internalRoot.mkdirs()
        removableRoot.mkdirs()
    }

    /** Canonical guest path, never a host path. Unknown drives retain legacy behavior and map to C:. */
    fun normalizeGuestPath(path: String): String? {
        val cleaned = path.replace('/', '\\').trim().trimEnd('\u0000')
        if (cleaned.isBlank()) return null
        val drive = if (cleaned.length >= 2 && cleaned[1] == ':') cleaned[0].uppercaseChar() else 'C'
        val rest = if (cleaned.length >= 2 && cleaned[1] == ':') cleaned.substring(2) else cleaned
        val parts = mutableListOf<String>()
        for (part in rest.split('\\')) {
            if (part.isBlank() || part == ".") continue
            if (part == "..") return null
            parts += sanitize(part)
        }
        return if (parts.isEmpty()) "$drive:\\" else "$drive:\\${parts.joinToString("\\")}" 
    }

    fun defaultFolderPath(drive: Char = 'C'): String = if (drive.uppercaseChar() == 'E') "E:\\" else "C:\\"

    fun filename(path: String): String? {
        val normalized = normalizeGuestPath(path) ?: return null
        if (normalized.length <= 3) return ""
        return normalized.substringAfterLast('\\')
    }

    /** Parent path always carries a trailing slash, matching common MRE path composition code. */
    fun parentPath(path: String): String? {
        val normalized = normalizeGuestPath(path) ?: return null
        if (normalized.length <= 3) return normalized
        val slash = normalized.lastIndexOf('\\')
        return if (slash <= 2) normalized.substring(0, 3) else normalized.substring(0, slash + 1)
    }

    fun resolveMrePath(path: String): File? {
        val guest = normalizeGuestPath(path) ?: return null
        val drive = guest[0]
        val rest = if (guest.length > 3) guest.substring(3) else ""
        val base = if (drive == 'E') removableRoot else internalRoot
        var p: Path = base.toPath().toAbsolutePath().normalize()
        if (rest.isNotEmpty()) {
            for (part in rest.split('\\')) p = p.resolve(part)
        }
        p = p.normalize()
        val basePath = base.toPath().toAbsolutePath().normalize()
        if (!p.startsWith(basePath)) return null
        return p.toFile()
    }

    fun open(path: String, mode: Int): Int {
        val target = resolveMrePath(path) ?: return -1
        return try {
            val readable = (mode and MODE_READ) != 0
            val writable = (mode and (MODE_WRITE or MODE_CREATE_ALWAYS or MODE_APPEND)) != 0
            val append = (mode and MODE_APPEND) != 0
            if (!readable && !writable) return -1
            if (!writable && !target.isFile) return -1
            if (target.exists() && !target.isFile) return -1
            if (writable && target.exists() && (attributesForFile(target) and ATTR_READ_ONLY) != 0) return -1
            if (writable) target.parentFile?.mkdirs()
            val raf = RandomAccessFile(target, if (writable) "rw" else "r")
            if ((mode and MODE_CREATE_ALWAYS) != 0) raf.setLength(0)
            if (append) raf.seek(raf.length())
            val h = allocHandle()
            handles[h] = OpenFile(raf, target, mode, readable, writable, append)
            h
        } catch (_: Throwable) {
            -1
        }
    }

    fun close(handle: Int): Int {
        val o = handles.remove(handle) ?: return -1
        return try { o.file.close(); 0 } catch (_: Throwable) { -1 }
    }

    fun size(handle: Int): Long? = handles[handle]?.let { runCatching { it.file.length() }.getOrNull() }

    fun sizeByPath(path: String): Long? {
        val f = resolveMrePath(path) ?: return null
        return if (f.isFile) runCatching { f.length() }.getOrNull() else null
    }

    fun exists(path: String): Boolean = resolveMrePath(path)?.exists() == true

    fun truncate(handle: Int, size: Long): Int {
        if (size < 0) return -1
        val o = handles[handle] ?: return -1
        if (!o.writable || (attributesForFile(o.path) and ATTR_READ_ONLY) != 0) return -1
        return try {
            o.file.setLength(size)
            if (o.file.filePointer > size) o.file.seek(size)
            0
        } catch (_: Throwable) { -1 }
    }

    /** Host free-space query scoped to the selected guest drive. */
    fun freeSpace(path: String): Long {
        val resolved = resolveMrePath(path) ?: return 0L
        val base = if (resolved.toPath().toAbsolutePath().normalize().startsWith(removableRoot.toPath().toAbsolutePath().normalize())) removableRoot else internalRoot
        return runCatching { base.usableSpace.coerceAtLeast(0L) }.getOrDefault(0L)
    }

    fun read(handle: Int, length: Int): ByteArray? {
        val o = handles[handle] ?: return null
        if (!o.readable || length < 0 || length > MAX_IO_SIZE) return null
        return try {
            val out = ByteArray(length)
            val n = o.file.read(out)
            if (n < 0) ByteArray(0) else if (n == length) out else out.copyOf(n)
        } catch (_: Throwable) { null }
    }

    fun write(handle: Int, bytes: ByteArray): Int {
        val o = handles[handle] ?: return -1
        if (!o.writable || bytes.size > MAX_IO_SIZE) return -1
        if ((attributesForFile(o.path) and ATTR_READ_ONLY) != 0) return -1
        return try {
            // Append is a write property, not merely the initial cursor position.
            if (o.append) o.file.seek(o.file.length())
            o.file.write(bytes)
            bytes.size
        } catch (_: Throwable) { -1 }
    }

    fun seek(handle: Int, offset: Long, origin: Int): Long? {
        val o = handles[handle] ?: return null
        return try {
            val base = when (origin) {
                0 -> 0L
                1 -> o.file.filePointer
                2 -> o.file.length()
                else -> return null
            }
            val pos = Math.addExact(base, offset)
            if (pos < 0L) return null
            o.file.seek(pos)
            pos
        } catch (_: ArithmeticException) {
            null
        } catch (_: Throwable) { null }
    }

    fun tell(handle: Int): Long? = handles[handle]?.let { runCatching { it.file.filePointer }.getOrNull() }

    fun isEof(handle: Int): Boolean? = handles[handle]?.let {
        runCatching { it.file.filePointer >= it.file.length() }.getOrNull()
    }

    fun commit(handle: Int): Int {
        val o = handles[handle] ?: return -1
        return try { o.file.fd.sync(); 0 } catch (_: Throwable) { -1 }
    }

    fun attributes(path: String): Int {
        val f = resolveMrePath(path) ?: return -1
        if (!f.exists()) return -1
        return attributesForFile(f)
    }

    fun setAttributes(path: String, attributes: Int): Int {
        val f = resolveMrePath(path) ?: return -1
        if (!f.exists()) return -1
        val type = if (f.isDirectory) ATTR_DIR else 0
        val persistent = attributes and (ATTR_READ_ONLY or ATTR_HIDDEN or ATTR_SYSTEM or ATTR_ARCHIVE)
        val normalized = if (f.isDirectory) persistent and ATTR_ARCHIVE.inv() else persistent
        attributeOverrides[key(f)] = type or normalized
        runCatching { f.setWritable((attributes and ATTR_READ_ONLY) == 0, false) }
        return 0
    }

    /** Modification time in Unix seconds, suitable for the v0.8.4.x compatibility ABI. */
    fun modifyTimeSeconds(path: String): Long? {
        val f = resolveMrePath(path) ?: return null
        if (!f.exists()) return null
        return runCatching { (Files.getLastModifiedTime(f.toPath()).toMillis() / 1000L).coerceAtLeast(0L) }.getOrNull()
    }

    fun setModifyTimeSeconds(path: String, epochSeconds: Long): Int {
        if (epochSeconds < 0L) return -1
        val f = resolveMrePath(path) ?: return -1
        if (!f.exists()) return -1
        return try {
            Files.setLastModifiedTime(f.toPath(), java.nio.file.attribute.FileTime.fromMillis(Math.multiplyExact(epochSeconds, 1000L)))
            0
        } catch (_: Throwable) { -1 }
    }

    fun mkdir(path: String): Int {
        val f = resolveMrePath(path) ?: return -1
        return when {
            f.isDirectory -> 0
            f.exists() -> -1
            f.mkdirs() -> 0
            else -> -1
        }
    }

    fun delete(path: String): Int {
        val f = resolveMrePath(path) ?: return -1
        if ((attributesForFile(f) and ATTR_READ_ONLY) != 0) return -1
        val k = key(f)
        return if (f.isFile && f.delete()) { attributeOverrides.remove(k); 0 } else -1
    }

    fun rmdir(path: String): Int {
        val f = resolveMrePath(path) ?: return -1
        if ((attributesForFile(f) and ATTR_READ_ONLY) != 0) return -1
        val k = key(f)
        return if (f.isDirectory && f.delete()) { removeMetadataPrefix(k); 0 } else -1
    }

    fun rename(from: String, to: String): Int {
        val src = resolveMrePath(from) ?: return -1
        val dst = resolveMrePath(to) ?: return -1
        if (!src.exists()) return -1
        if ((attributesForFile(src) and ATTR_READ_ONLY) != 0) return -1
        val srcPath = src.toPath().toAbsolutePath().normalize()
        val dstPath = dst.toPath().toAbsolutePath().normalize()
        if (srcPath == dstPath) return 0
        if (src.isDirectory && dstPath.startsWith(srcPath)) return -1
        if (dst.exists() && src.isFile != dst.isFile) return -1
        return try {
            dst.parentFile?.mkdirs()
            Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING)
            moveMetadataPrefix(key(src), key(dst))
            0
        } catch (_: Throwable) { -1 }
    }

    fun copy(from: String, to: String): Int {
        val src = resolveMrePath(from) ?: return -1
        val dst = resolveMrePath(to) ?: return -1
        if (!src.isFile) return -1
        val srcPath = src.toPath().toAbsolutePath().normalize()
        val dstPath = dst.toPath().toAbsolutePath().normalize()
        if (srcPath == dstPath) return 0
        if (dst.exists() && !dst.isFile) return -1
        if (dst.exists() && (attributesForFile(dst) and ATTR_READ_ONLY) != 0) return -1
        return try {
            dst.parentFile?.mkdirs()
            Files.copy(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            attributeOverrides[key(src)]?.let { attributeOverrides[key(dst)] = it }
            0
        } catch (_: Throwable) { -1 }
    }

    /** Read an entire sandbox file with an explicit compatibility ceiling. */
    fun readAll(path: String, maxBytes: Int = MAX_IO_SIZE): ByteArray? {
        if (maxBytes < 0 || maxBytes > MAX_IO_SIZE) return null
        val f = resolveMrePath(path) ?: return null
        if (!f.isFile || f.length() > maxBytes.toLong()) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    /** Read a bounded file slice without changing any guest file handle cursor. */
    fun readRange(path: String, offset: Long, size: Int): ByteArray? {
        if (offset < 0L || size < 0 || size > MAX_IO_SIZE) return null
        val f = resolveMrePath(path) ?: return null
        if (!f.isFile) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                if (offset > raf.length() || offset + size.toLong() > raf.length()) return null
                raf.seek(offset)
                ByteArray(size).also { if (size > 0) raf.readFully(it) }
            }
        } catch (_: Throwable) { null }
    }

    /**
     * MRE-style wildcard enumeration used by vm_find_first/vm_find_next.
     * The guest only sees names inside its C:/E: sandbox; host paths never escape.
     */
    fun findFirst(pattern: String): Pair<Int, String>? {
        val spec = splitFindPattern(pattern) ?: return null
        val dir = resolveMrePath(spec.first) ?: return null
        if (!dir.isDirectory) return null
        val regex = wildcardRegex(spec.second)
        val names = dir.listFiles()
            ?.asSequence()
            ?.filter { regex.matches(it.name) }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?.toList()
            .orEmpty()
        if (names.isEmpty()) return null
        val h = allocFindHandle()
        findHandles[h] = FindState(names, index = 0)
        return h to names[0]
    }

    fun findNext(handle: Int): String? {
        val state = findHandles[handle] ?: return null
        val next = state.index + 1
        if (next >= state.names.size) return null
        state.index = next
        return state.names[next]
    }

    fun findClose(handle: Int): Int = if (findHandles.remove(handle) != null) 0 else -1

    fun describeHandle(handle: Int): String = handles[handle]?.path?.path ?: "?"

    override fun close() {
        handles.values.forEach { runCatching { it.file.close() } }
        handles.clear()
        findHandles.clear()
    }

    private fun allocHandle(): Int {
        while (handles.containsKey(nextHandle) || nextHandle < 0) nextHandle++
        return nextHandle++
    }

    private fun allocFindHandle(): Int {
        while (findHandles.containsKey(nextFindHandle) || nextFindHandle < 0) nextFindHandle++
        return nextFindHandle++
    }

    private fun splitFindPattern(path: String): Pair<String, String>? {
        val cleaned = path.replace('/', '\\').trim().trimEnd('\u0000')
        if (cleaned.isBlank()) return null
        val slash = cleaned.lastIndexOf('\\')
        return if (slash >= 0) {
            val dir = cleaned.substring(0, slash + 1)
            val mask = cleaned.substring(slash + 1).ifBlank { "*" }
            dir to mask
        } else if (cleaned.length >= 2 && cleaned[1] == ':') {
            (cleaned.substring(0, 2) + "\\") to cleaned.substring(2).ifBlank { "*" }
        } else {
            "C:\\" to cleaned
        }
    }

    private fun wildcardRegex(mask: String): Regex {
        val out = StringBuilder("^")
        for (ch in mask) {
            when (ch) {
                '*' -> out.append(".*")
                '?' -> out.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> out.append('\\').append(ch)
                else -> out.append(ch)
            }
        }
        out.append('$')
        return Regex(out.toString(), RegexOption.IGNORE_CASE)
    }

    private fun sanitize(part: String): String = part.map {
        if (it.code < 32 || it in charArrayOf('<', '>', ':', '"', '|', '?', '*')) '_' else it
    }.joinToString("")

    private fun attributesForFile(f: File): Int {
        if (!f.exists()) return -1
        val type = if (f.isDirectory) ATTR_DIR else ATTR_ARCHIVE
        val stored = attributeOverrides[key(f)] ?: 0
        var result = type or (stored and (ATTR_READ_ONLY or ATTR_HIDDEN or ATTR_SYSTEM or ATTR_ARCHIVE))
        if (f.name.startsWith(".")) result = result or ATTR_HIDDEN
        // Prefer explicit guest metadata, but also expose a genuinely non-writable host file.
        if (!f.canWrite()) result = result or ATTR_READ_ONLY
        return result
    }

    private fun moveMetadataPrefix(fromKey: String, toKey: String) {
        val affected = attributeOverrides.entries
            .filter { it.key == fromKey || it.key.startsWith(fromKey + File.separator) }
            .map { it.key to it.value }
        affected.forEach { (k, _) -> attributeOverrides.remove(k) }
        affected.forEach { (k, v) ->
            val suffix = k.removePrefix(fromKey)
            attributeOverrides[toKey + suffix] = v
        }
    }

    private fun removeMetadataPrefix(prefix: String) {
        val keys = attributeOverrides.keys.filter { it == prefix || it.startsWith(prefix + File.separator) }
        keys.forEach { attributeOverrides.remove(it) }
    }

    private fun key(f: File): String = f.absoluteFile.normalize().path.lowercase(Locale.ROOT)
}
