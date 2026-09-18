package vxpcore

import java.util.TreeMap

class GuestMemory {
    data class Region(
        val start: Int,
        val size: Int,
        val readable: Boolean,
        val writable: Boolean,
        val executable: Boolean,
        val data: ByteArray = ByteArray(size)
    ) {
        val endExclusive: Long get() = start.toUInt().toLong() + size.toLong()
    }

    private val regions = TreeMap<Long, Region>()

    fun map(address: Int, size: Int, read: Boolean = true, write: Boolean = true, exec: Boolean = false) {
        require(size > 0) { "size must be > 0" }
        val start = address.toUInt().toLong()
        val end = start + size.toLong()
        for (r in regions.values) {
            val rs = r.start.toUInt().toLong()
            val re = r.endExclusive
            require(end <= rs || start >= re) {
                "Guest memory overlap: 0x${start.toString(16)}..0x${end.toString(16)} with 0x${rs.toString(16)}..0x${re.toString(16)}"
            }
        }
        regions[start] = Region(address, size, read, write, exec)
    }

    fun isMapped(address: Int, size: Int = 1): Boolean = find(address, size) != null

    private fun find(address: Int, size: Int): Region? {
        val a = address.toUInt().toLong()
        val e = a + size.toLong()
        val entry = regions.floorEntry(a) ?: return null
        val r = entry.value
        return if (a >= r.start.toUInt().toLong() && e <= r.endExclusive) r else null
    }

    private fun region(address: Int, size: Int, write: Boolean = false, exec: Boolean = false): Region {
        val r = find(address, size) ?: error("Unmapped guest memory @ 0x${address.toUInt().toString(16)} size=$size")
        if (write && !r.writable) error("Write protection fault @ 0x${address.toUInt().toString(16)}")
        if (exec && !r.executable) error("Execute protection fault @ 0x${address.toUInt().toString(16)}")
        if (!write && !exec && !r.readable) error("Read protection fault @ 0x${address.toUInt().toString(16)}")
        return r
    }

    fun writeBytes(address: Int, src: ByteArray, offset: Int = 0, length: Int = src.size - offset, force: Boolean = false) {
        val r = find(address, length) ?: error("Unmapped guest memory @ 0x${address.toUInt().toString(16)} size=$length")
        if (!force && !r.writable) error("Write protection fault @ 0x${address.toUInt().toString(16)}")
        val dst = (address.toUInt().toLong() - r.start.toUInt().toLong()).toInt()
        src.copyInto(r.data, dst, offset, offset + length)
    }

    fun readBytes(address: Int, length: Int): ByteArray {
        val r = region(address, length)
        val off = (address.toUInt().toLong() - r.start.toUInt().toLong()).toInt()
        return r.data.copyOfRange(off, off + length)
    }

    fun read8(address: Int, exec: Boolean = false): Int {
        val r = region(address, 1, exec = exec)
        val off = (address.toUInt().toLong() - r.start.toUInt().toLong()).toInt()
        return r.data[off].toInt() and 0xFF
    }

    fun read16(address: Int, exec: Boolean = false): Int =
        read8(address, exec) or (read8(address + 1, exec) shl 8)

    fun read32(address: Int, exec: Boolean = false): Int =
        read8(address, exec) or
            (read8(address + 1, exec) shl 8) or
            (read8(address + 2, exec) shl 16) or
            (read8(address + 3, exec) shl 24)

    fun write8(address: Int, value: Int, force: Boolean = false) {
        val r = find(address, 1) ?: error("Unmapped guest memory @ 0x${address.toUInt().toString(16)}")
        if (!force && !r.writable) error("Write protection fault @ 0x${address.toUInt().toString(16)}")
        val off = (address.toUInt().toLong() - r.start.toUInt().toLong()).toInt()
        r.data[off] = value.toByte()
    }

    fun write16(address: Int, value: Int, force: Boolean = false) {
        write8(address, value, force)
        write8(address + 1, value ushr 8, force)
    }

    fun write32(address: Int, value: Int, force: Boolean = false) {
        write8(address, value, force)
        write8(address + 1, value ushr 8, force)
        write8(address + 2, value ushr 16, force)
        write8(address + 3, value ushr 24, force)
    }

    fun readCString(address: Int, maxLen: Int = 4096): String {
        val out = ArrayList<Byte>()
        var p = address
        repeat(maxLen) {
            val b = read8(p++)
            if (b == 0) return out.toByteArray().toString(Charsets.UTF_8)
            out += b.toByte()
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    fun dumpRegions(): List<Region> = regions.values.toList()
}
