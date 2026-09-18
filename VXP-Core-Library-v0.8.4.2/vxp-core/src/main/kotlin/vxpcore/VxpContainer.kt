package vxpcore

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

object VxpContainer {
    enum class Kind { ELF32_ARM, ZLIB_ELF, RAW_ARM_ZLIB, EMBEDDED_ELF, FLASH_LITE_SWF, UNKNOWN }
    data class Payload(val kind: Kind, val elf: ByteArray)

    fun detect(bytes: ByteArray): Kind = when {
        FlashLiteSwf.isSwf(bytes) -> Kind.FLASH_LITE_SWF
        isElf(bytes, 0) -> Kind.ELF32_ARM
        looksLikeZlib(bytes) -> runCatching {
            val inflated = InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            when {
                isElf(inflated, 0) -> Kind.ZLIB_ELF
                RawVxpPackage.looksLikeRawArmZlib(bytes) -> Kind.RAW_ARM_ZLIB
                else -> Kind.UNKNOWN
            }
        }.getOrDefault(Kind.UNKNOWN)
        findElf(bytes) >= 0 -> Kind.EMBEDDED_ELF
        else -> Kind.UNKNOWN
    }

    fun extractElf(bytes: ByteArray): Payload {
        if (FlashLiteSwf.isSwf(bytes)) error("Flash Lite SWF VXP detected; use the Flash Lite backend instead of the ARM/ELF backend.")
        if (isElf(bytes, 0)) return Payload(Kind.ELF32_ARM, bytes)
        if (looksLikeZlib(bytes)) {
            val inflated = runCatching {
                InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrNull()
            if (inflated != null && isElf(inflated, 0)) return Payload(Kind.ZLIB_ELF, inflated)
            if (RawVxpPackage.looksLikeRawArmZlib(bytes)) {
                error("RAW_ARM_ZLIB VXP detected; use the raw native backend instead of ELF loader.")
            }
        }
        val off = findElf(bytes)
        if (off >= 0) return Payload(Kind.EMBEDDED_ELF, bytes.copyOfRange(off, bytes.size))
        error("Unsupported/unknown VXP container. No executable ELF32 payload found.")
    }

    private fun isElf(bytes: ByteArray, o: Int): Boolean =
        o >= 0 && o + 4 <= bytes.size &&
            bytes[o] == 0x7f.toByte() && bytes[o + 1] == 'E'.code.toByte() &&
            bytes[o + 2] == 'L'.code.toByte() && bytes[o + 3] == 'F'.code.toByte()

    private fun looksLikeZlib(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val cmf = bytes[0].toInt() and 0xff
        val flg = bytes[1].toInt() and 0xff
        return (cmf and 0x0f) == 8 && ((cmf shl 8) + flg) % 31 == 0
    }

    private fun findElf(bytes: ByteArray): Int {
        val max = minOf(bytes.size - 4, 1024 * 1024)
        for (i in 1..max) if (isElf(bytes, i)) return i
        return -1
    }
}
