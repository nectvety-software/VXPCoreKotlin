package vxpcore.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayInputStream
import java.util.TreeMap
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import vxpcore.FrameSnapshot
import vxpcore.FlashLiteSwf
import vxpcore.VxpKey

/** Kotlin-only Android Flash Lite backend. No JNI/NDK/C++ and no UI classes. */
object FlashLiteAndroidBackend {
    data class Result(
        val width: Int,
        val height: Int,
        val frameRate: Double,
        val frameCount: Int,
        val renderedFrame: Int,
        val renderedLabel: String?,
        val shapes: Int,
        val sprites: Int,
        val bitmaps: Int,
        val buttons: Int,
        val editTexts: Int,
        val placements: Int,
        val removals: Int,
        val unsupportedTags: Map<Int, Int>,
        val frame: FrameSnapshot,
        val avm1ActionsExecuted: Int = 0,
        val inputEvents: Int = 0,
        val playing: Boolean = false,
        val variables: Map<String, String> = emptyMap()
    )

    class Session internal constructor(
        val width: Int,
        val height: Int,
        val frameRate: Double,
        val frameCount: Int,
        private val snapshotFn: () -> FrameSnapshot,
        private val keyFn: (VxpKey, Boolean) -> Boolean,
        private val advanceFn: () -> Unit,
        private val playingFn: () -> Boolean,
        private val frameIndexFn: () -> Int
    ) {
        fun snapshot(): FrameSnapshot = snapshotFn()
        fun keyDown(key: VxpKey): Boolean = keyFn(key, true)
        fun keyUp(key: VxpKey): Boolean = keyFn(key, false)
        fun advanceTimeline() = advanceFn()
        val playing: Boolean get() = playingFn()
        val currentFrame: Int get() = frameIndexFn()
    }

    fun open(input: ByteArray, preferMenu: Boolean = true, trace: Boolean = false): Session {
        val movie = Parser(trace).parse(input)
        val menuFrame = if (preferMenu) runCatching {
            val startup = FlashLiteSwf.runStartup(input)
            if (startup.stopped) startup.reachedFrame + 1 else 0
        }.getOrDefault(0) else 0
        val startFrame = menuFrame.coerceIn(0, max(0, movie.root.frames.size - 1))
        val renderer = Renderer(movie, trace)
        val player = Player(movie, renderer, startFrame, trace)
        var serial = 0L
        fun snap(): FrameSnapshot = bitmapToSnapshot(player.image(), ++serial)
        return Session(
            width = movie.width,
            height = movie.height,
            frameRate = movie.frameRate,
            frameCount = movie.root.frames.size,
            snapshotFn = ::snap,
            keyFn = { key, down ->
                if (down) player.dispatchFlashKey(toFlashKey(key))
                else player.dispatchButtonTransition(press = false)
            },
            advanceFn = player::advanceTimeline,
            playingFn = { player.playing },
            frameIndexFn = { player.currentFrame }
        )
    }

    fun render(
        input: ByteArray,
        requestedFrame: Int? = null,
        requestedLabel: String? = null,
        preferMenu: Boolean = false,
        inputKeys: List<VxpKey> = emptyList(),
        trace: Boolean = false
    ): Result {
        val movie = Parser(trace).parse(input)
        val menuFrame = if (preferMenu) runCatching {
            val startup = FlashLiteSwf.runStartup(input)
            if (startup.stopped) startup.reachedFrame + 1 else 0
        }.getOrDefault(0) else null
        val rawFrame = when {
            requestedLabel != null -> movie.root.labels[requestedLabel]
                ?: error("Flash frame label '$requestedLabel' not found")
            requestedFrame != null -> requestedFrame
            menuFrame != null -> menuFrame
            else -> 0
        }
        val player = Player(movie, Renderer(movie, trace), rawFrame.coerceIn(0, max(0, movie.root.frames.size - 1)), trace)
        inputKeys.forEach { player.dispatchFlashKey(toFlashKey(it)) }
        return Result(
            width = movie.width,
            height = movie.height,
            frameRate = movie.frameRate,
            frameCount = movie.root.frames.size,
            renderedFrame = player.currentFrame,
            renderedLabel = requestedLabel ?: if (preferMenu) "startup-stop" else null,
            shapes = movie.dictionary.values.count { it is ShapeDef },
            sprites = movie.dictionary.values.count { it is SpriteDef },
            bitmaps = movie.dictionary.values.count { it is BitmapDef },
            buttons = movie.dictionary.values.count { it is ButtonDef },
            editTexts = movie.dictionary.values.count { it is EditTextDef },
            placements = movie.statsPlacements,
            removals = movie.statsRemovals,
            unsupportedTags = movie.unsupportedTags.toSortedMap(),
            frame = bitmapToSnapshot(player.image(), 1),
            avm1ActionsExecuted = player.actionsExecuted,
            inputEvents = player.inputEvents,
            playing = player.playing,
            variables = player.variables.toMap()
        )
    }

    private fun toFlashKey(key: VxpKey): Int = when (key) {
        VxpKey.LEFT -> 1
        VxpKey.RIGHT -> 2
        VxpKey.OK -> 13
        VxpKey.UP -> 14
        VxpKey.DOWN -> 15
        VxpKey.LEFT_SOFT -> 16
        VxpKey.RIGHT_SOFT -> 17
        else -> key.mreCode
    }

    private fun bitmapToSnapshot(bitmap: Bitmap, serial: Long): FrameSnapshot {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val out = ShortArray(argb.size)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c ushr 16) and 0xff
            val g = (c ushr 8) and 0xff
            val b = c and 0xff
            out[i] = (((r and 0xf8) shl 8) or ((g and 0xfc) shl 3) or (b ushr 3)).toShort()
        }
        return FrameSnapshot(bitmap.width, bitmap.height, out, serial)
    }

    private data class Movie(
        val width: Int,
        val height: Int,
        val frameRate: Double,
        val background: Int,
        val root: Timeline,
        val dictionary: MutableMap<Int, CharacterDef>,
        val unsupportedTags: MutableMap<Int, Int>,
        var statsPlacements: Int,
        var statsRemovals: Int
    )

    private data class Tag(val code: Int, val data: ByteArray)

    private data class Timeline(
        val frameCountDeclared: Int,
        val frames: MutableList<FrameState> = mutableListOf(),
        val labels: MutableMap<String, Int> = linkedMapOf()
    )

    private data class FrameState(
        val displayList: TreeMap<Int, Placement>,
        val actions: List<ByteArray> = emptyList()
    )

    private data class Placement(
        val characterId: Int,
        val matrix: Matrix = Matrix.IDENTITY,
        val alpha: Float = 1f,
        val placedAtFrame: Int = 0,
        val name: String? = null
    )

    private data class Matrix(
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        val tx: Double,
        val ty: Double
    ) {
        fun android(): android.graphics.Matrix = android.graphics.Matrix().apply {
            setValues(floatArrayOf(
                a.toFloat(), c.toFloat(), tx.toFloat(),
                b.toFloat(), d.toFloat(), ty.toFloat(),
                0f, 0f, 1f
            ))
        }
        companion object {
            val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    private data class Rect(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
        fun pixels(): RectF = RectF(
            xMin / 20f,
            yMin / 20f,
            xMax / 20f,
            yMax / 20f
        )
    }

    private sealed interface CharacterDef { val id: Int }

    private data class ShapeDef(
        override val id: Int,
        val bounds: Rect,
        val fills: List<FillGeometry>,
        val lines: List<LineGeometry>
    ) : CharacterDef

    private data class SpriteDef(
        override val id: Int,
        val timeline: Timeline
    ) : CharacterDef

    private data class BitmapDef(
        override val id: Int,
        val image: Bitmap
    ) : CharacterDef

    private data class ButtonConditionAction(
        val onPress: Boolean,
        val onRelease: Boolean,
        val onReleaseOutside: Boolean,
        val keyPress: Int,
        val actions: ByteArray,
        val rawFlags1: Int,
        val rawFlags2: Int
    )

    private data class ButtonDef(
        override val id: Int,
        val upState: TreeMap<Int, Placement>,
        val conditionActions: List<ButtonConditionAction>
    ) : CharacterDef

    private data class EditTextDef(
        override val id: Int,
        val bounds: Rect,
        val initialText: String,
        val variableName: String,
        val color: Int,
        val fontHeightPx: Double
    ) : CharacterDef

    private sealed interface FillPaint
    private data class SolidFill(val color: Int) : FillPaint
    private data class GradientFill(val colors: List<Int>) : FillPaint
    private data class BitmapFill(val bitmapId: Int, val clipped: Boolean) : FillPaint
    private object UnsupportedFill : FillPaint

    private data class FillStyle(val serial: Int, val paint: FillPaint)
    private data class LineStyle(val serial: Int, val widthTwips: Int, val color: Int)

    private data class Pt(val x: Int, val y: Int)
    private data class Seg(val start: Pt, val end: Pt, val control: Pt? = null)
    private data class FillGeometry(val style: FillStyle, val paths: List<Path>)
    private data class LineGeometry(val style: LineStyle, val segments: List<Seg>)

    private class Parser(private val trace: Boolean) {
        private val dictionary = linkedMapOf<Int, CharacterDef>()
        private val unsupported = linkedMapOf<Int, Int>()
        private var fillSerial = 1
        private var lineSerial = 1
        private var placements = 0
        private var removals = 0
        private var background = Color.BLACK

        fun parse(input: ByteArray): Movie {
            val bytes = normalize(input)
            require(bytes.size >= 12) { "Truncated SWF" }
            val headerBits = Bits(bytes, 8 * 8)
            val stage = readRect(headerBits)
            headerBits.align()
            val rateRaw = headerBits.u16()
            val frameRate = ((rateRaw ushr 8) and 0xff) + (rateRaw and 0xff) / 256.0
            val declaredFrames = headerBits.u16()
            val tags = readTags(bytes, headerBits.bytePos())

            // First collect character definitions so references may resolve regardless of order.
            scanDefinitions(tags)
            for (tag in tags) {
                if (tag.code == 9 && tag.data.size >= 3) {
                    background = Color.rgb(u8(tag.data, 0), u8(tag.data, 1), u8(tag.data, 2))
                }
            }
            val root = buildTimeline(tags, declaredFrames)
            if (trace) {
                println("[FLASH] dictionary=${dictionary.size}, rootFrames=${root.frames.size}, labels=${root.labels}")
            }
            return Movie(
                width = max(1, (stage.xMax - stage.xMin) / 20),
                height = max(1, (stage.yMax - stage.yMin) / 20),
                frameRate = frameRate,
                background = background,
                root = root,
                dictionary = dictionary,
                unsupportedTags = unsupported,
                statsPlacements = placements,
                statsRemovals = removals
            )
        }

        private fun scanDefinitions(tags: List<Tag>) {
            for (tag in tags) {
                try {
                    when (tag.code) {
                        2, 22, 32 -> {
                            val def = parseShape(tag.code, tag.data)
                            dictionary[def.id] = def
                        }
                        35 -> {
                            val def = parseJpeg3(tag.data)
                            if (def != null) dictionary[def.id] = def
                        }
                        37 -> {
                            val def = parseEditText(tag.data)
                            dictionary[def.id] = def
                        }
                        34 -> {
                            val def = parseButton2(tag.data)
                            dictionary[def.id] = def
                        }
                        39 -> {
                            val def = parseSprite(tag.data)
                            dictionary[def.id] = def
                        }
                        // Known non-definition/control/meta tags handled elsewhere or intentionally ignored.
                        0, 1, 9, 12, 24, 26, 28, 43, 48 -> Unit
                        else -> unsupported[tag.code] = (unsupported[tag.code] ?: 0) + 1
                    }
                } catch (t: Throwable) {
                    unsupported[tag.code] = (unsupported[tag.code] ?: 0) + 1
                    if (trace) println("[FLASH] tag ${tag.code} parse skipped: ${t.message}")
                }
            }
        }

        private fun parseSprite(data: ByteArray): SpriteDef {
            require(data.size >= 4)
            val id = u16(data, 0)
            val declared = u16(data, 2)
            val tags = readTags(data, 4)
            scanDefinitions(tags)
            val timeline = buildTimeline(tags, declared)
            return SpriteDef(id, timeline)
        }

        private fun buildTimeline(tags: List<Tag>, declaredFrames: Int): Timeline {
            val timeline = Timeline(declaredFrames)
            val display = TreeMap<Int, Placement>()
            val actions = mutableListOf<ByteArray>()
            var frameIndex = 0
            for (tag in tags) {
                when (tag.code) {
                    12 -> actions += tag.data
                    26 -> {
                        applyPlaceObject2(tag.data, display, frameIndex)
                        placements++
                    }
                    28 -> {
                        if (tag.data.size >= 2) {
                            display.remove(u16(tag.data, 0))
                            removals++
                        }
                    }
                    43 -> {
                        val label = readCString(tag.data, 0).first
                        if (label.isNotBlank()) timeline.labels[label] = frameIndex
                    }
                    1 -> {
                        timeline.frames += FrameState(TreeMap(display), actions.toList())
                        actions.clear()
                        frameIndex++
                    }
                }
            }
            if (timeline.frames.isEmpty()) timeline.frames += FrameState(TreeMap(display), actions.toList())
            while (timeline.frames.size < declaredFrames) {
                timeline.frames += FrameState(TreeMap(display))
            }
            return timeline
        }

        private fun applyPlaceObject2(data: ByteArray, display: TreeMap<Int, Placement>, frame: Int) {
            if (data.size < 3) return
            val bits = Bits(data)
            val flags = bits.u8()
            val depth = bits.u16()
            val move = (flags and 0x01) != 0
            val hasCharacter = (flags and 0x02) != 0
            val hasMatrix = (flags and 0x04) != 0
            val hasCxform = (flags and 0x08) != 0
            val hasRatio = (flags and 0x10) != 0
            val hasName = (flags and 0x20) != 0
            val hasClipDepth = (flags and 0x40) != 0
            val hasClipActions = (flags and 0x80) != 0

            val old = display[depth]
            val charId = if (hasCharacter) bits.u16() else old?.characterId
            val matrix = if (hasMatrix) readMatrix(bits) else old?.matrix ?: Matrix.IDENTITY
            val alpha = if (hasCxform) readCxformAlpha(bits) else old?.alpha ?: 1f
            if (hasRatio) bits.u16()
            val name = if (hasName) bits.cString() else old?.name
            if (hasClipDepth) bits.u16()
            if (hasClipActions) {
                // SWF 5+ structure. CrazyTaxi is SWF 4, but leave the trailing block unread safely.
            }
            if (charId != null) {
                val placedAt = if (move && !hasCharacter && old != null) old.placedAtFrame else frame
                display[depth] = Placement(charId, matrix, alpha, placedAt, name)
            }
        }

        private fun parseShape(tagCode: Int, data: ByteArray): ShapeDef {
            val hasAlpha = tagCode == 32
            val bits = Bits(data)
            val id = bits.u16()
            val bounds = readRect(bits)
            val fillEdges = linkedMapOf<FillStyle, MutableList<Seg>>()
            val lineEdges = linkedMapOf<LineStyle, MutableList<Seg>>()
            var fills = readFillStyles(bits, hasAlpha, tagCode)
            var lines = readLineStyles(bits, hasAlpha, tagCode)
            var numFillBits = bits.ub(4)
            var numLineBits = bits.ub(4)
            var x = 0
            var y = 0
            var fill0: FillStyle? = null
            var fill1: FillStyle? = null
            var line: LineStyle? = null

            while (bits.remainingBits() > 5) {
                val typeFlag = bits.ub(1)
                if (typeFlag == 0) {
                    val stateNewStyles = bits.ub(1) != 0
                    val stateLineStyle = bits.ub(1) != 0
                    val stateFillStyle1 = bits.ub(1) != 0
                    val stateFillStyle0 = bits.ub(1) != 0
                    val stateMoveTo = bits.ub(1) != 0
                    if (!stateNewStyles && !stateLineStyle && !stateFillStyle1 && !stateFillStyle0 && !stateMoveTo) break
                    if (stateMoveTo) {
                        val n = bits.ub(5)
                        x = bits.sb(n)
                        y = bits.sb(n)
                    }
                    if (stateFillStyle0) {
                        val idx = if (numFillBits == 0) 0 else bits.ub(numFillBits)
                        fill0 = if (idx == 0) null else fills.getOrNull(idx - 1)
                    }
                    if (stateFillStyle1) {
                        val idx = if (numFillBits == 0) 0 else bits.ub(numFillBits)
                        fill1 = if (idx == 0) null else fills.getOrNull(idx - 1)
                    }
                    if (stateLineStyle) {
                        val idx = if (numLineBits == 0) 0 else bits.ub(numLineBits)
                        line = if (idx == 0) null else lines.getOrNull(idx - 1)
                    }
                    if (stateNewStyles) {
                        bits.align()
                        fills = readFillStyles(bits, hasAlpha, tagCode)
                        lines = readLineStyles(bits, hasAlpha, tagCode)
                        numFillBits = bits.ub(4)
                        numLineBits = bits.ub(4)
                    }
                } else {
                    val straight = bits.ub(1) != 0
                    val n = bits.ub(4) + 2
                    val start = Pt(x, y)
                    val seg: Seg
                    if (straight) {
                        val general = bits.ub(1) != 0
                        val dx: Int
                        val dy: Int
                        if (general) {
                            dx = bits.sb(n)
                            dy = bits.sb(n)
                        } else {
                            val vertical = bits.ub(1) != 0
                            if (vertical) {
                                dx = 0
                                dy = bits.sb(n)
                            } else {
                                dx = bits.sb(n)
                                dy = 0
                            }
                        }
                        x += dx
                        y += dy
                        seg = Seg(start, Pt(x, y))
                    } else {
                        val cdx = bits.sb(n)
                        val cdy = bits.sb(n)
                        val adx = bits.sb(n)
                        val ady = bits.sb(n)
                        val control = Pt(x + cdx, y + cdy)
                        x = control.x + adx
                        y = control.y + ady
                        seg = Seg(start, Pt(x, y), control)
                    }
                    fill0?.let { fillEdges.getOrPut(it) { mutableListOf() }.add(seg) }
                    fill1?.let { fillEdges.getOrPut(it) { mutableListOf() }.add(reverse(seg)) }
                    line?.let { lineEdges.getOrPut(it) { mutableListOf() }.add(seg) }
                }
            }

            val fillGeometry = fillEdges.map { (style, segs) -> FillGeometry(style, stitchPaths(segs)) }
            val lineGeometry = lineEdges.map { (style, segs) -> LineGeometry(style, segs.toList()) }
            return ShapeDef(id, bounds, fillGeometry, lineGeometry)
        }

        private fun readFillStyles(bits: Bits, hasAlpha: Boolean, tagCode: Int): List<FillStyle> {
            bits.align()
            var count = bits.u8()
            if (count == 0xff && tagCode != 2) count = bits.u16()
            val out = ArrayList<FillStyle>(count)
            repeat(count) {
                val type = bits.u8()
                val paint: FillPaint = when (type) {
                    0x00 -> SolidFill(readColor(bits, hasAlpha))
                    0x10, 0x12, 0x13 -> {
                        readMatrix(bits)
                        val packed = bits.u8()
                        val n = packed and 0x0f
                        val colors = mutableListOf<Int>()
                        repeat(n) {
                            bits.u8() // ratio
                            colors += readColor(bits, hasAlpha)
                        }
                        if (type == 0x13) bits.u16() // focal point, ignored for v0.5
                        GradientFill(colors.ifEmpty { listOf(Color.WHITE) })
                    }
                    0x40, 0x41, 0x42, 0x43 -> {
                        val bitmapId = bits.u16()
                        readMatrix(bits)
                        BitmapFill(bitmapId, clipped = type == 0x41 || type == 0x43)
                    }
                    else -> UnsupportedFill
                }
                out += FillStyle(fillSerial++, paint)
            }
            return out
        }

        private fun readLineStyles(bits: Bits, hasAlpha: Boolean, tagCode: Int): List<LineStyle> {
            bits.align()
            var count = bits.u8()
            if (count == 0xff && tagCode != 2) count = bits.u16()
            val out = ArrayList<LineStyle>(count)
            repeat(count) {
                val width = bits.u16()
                val color = readColor(bits, hasAlpha)
                out += LineStyle(lineSerial++, width, color)
            }
            return out
        }

        private fun parseJpeg3(data: ByteArray): BitmapDef? {
            if (data.size < 6) return null
            val id = u16(data, 0)
            val imageLen = u32(data, 2).toInt()
            if (imageLen <= 0 || 6 + imageLen > data.size) return null
            var imageBytes = data.copyOfRange(6, 6 + imageLen)
            if (imageBytes.size >= 4 &&
                u8(imageBytes, 0) == 0xff && u8(imageBytes, 1) == 0xd9 &&
                u8(imageBytes, 2) == 0xff && u8(imageBytes, 3) == 0xd8
            ) imageBytes = imageBytes.copyOfRange(2, imageBytes.size)
            val base = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val alphaCompressed = data.copyOfRange(6 + imageLen, data.size)
            val alpha = if (alphaCompressed.isNotEmpty()) runCatching {
                InflaterInputStream(ByteArrayInputStream(alphaCompressed)).use { it.readBytes() }
            }.getOrNull() else null
            if (alpha == null) return BitmapDef(id, base.copy(Bitmap.Config.ARGB_8888, false))
            val pixels = IntArray(base.width * base.height)
            base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
            for (i in pixels.indices) {
                val a = if (i < alpha.size) alpha[i].toInt() and 0xff else 0xff
                pixels[i] = (a shl 24) or (pixels[i] and 0x00ffffff)
            }
            return BitmapDef(id, Bitmap.createBitmap(pixels, base.width, base.height, Bitmap.Config.ARGB_8888))
        }

        private fun parseButton2(data: ByteArray): ButtonDef {
            val bits = Bits(data)
            val id = bits.u16()
            bits.u8() // reserved/trackAsMenu flags
            val actionOffset = bits.u16()
            val up = TreeMap<Int, Placement>()
            while (bits.remainingBits() >= 8) {
                val flags = bits.u8()
                if (flags == 0) break
                val charId = bits.u16()
                val depth = bits.u16()
                val matrix = readMatrix(bits)
                val alpha = readCxformAlpha(bits)
                val stateUp = (flags and 0x01) != 0
                // SWF4 file: filter/blend extension flags cannot appear, so no extra fields are consumed.
                if (stateUp) up[depth] = Placement(charId, matrix, alpha, 0)
            }

            val actions = mutableListOf<ButtonConditionAction>()
            if (actionOffset != 0) {
                // ActionOffset is measured from the beginning of the ActionOffset field (byte 3).
                var p = 3 + actionOffset
                while (p + 4 <= data.size) {
                    val size = u16(data, p)
                    val flags1 = u8(data, p + 2)
                    val flags2 = u8(data, p + 3)
                    val next = if (size == 0) data.size else (p + size).coerceAtMost(data.size)
                    if (next < p + 4) break
                    val code = data.copyOfRange(p + 4, next)
                    // BUTTONCONDACTION bit order is MSB-first inside each byte.
                    // flags1 bit2 = OverUp->OverDown (onPress), bit3 = OverDown->OverUp (onRelease).
                    // flags2 bits7..1 contain CondKeyPress; bit0 = OverDown->Idle (release outside).
                    actions += ButtonConditionAction(
                        onPress = (flags1 and 0x04) != 0,
                        onRelease = (flags1 and 0x08) != 0,
                        onReleaseOutside = (flags2 and 0x01) != 0,
                        keyPress = (flags2 ushr 1) and 0x7f,
                        actions = code,
                        rawFlags1 = flags1,
                        rawFlags2 = flags2
                    )
                    if (size == 0) break
                    p = next
                }
            }
            if (trace && actions.isNotEmpty()) {
                println("[FLASH] button#$id actions=" + actions.joinToString {
                    "key=${it.keyPress},press=${it.onPress},release=${it.onRelease}"
                })
            }
            return ButtonDef(id, up, actions)
        }

        private fun parseEditText(data: ByteArray): EditTextDef {
            val bits = Bits(data)
            val id = bits.u16()
            val bounds = readRect(bits)
            val hasText = bits.ub(1) != 0
            bits.ub(1) // wordWrap
            bits.ub(1) // multiline
            bits.ub(1) // password
            bits.ub(1) // readOnly
            val hasTextColor = bits.ub(1) != 0
            val hasMaxLength = bits.ub(1) != 0
            val hasFont = bits.ub(1) != 0
            val hasFontClass = bits.ub(1) != 0
            bits.ub(1) // autoSize
            val hasLayout = bits.ub(1) != 0
            bits.ub(1) // noSelect
            bits.ub(1) // border
            bits.ub(1) // wasStatic
            bits.ub(1) // html
            bits.ub(1) // useOutlines
            bits.align()
            if (hasFont) bits.u16()
            if (hasFontClass) bits.cString()
            val fontHeight = if (hasFont) bits.u16() / 20.0 else 12.0
            val color = if (hasTextColor) readColor(bits, true) else Color.WHITE
            if (hasMaxLength) bits.u16()
            if (hasLayout) {
                bits.u8()
                bits.u16(); bits.u16(); bits.u16(); bits.u16()
            }
            val variable = bits.cString()
            val initial = if (hasText) bits.cString() else ""
            return EditTextDef(id, bounds, initial, variable, color, fontHeight.coerceAtLeast(6.0))
        }

        private fun readColor(bits: Bits, alpha: Boolean): Int {
            val r = bits.u8(); val g = bits.u8(); val b = bits.u8(); val a = if (alpha) bits.u8() else 255
            return Color.argb(a, r, g, b)
        }

        private fun readCxformAlpha(bits: Bits): Float {
            val hasAdd = bits.ub(1) != 0
            val hasMult = bits.ub(1) != 0
            val n = bits.ub(4)
            var am = 256
            var aa = 0
            if (hasMult) {
                bits.sb(n); bits.sb(n); bits.sb(n); am = bits.sb(n)
            }
            if (hasAdd) {
                bits.sb(n); bits.sb(n); bits.sb(n); aa = bits.sb(n)
            }
            bits.align()
            return (am / 256.0 + aa / 255.0).coerceIn(0.0, 1.0).toFloat()
        }

        private fun normalize(input: ByteArray): ByteArray {
            require(input.size >= 8) { "Truncated SWF" }
            val sig = input.copyOfRange(0, 3).toString(Charsets.US_ASCII)
            if (sig == "FWS") return input
            if (sig == "CWS") {
                val head = input.copyOfRange(0, 8)
                head[0] = 'F'.code.toByte()
                val body = InflaterInputStream(ByteArrayInputStream(input, 8, input.size - 8)).use { it.readBytes() }
                return head + body
            }
            error("ZWS/LZMA SWF is not implemented in Flash Lite v0.5")
        }

        private fun readTags(bytes: ByteArray, start: Int): List<Tag> {
            val out = mutableListOf<Tag>()
            var p = start
            while (p + 2 <= bytes.size) {
                val rec = u16(bytes, p); p += 2
                val code = rec ushr 6
                var len = rec and 0x3f
                if (len == 0x3f) {
                    if (p + 4 > bytes.size) break
                    len = u32(bytes, p).toInt(); p += 4
                }
                if (len < 0 || p + len > bytes.size) break
                val payload = bytes.copyOfRange(p, p + len)
                out += Tag(code, payload)
                p += len
                if (code == 0) break
            }
            return out
        }

        private fun stitchPaths(input: List<Seg>): List<Path> {
            val left = input.toMutableList()
            val out = mutableListOf<Path>()
            while (left.isNotEmpty()) {
                val first = left.removeAt(0)
                val path = Path().apply { fillType = Path.FillType.WINDING }
                path.moveTo(first.start.x / 20f, first.start.y / 20f)
                append(path, first)
                val start = first.start
                var end = first.end
                var guard = 0
                while (end != start && left.isNotEmpty() && guard++ < input.size + 4) {
                    val idx = left.indexOfFirst { it.start == end }
                    if (idx < 0) break
                    val seg = left.removeAt(idx)
                    append(path, seg)
                    end = seg.end
                }
                if (end == start) path.close()
                out += path
            }
            return out
        }

        private fun append(path: Path, seg: Seg) {
            val c = seg.control
            if (c == null) {
                path.lineTo(seg.end.x / 20f, seg.end.y / 20f)
            } else {
                path.quadTo(c.x / 20f, c.y / 20f, seg.end.x / 20f, seg.end.y / 20f)
            }
        }

        private fun reverse(seg: Seg): Seg = Seg(seg.end, seg.start, seg.control)
    }

    private fun containsActionOpcode(code: ByteArray, wanted: Int): Boolean {
        var p = 0
        while (p < code.size) {
            val op = u8(code, p++)
            if (op == 0) return false
            if (op == wanted) return true
            if (op >= 0x80) {
                if (p + 2 > code.size) return false
                val len = u16(code, p)
                p += 2 + len
            }
        }
        return false
    }

    /**
     * v0.5 intentionally models the most important Flash Lite timeline rule used by Crazy Taxi:
     * a nested sprite whose first frame executes ActionStop remains on frame 0 until AVM1 moves it.
     * Other sprites continue to derive their local frame from their placement age, preserving the
     * v0.4 renderer behavior for animated scenery.
     */
    private fun resolvedSpriteFrame(timeline: Timeline, placement: Placement, parentFrame: Int): Int {
        if (timeline.frames.isEmpty()) return 0
        val stoppedAtZero = timeline.frames.first().actions.any { containsActionOpcode(it, 0x07) }
        if (stoppedAtZero) return 0
        val age = max(0, parentFrame - placement.placedAtFrame)
        return age % timeline.frames.size
    }

    private class Player(
        private val movie: Movie,
        private val renderer: Renderer,
        startFrame: Int,
        private val trace: Boolean
    ) {
        var currentFrame: Int = startFrame.coerceIn(0, max(0, movie.root.frames.lastIndex))
            private set
        var playing: Boolean = false
            private set
        val variables: MutableMap<String, String> = linkedMapOf()
        var actionsExecuted: Int = 0
            private set
        var inputEvents: Int = 0
            private set

        private val avm1 = Avm1(this, trace)

        fun image(): Bitmap = renderer.renderRootFrame(currentFrame)

        fun gotoAndStop(frameOrLabel: Any?) {
            goto(frameOrLabel)
            playing = false
            if (trace) println("[AVM1] gotoAndStop($frameOrLabel) -> root frame $currentFrame")
        }

        fun gotoAndPlay(frameOrLabel: Any?) {
            goto(frameOrLabel)
            playing = true
            if (trace) println("[AVM1] gotoAndPlay($frameOrLabel) -> root frame $currentFrame")
        }

        fun goto(frameOrLabel: Any?) {
            val requested = when (frameOrLabel) {
                is Number -> frameOrLabel.toInt()
                null -> currentFrame
                else -> {
                    val text = frameOrLabel.toString()
                    movie.root.labels[text] ?: text.toIntOrNull() ?: currentFrame
                }
            }
            currentFrame = requested.coerceIn(0, max(0, movie.root.frames.lastIndex))
        }

        fun play() { playing = true }
        fun stop() { playing = false }

        fun nextFrame() {
            if (movie.root.frames.isNotEmpty()) currentFrame = (currentFrame + 1).coerceAtMost(movie.root.frames.lastIndex)
        }

        fun prevFrame() {
            currentFrame = (currentFrame - 1).coerceAtLeast(0)
        }

        fun advanceTimeline() {
            if (!playing || movie.root.frames.isEmpty()) return
            currentFrame = (currentFrame + 1) % movie.root.frames.size
            executeRootFrameActions()
        }

        private fun executeRootFrameActions() {
            val frame = movie.root.frames.getOrNull(currentFrame) ?: return
            for (code in frame.actions) avm1.execute(code)
        }

        fun dispatchNamedKey(name: String): Boolean {
            val normalized = name.trim().uppercase()
            return when (normalized) {
                "ENTER", "OK", "SELECT", "FIRE" -> dispatchFlashKey(13)
                "LSK", "LEFT_SOFTKEY", "SOFT1", "F1", "Z" -> dispatchFlashKey(16)
                "RSK", "RIGHT_SOFTKEY", "SOFT2", "F2", "X" -> dispatchFlashKey(17)
                "PRESS", "ONPRESS" -> dispatchButtonTransition(press = true)
                "RELEASE", "ONRELEASE" -> dispatchButtonTransition(press = false)
                else -> normalized.toIntOrNull()?.let(::dispatchFlashKey) ?: false
            }
        }


        fun dispatchFlashKey(keyCode: Int): Boolean {
            inputEvents++
            val buttons = visibleButtons()
            for (button in buttons) {
                val action = button.conditionActions.firstOrNull { it.keyPress == keyCode } ?: continue
                if (trace) println("[AVM1] key=$keyCode -> button#${button.id} (frame=$currentFrame)")
                executeButtonAction(button, action)
                return true
            }
            if (trace) println("[AVM1] key=$keyCode: no visible BUTTONCONDACTION")
            return false
        }

        fun dispatchButtonTransition(press: Boolean): Boolean {
            inputEvents++
            for (button in visibleButtons()) {
                val action = button.conditionActions.firstOrNull { if (press) it.onPress else it.onRelease || it.onReleaseOutside }
                    ?: continue
                if (trace) println("[AVM1] ${if (press) "onPress" else "onRelease"} -> button#${button.id}")
                executeButtonAction(button, action)
                return true
            }
            return false
        }

        private fun executeButtonAction(button: ButtonDef, action: ButtonConditionAction) {
            if (trace) {
                println(
                    "[AVM1] execute button#${button.id} flags=" +
                        "%02x/%02x".format(action.rawFlags1, action.rawFlags2)
                )
            }
            avm1.execute(action.actions)
        }

        fun countAction() { actionsExecuted++ }

        fun setRootFrame(frame: Int) {
            currentFrame = frame.coerceIn(0, max(0, movie.root.frames.lastIndex))
        }

        fun setRootLabel(label: String) {
            val f = movie.root.labels[label]
            if (f != null) currentFrame = f
            else if (trace) println("[AVM1] unknown root label '$label'")
        }

        private fun visibleButtons(): List<ButtonDef> {
            val out = mutableListOf<ButtonDef>()
            val root = movie.root.frames.getOrNull(currentFrame) ?: return out
            walkButtons(root.displayList, currentFrame, 0, out)
            return out
        }

        private fun walkButtons(
            display: TreeMap<Int, Placement>,
            localFrame: Int,
            recursion: Int,
            out: MutableList<ButtonDef>
        ) {
            if (recursion > 24) return
            for ((_, placement) in display) {
                when (val def = movie.dictionary[placement.characterId]) {
                    is ButtonDef -> out += def
                    is SpriteDef -> {
                        if (def.timeline.frames.isNotEmpty()) {
                            val sf = resolvedSpriteFrame(def.timeline, placement, localFrame)
                            walkButtons(def.timeline.frames[sf].displayList, sf, recursion + 1, out)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Minimal AVM1 interpreter focused on Flash Lite menu control and root timeline navigation. */
    private class Avm1(private val player: Player, private val trace: Boolean) {
        private val stack = mutableListOf<Any?>()
        private var target = "/"

        fun execute(code: ByteArray) {
            stack.clear()
            target = "/"
            var p = 0
            var guard = 0
            while (p < code.size && guard++ < 4096) {
                val opPos = p
                val op = u8(code, p++)
                if (op == 0) break
                var payload = ByteArray(0)
                if (op >= 0x80) {
                    if (p + 2 > code.size) break
                    val len = u16(code, p)
                    p += 2
                    if (p + len > code.size) break
                    payload = code.copyOfRange(p, p + len)
                    p += len
                }
                player.countAction()
                when (op) {
                    0x04 -> if (isRootTarget()) player.nextFrame()                       // NextFrame
                    0x05 -> if (isRootTarget()) player.prevFrame()                       // PreviousFrame
                    0x06 -> if (isRootTarget()) player.play()                            // Play
                    0x07 -> if (isRootTarget()) player.stop()                            // Stop
                    0x17 -> pop()                                                        // Pop
                    0x1c -> {                                                            // GetVariable
                        val name = popString()
                        stack += player.variables[name] ?: ""
                    }
                    0x1d -> {                                                            // SetVariable
                        val value = popString()
                        val name = popString()
                        player.variables[name] = value
                        if (trace) println("[AVM1] set $name=$value")
                    }
                    0x20 -> target = popString().ifBlank { "/" }                       // SetTarget2
                    0x2d -> executeFsCommand2()                                          // Flash Lite FSCommand2
                    0x81 -> if (payload.size >= 2 && isRootTarget()) {                   // GotoFrame
                        player.setRootFrame(u16(payload, 0))
                    }
                    0x8b -> {                                                            // SetTarget
                        val (value, _) = readCString(payload, 0)
                        target = value.ifBlank { "/" }
                        if (trace) println("[AVM1] target='$target'")
                    }
                    0x8c -> if (isRootTarget()) {                                        // GoToLabel
                        val (label, _) = readCString(payload, 0)
                        player.setRootLabel(label)
                        if (trace) println("[AVM1] goto label '$label' -> ${player.currentFrame}")
                    }
                    0x96 -> parsePush(payload).forEach { stack += it }                   // Push
                    0x9f -> {                                                            // GotoFrame2
                        val flags = payload.firstOrNull()?.toInt()?.and(0xff) ?: 0
                        val value = pop()
                        if (isRootTarget()) {
                            player.goto(value)
                            if ((flags and 0x01) != 0) player.play() else player.stop()
                        }
                    }
                    // Common no-op for current menu path. Keeping them explicit makes trace readable.
                    0x26 -> if (trace) println("[AVM1 trace] ${popString()}")
                    else -> if (trace) println("[AVM1] unsupported op=0x${op.toString(16)} at $opPos")
                }
            }
        }

        private fun isRootTarget(): Boolean = target == "/" || target.isBlank()

        private fun executeFsCommand2() {
            val count = popString().toIntOrNull() ?: 0
            if (count <= 0) {
                stack += "-1"
                return
            }
            val command = popString()
            val args = mutableListOf<String>()
            repeat((count - 1).coerceAtLeast(0)) { args += popString() }
            if (trace) println("[AVM1] FSCommand2 $command(${args.joinToString()})")
            stack += "0"
        }

        private fun parsePush(data: ByteArray): List<Any?> {
            val out = mutableListOf<Any?>()
            var p = 0
            while (p < data.size) {
                when (u8(data, p++)) {
                    0 -> {
                        val (s, next) = readCString(data, p)
                        out += s
                        p = next
                    }
                    1 -> {
                        if (p + 4 > data.size) break
                        out += Float.fromBits(u32(data, p).toInt())
                        p += 4
                    }
                    2, 3 -> out += null
                    4 -> if (p < data.size) out += u8(data, p++) // register index; value not modeled yet
                    5 -> if (p < data.size) out += (u8(data, p++) != 0)
                    7 -> {
                        if (p + 4 > data.size) break
                        out += u32(data, p).toInt()
                        p += 4
                    }
                    8 -> if (p < data.size) out += u8(data, p++)
                    9 -> {
                        if (p + 2 > data.size) break
                        out += u16(data, p)
                        p += 2
                    }
                    else -> break
                }
            }
            return out
        }

        private fun pop(): Any? = if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)

        private fun popString(): String = when (val v = pop()) {
            null -> ""
            is Boolean -> if (v) "1" else "0"
            is Float -> if (v % 1f == 0f) v.toInt().toString() else v.toString()
            is Double -> if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
            else -> v.toString()
        }
    }


    private class Renderer(private val movie: Movie, private val trace: Boolean) {
        fun renderRootFrame(frame: Int): Bitmap {
            val image = Bitmap.createBitmap(movie.width, movie.height, Bitmap.Config.RGB_565)
            val canvas = Canvas(image)
            canvas.drawColor(movie.background)
            val state = movie.root.frames[frame.coerceIn(0, movie.root.frames.lastIndex)]
            renderDisplayList(canvas, state.displayList, frame, 1f, 0)
            return image
        }

        private fun renderDisplayList(
            canvas: Canvas,
            display: TreeMap<Int, Placement>,
            localFrame: Int,
            parentAlpha: Float,
            recursion: Int
        ) {
            if (recursion > 24) return
            for ((_, placement) in display) {
                val def = movie.dictionary[placement.characterId] ?: continue
                val save = canvas.save()
                try {
                    canvas.concat(placement.matrix.android())
                    val alpha = (parentAlpha * placement.alpha).coerceIn(0f, 1f)
                    when (def) {
                        is ShapeDef -> renderShape(canvas, def, alpha)
                        is BitmapDef -> canvas.drawBitmap(def.image, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).roundToInt() })
                        is EditTextDef -> renderEditText(canvas, def, alpha)
                        is ButtonDef -> renderDisplayList(canvas, def.upState, 0, alpha, recursion + 1)
                        is SpriteDef -> if (def.timeline.frames.isNotEmpty()) {
                            val sf = resolvedSpriteFrame(def.timeline, placement, localFrame)
                            renderDisplayList(canvas, def.timeline.frames[sf].displayList, sf, alpha, recursion + 1)
                        }
                    }
                } catch (t: Throwable) {
                    if (trace) println("[FLASH] render char=${placement.characterId} skipped: ${t.message}")
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }

        private fun renderShape(canvas: Canvas, shape: ShapeDef, alpha: Float) {
            for (fill in shape.fills) for (path in fill.paths) paintPath(canvas, path, fill.style.paint, alpha)
            for (line in shape.lines) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(0.5f, line.style.widthTwips / 20f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = withAlpha(line.style.color, alpha)
                }
                for (seg in line.segments) {
                    val p = Path()
                    p.moveTo(seg.start.x / 20f, seg.start.y / 20f)
                    if (seg.control == null) p.lineTo(seg.end.x / 20f, seg.end.y / 20f)
                    else p.quadTo(seg.control.x / 20f, seg.control.y / 20f, seg.end.x / 20f, seg.end.y / 20f)
                    canvas.drawPath(p, paint)
                }
            }
        }

        private fun paintPath(canvas: Canvas, path: Path, fill: FillPaint, alpha: Float) {
            val bounds = RectF().also { path.computeBounds(it, true) }
            when (fill) {
                is SolidFill -> canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL; color = withAlpha(fill.color, alpha)
                })
                is GradientFill -> {
                    val colors = fill.colors.ifEmpty { listOf(Color.WHITE) }.map { withAlpha(it, alpha) }.toIntArray()
                    val shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom, colors, null, Shader.TileMode.CLAMP)
                    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.shader = shader })
                }
                is BitmapFill -> {
                    val bitmap = (movie.dictionary[fill.bitmapId] as? BitmapDef)?.image ?: return
                    if (bounds.width() <= 0f || bounds.height() <= 0f) return
                    val save = canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(bitmap, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).roundToInt() })
                    canvas.restoreToCount(save)
                }
                UnsupportedFill -> Unit
            }
        }

        private fun renderEditText(canvas: Canvas, text: EditTextDef, alpha: Float) {
            val s = text.initialText.ifEmpty { return }
            val box = text.bounds.pixels()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = text.fontHeightPx.toFloat().coerceAtLeast(6f)
                color = withAlpha(text.color, alpha)
                style = Paint.Style.FILL
            }
            val fm = paint.fontMetrics
            val baseline = min(box.bottom, box.top - fm.ascent)
            canvas.drawText(s, box.left, baseline, paint)
        }

        private fun withAlpha(color: Int, alpha: Float): Int {
            val a = ((Color.alpha(color) / 255f) * alpha * 255f).roundToInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    private class Bits(private val data: ByteArray, var bit: Int = 0) {
        fun remainingBits(): Int = data.size * 8 - bit
        fun bytePos(): Int = (bit + 7) ushr 3
        fun align() { bit = (bit + 7) and -8 }
        fun ub(n: Int): Int {
            if (n <= 0) return 0
            require(bit + n <= data.size * 8) { "Unexpected end of SWF bitstream" }
            var v = 0
            repeat(n) {
                val b = data[bit ushr 3].toInt() and 0xff
                v = (v shl 1) or ((b ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }
        fun sb(n: Int): Int {
            if (n <= 0) return 0
            val v = ub(n)
            return if ((v and (1 shl (n - 1))) != 0) v - (1 shl n) else v
        }
        fun u8(): Int { align(); val p = bit ushr 3; require(p < data.size); bit += 8; return data[p].toInt() and 0xff }
        fun u16(): Int { val a = u8(); val b = u8(); return a or (b shl 8) }
        fun cString(): String {
            align()
            val start = bit ushr 3
            var end = start
            while (end < data.size && data[end].toInt() != 0) end++
            bit = min(data.size * 8, (end + 1) * 8)
            return data.copyOfRange(start, end).toString(Charsets.ISO_8859_1)
        }
    }

    private fun readRect(bits: Bits): Rect {
        val n = bits.ub(5)
        val r = Rect(bits.sb(n), bits.sb(n), bits.sb(n), bits.sb(n))
        bits.align()
        return r
    }

    private fun readMatrix(bits: Bits): Matrix {
        val hasScale = bits.ub(1) != 0
        var a = 1.0
        var d = 1.0
        if (hasScale) {
            val n = bits.ub(5)
            a = bits.sb(n) / 65536.0
            d = bits.sb(n) / 65536.0
        }
        val hasRotate = bits.ub(1) != 0
        var b = 0.0
        var c = 0.0
        if (hasRotate) {
            val n = bits.ub(5)
            b = bits.sb(n) / 65536.0
            c = bits.sb(n) / 65536.0
        }
        val n = bits.ub(5)
        val tx = bits.sb(n) / 20.0
        val ty = bits.sb(n) / 20.0
        bits.align()
        return Matrix(a, b, c, d, tx, ty)
    }

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xff
    private fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or
            (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or
            (u8(b, o + 3).toLong() shl 24)

    private fun readCString(b: ByteArray, off: Int): Pair<String, Int> {
        var e = off
        while (e < b.size && b[e].toInt() != 0) e++
        return b.copyOfRange(off, e).toString(Charsets.ISO_8859_1) to min(b.size, e + 1)
    }
}
