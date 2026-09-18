package vxpcore

import java.util.TreeMap

/**
 * Reusable guest heap for MRE applications.
 *
 * Blocks are kept in address order so free() can coalesce adjacent ranges in
 * O(log n). Allocation uses first-fit, splits oversized free blocks, and
 * realloc() grows in place when the immediately following block is free.
 * Metadata lives on the host side; guest memory contains only application data.
 */
class MreHeap(
    private val memory: GuestMemory,
    val base: Int,
    val capacity: Int,
    private val alignment: Int = 8
) {
    data class Stats(
        val capacity: Int,
        val allocatedBytes: Int,
        val freeBytes: Int,
        val largestFreeBlock: Int,
        val liveAllocations: Int,
        val peakAllocatedBytes: Int,
        val allocationCalls: Long,
        val freeCalls: Long,
        val reallocCalls: Long,
        val failedAllocations: Long,
        val lastFailedRequest: Int,
        val lastFailureReason: String
    )

    private data class Block(
        var start: Int,
        var size: Int,
        var free: Boolean
    )

    companion object {
        private const val MIN_SPLIT = 8
    }

    private val blocks = TreeMap<Int, Block>()
    private var allocatedBytes = 0
    private var peakAllocatedBytes = 0
    private var allocationCalls = 0L
    private var freeCalls = 0L
    private var reallocCalls = 0L
    private var failedAllocations = 0L
    private var lastFailedRequest = 0
    private var lastFailureReason = ""

    init {
        require(capacity > 0) { "Heap capacity must be > 0" }
        require(alignment > 0 && alignment and (alignment - 1) == 0) {
            "Heap alignment must be a power of two"
        }
        blocks[base] = Block(base, capacity, free = true)
    }

    @Synchronized
    fun malloc(requested: Int): Int {
        allocationCalls++
        // MRE titles may probe vm_malloc(0). Returning NULL is valid and should not
        // be counted as an allocator failure/OOM.
        if (requested == 0) return 0
        val need = alignedSize(requested) ?: return fail(requested, "invalid-size")

        val candidate = blocks.values.firstOrNull { it.free && it.size >= need }
            ?: return fail(requested, "no-fit")

        allocateFromFreeBlock(candidate, need)
        allocatedBytes += candidate.size
        if (allocatedBytes > peakAllocatedBytes) peakAllocatedBytes = allocatedBytes
        return candidate.start
    }

    @Synchronized
    fun calloc(size: Int): Int {
        val ptr = malloc(size)
        if (ptr != 0) {
            val usable = usableSize(ptr)
            if (usable > 0) memory.writeBytes(ptr, ByteArray(usable))
        }
        return ptr
    }

    @Synchronized
    fun free(ptr: Int): Boolean {
        freeCalls++
        if (ptr == 0) return true
        val block = blocks[ptr] ?: return false
        if (block.free) return false

        block.free = true
        allocatedBytes -= block.size
        coalesce(block)
        return true
    }

    @Synchronized
    fun realloc(ptr: Int, requested: Int): Int {
        reallocCalls++
        if (ptr == 0) return malloc(requested)
        if (requested <= 0) {
            free(ptr)
            return 0
        }

        val need = alignedSize(requested) ?: return fail(requested, "invalid-size")
        val block = blocks[ptr] ?: return 0
        if (block.free) return 0
        val oldSize = block.size

        // Shrink in place and put the tail back on the free list.
        if (need <= oldSize) {
            val tailSize = oldSize - need
            if (tailSize >= MIN_SPLIT) {
                block.size = need
                val tail = Block(block.start + need, tailSize, free = true)
                blocks[tail.start] = tail
                allocatedBytes -= tailSize
                coalesce(tail)
            }
            return ptr
        }

        // Grow in place when the next physical block is free.
        val nextEntry = blocks.higherEntry(block.start)
        val next = nextEntry?.value
        if (next != null && next.free && block.start + block.size == next.start && block.size + next.size >= need) {
            val growBy = need - block.size
            blocks.remove(next.start)
            val remaining = next.size - growBy
            block.size = need
            if (remaining >= MIN_SPLIT) {
                val remainder = Block(block.start + need, remaining, free = true)
                blocks[remainder.start] = remainder
            } else {
                block.size += remaining
            }
            allocatedBytes += block.size - oldSize
            if (allocatedBytes > peakAllocatedBytes) peakAllocatedBytes = allocatedBytes
            return ptr
        }

        // Fall back to allocate-copy-free. Do not release the old block unless a
        // replacement was successfully obtained, matching realloc semantics.
        val replacement = malloc(requested)
        if (replacement == 0) return 0
        val copyBytes = minOf(oldSize, usableSize(replacement))
        if (copyBytes > 0) memory.writeBytes(replacement, memory.readBytes(ptr, copyBytes))
        free(ptr)
        return replacement
    }

    @Synchronized
    fun usableSize(ptr: Int): Int {
        val block = blocks[ptr] ?: return 0
        return if (block.free) 0 else block.size
    }

    @Synchronized
    fun stats(): Stats {
        var freeBytes = 0
        var largest = 0
        var live = 0
        for (block in blocks.values) {
            if (block.free) {
                freeBytes += block.size
                if (block.size > largest) largest = block.size
            } else {
                live++
            }
        }
        return Stats(
            capacity = capacity,
            allocatedBytes = allocatedBytes,
            freeBytes = freeBytes,
            largestFreeBlock = largest,
            liveAllocations = live,
            peakAllocatedBytes = peakAllocatedBytes,
            allocationCalls = allocationCalls,
            freeCalls = freeCalls,
            reallocCalls = reallocCalls,
            failedAllocations = failedAllocations,
            lastFailedRequest = lastFailedRequest,
            lastFailureReason = lastFailureReason
        )
    }

    @Synchronized
    fun validate(): Boolean {
        var expected = base
        var total = 0L
        var previousFree = false
        for (block in blocks.values) {
            if (block.start != expected || block.size <= 0) return false
            if (previousFree && block.free) return false // should have coalesced
            expected += block.size
            total += block.size.toLong()
            previousFree = block.free
        }
        return expected == base + capacity && total == capacity.toLong()
    }

    private fun alignedSize(requested: Int): Int? {
        if (requested <= 0 || requested > capacity) return null
        val r = requested.toLong()
        val aligned = (r + alignment - 1L) and -(alignment.toLong())
        if (aligned <= 0L || aligned > capacity.toLong()) return null
        return aligned.toInt()
    }

    private fun allocateFromFreeBlock(block: Block, need: Int) {
        check(block.free && block.size >= need)
        val originalSize = block.size
        val remainder = originalSize - need
        block.free = false
        if (remainder >= MIN_SPLIT) {
            block.size = need
            val tail = Block(block.start + need, remainder, free = true)
            blocks[tail.start] = tail
        }
    }

    private fun coalesce(initial: Block): Block {
        var block = initial

        val prevEntry = blocks.lowerEntry(block.start)
        val prev = prevEntry?.value
        if (prev != null && prev.free && prev.start + prev.size == block.start) {
            prev.size += block.size
            blocks.remove(block.start)
            block = prev
        }

        val nextEntry = blocks.higherEntry(block.start)
        val next = nextEntry?.value
        if (next != null && next.free && block.start + block.size == next.start) {
            block.size += next.size
            blocks.remove(next.start)
        }
        return block
    }

    private fun fail(requested: Int, reason: String): Int {
        failedAllocations++
        lastFailedRequest = requested
        lastFailureReason = reason
        return 0
    }
}
