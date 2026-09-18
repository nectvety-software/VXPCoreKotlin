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
    private val hiddenPaths = linkedSetOf<String>()
    private var nextHandle = 3
    private var nextFindHandle = 0x10000

    val internalRoot = File(root, "C")
    val removableRoot = File(root, "E")

    init {
        internalRoot.mkdirs()
        removableRoot.mkdirs()
    }

    fun resolveMrePath(path: String): File? {
        val cleaned = path.replace('/', '\\').trim().trimEnd('\u0000')
        if (cleaned.isBlank()) return null
        val drive = if (cleaned.length >= 2 && cleaned[1] == ':') cleaned[0].uppercaseChar() else 'C'
        val rest = if (cleaned.length >= 2 && cleaned[1] == ':') cleaned.substring(2) else cleaned
        val base = if (drive == 'E') removableRoot else internalRoot
        var p: Path = base.toPath().toAbsolutePath().normalize()
        for (part in rest.split('\\')) {
            if (part.isBlank() || part == ".") continue
            if (part == "..") return null
            p = p.resolve(sanitize(part))
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
        return try {
            // Append is a write property, not merely the initial cursor position. A guest may
            // seek after opening; writes must still land at EOF when append mode is active.
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

    fun commit(handle: Int): Int {
        val o = handles[handle] ?: return -1
        return try { o.file.fd.sync(); 0 } catch (_: Throwable) { -1 }
    }

    fun attributes(path: String): Int {
        val f = resolveMrePath(path) ?: return -1
        if (!f.exists()) return -1
        var a = if (f.isDirectory) ATTR_DIR else ATTR_ARCHIVE
        if (!f.canWrite()) a = a or ATTR_READ_ONLY
        if (hiddenPaths.contains(key(f)) || f.name.startsWith(".")) a = a or ATTR_HIDDEN
        return a
    }

    fun setAttributes(path: String, attributes: Int): Int {
        val f = resolveMrePath(path) ?: return -1
        if (!f.exists()) return -1
        val k = key(f)
        if ((attributes and ATTR_HIDDEN) != 0) hiddenPaths += k else hiddenPaths -= k
        runCatching { f.setWritable((attributes and ATTR_READ_ONLY) == 0, false) }
        return 0
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
        return if (f.isFile && f.delete()) 0 else -1
    }

    fun rmdir(path: String): Int {
        val f = resolveMrePath(path) ?: return -1
        return if (f.isDirectory && f.delete()) 0 else -1
    }

    fun rename(from: String, to: String): Int {
        val src = resolveMrePath(from) ?: return -1
        val dst = resolveMrePath(to) ?: return -1
        if (!src.exists()) return -1
        return try {
            dst.parentFile?.mkdirs()
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            0
        } catch (_: Throwable) { -1 }
    }

    fun copy(from: String, to: String): Int {
        val src = resolveMrePath(from) ?: return -1
        val dst = resolveMrePath(to) ?: return -1
        if (!src.isFile) return -1
        return try {
            dst.parentFile?.mkdirs()
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
            0
        } catch (_: Throwable) { -1 }
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

    private fun key(f: File): String = f.absoluteFile.normalize().path.lowercase(Locale.ROOT)
}
