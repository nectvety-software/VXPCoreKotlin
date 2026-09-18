package vxpcore

class ArmCpu(
    private val memory: GuestMemory,
    private val runtime: MreRuntime,
    var trace: Boolean = false
) {
    val r = IntArray(16)
    var thumb = false
    var halted = false
    var instructions: Long = 0

    private var n = false
    private var z = false
    private var c = false
    private var v = false
    private var thumbBlHigh: Int? = null

    fun reset(entry: Int) {
        r.fill(0)
        thumb = (entry and 1) != 0
        r[15] = entry and -2
        r[13] = MreRuntime.STACK_BASE + MreRuntime.STACK_SIZE - 16
        r[14] = 0
        halted = false
        instructions = 0
        n = false; z = false; c = false; v = false
        thumbBlHigh = null
    }

    fun run(maxInstructions: Long = 5_000_000) {
        while (!halted && !runtime.exitRequested) {
            if (instructions >= maxInstructions) error("Instruction limit exceeded ($maxInstructions), PC=0x${r[15].toUInt().toString(16)}")
            step()
            instructions++
        }
    }

    /**
     * Invoke a guest ARM/Thumb function as an MRE callback. The host installs a synthetic
     * LR return trap, executes until the guest returns to it, then restores the outer PC/LR.
     * r0-r3 follow AAPCS and are intentionally left with callback results/clobbers.
     */
    fun callGuest(function: Int, args: IntArray = intArrayOf(), maxInstructions: Long = 2_000_000): Int {
        require(function != 0) { "Guest function address is null" }
        require(args.size <= 4) { "callGuest currently supports up to four register arguments" }

        val savedPc = r[15]
        val savedLr = r[14]
        val savedThumb = thumb
        val savedHalted = halted
        val startInstructions = instructions

        for (i in 0 until 4) r[i] = if (i < args.size) args[i] else 0
        r[14] = MreRuntime.HOST_RETURN_TRAP
        thumb = (function and 1) != 0
        r[15] = function and -2
        halted = false
        thumbBlHigh = null

        while (!halted && !runtime.exitRequested) {
            if ((r[15] and -2) == MreRuntime.HOST_RETURN_TRAP) break
            if (instructions - startInstructions >= maxInstructions) {
                error("Guest callback instruction limit exceeded ($maxInstructions), function=0x${function.toUInt().toString(16)}, PC=0x${r[15].toUInt().toString(16)}")
            }
            step()
            instructions++
        }

        val result = r[0]
        if (!runtime.exitRequested) {
            r[15] = savedPc
            r[14] = savedLr
            thumb = savedThumb
            halted = savedHalted
        }
        return result
    }

    fun step() {
        val faultPc = r[15]
        val faultThumb = thumb
        try {
            if (runtime.dispatch(this)) return
            if (thumb) stepThumb() else stepArm()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError || t is StackOverflowError) throw t
            val mode = if (faultThumb) "Thumb" else "ARM"
            val regs = "r0=0x${r[0].toUInt().toString(16)} r1=0x${r[1].toUInt().toString(16)} " +
                "r2=0x${r[2].toUInt().toString(16)} r3=0x${r[3].toUInt().toString(16)} " +
                "sp=0x${r[13].toUInt().toString(16)} lr=0x${r[14].toUInt().toString(16)}"
            throw IllegalStateException(
                "Guest CPU fault at PC=0x${faultPc.toUInt().toString(16)} mode=$mode: ${t.message} [$regs]",
                t
            )
        }
    }

    private fun stepArm() {
        val pc = r[15]
        val insn = memory.read32(pc, exec = true)
        if (trace) println("[ARM ] pc=0x${hex(pc)} insn=0x${hex(insn)}")
        r[15] = pc + 4

        val cond = insn ushr 28

        // ARMv5T BLX (immediate) uses cond=0b1111 and must be decoded before
        // normal condition handling. Encoding: 1111 101H imm24. It always
        // switches to Thumb state and stores the ARM return address in LR.
        if ((insn and 0xFE000000.toInt()) == 0xFA000000.toInt()) {
            var imm24 = insn and 0x00ffffff
            if ((imm24 and 0x00800000) != 0) imm24 = imm24 or 0xff000000.toInt()
            val h = (insn ushr 24) and 1
            val target = pc + 8 + (imm24 shl 2) + (h shl 1)
            r[14] = pc + 4
            thumb = true
            r[15] = target and -2
            return
        }

        if (!conditionPassed(cond)) return

        // BX / BLX register
        if ((insn and 0x0ffffff0) == 0x012fff10) {
            branchExchange(r[insn and 0xf], link = false)
            return
        }
        if ((insn and 0x0ffffff0) == 0x012fff30) {
            val ret = r[15]
            branchExchange(r[insn and 0xf], link = true, returnAddress = ret)
            return
        }

        // B/BL
        if ((insn and 0x0e000000) == 0x0a000000) {
            var imm24 = insn and 0x00ffffff
            if ((imm24 and 0x00800000) != 0) imm24 = imm24 or 0xff000000.toInt()
            val target = pc + 8 + (imm24 shl 2)
            if ((insn and 0x01000000) != 0) r[14] = pc + 4
            r[15] = target
            return
        }

        // Block transfer LDM/STM.
        if ((insn and 0x0e000000) == 0x08000000) {
            val p = (insn and (1 shl 24)) != 0
            val u = (insn and (1 shl 23)) != 0
            val w = (insn and (1 shl 21)) != 0
            val l = (insn and (1 shl 20)) != 0
            val rn = (insn ushr 16) and 0xf
            val list = insn and 0xffff
            val count = Integer.bitCount(list)
            var addr = when {
                u && !p -> r[rn]
                u && p -> r[rn] + 4
                !u && !p -> r[rn] - 4 * (count - 1)
                else -> r[rn] - 4 * count
            }
            for (reg in 0..15) {
                if ((list and (1 shl reg)) == 0) continue
                if (l) {
                    val value = memory.read32(addr)
                    if (reg == 15) {
                        thumb = (value and 1) != 0
                        r[15] = value and -2
                    } else r[reg] = value
                } else {
                    val value = if (reg == 15) pc + 12 else r[reg]
                    memory.write32(addr, value)
                }
                addr += 4
            }
            if (w) r[rn] = if (u) r[rn] + 4 * count else r[rn] - 4 * count
            return
        }

        // Single data transfer LDR/STR (immediate or simple shifted register offset)
        if ((insn and 0x0c000000) == 0x04000000) {
            val i = (insn and (1 shl 25)) != 0
            val p = (insn and (1 shl 24)) != 0
            val u = (insn and (1 shl 23)) != 0
            val byte = (insn and (1 shl 22)) != 0
            val w = (insn and (1 shl 21)) != 0
            val load = (insn and (1 shl 20)) != 0
            val rn = (insn ushr 16) and 0xf
            val rd = (insn ushr 12) and 0xf
            val offset = if (!i) insn and 0xfff else decodeShiftedRegister(insn and 0xfff).first
            val base = if (rn == 15) pc + 8 else r[rn]
            val adjusted = if (u) base + offset else base - offset
            val addr = if (p) adjusted else base
            if (load) {
                val value = if (byte) memory.read8(addr) else memory.read32(addr)
                if (rd == 15) {
                    thumb = (value and 1) != 0
                    r[15] = value and -2
                } else r[rd] = value
            } else {
                val value = if (rd == 15) pc + 12 else r[rd]
                if (byte) memory.write8(addr, value) else memory.write32(addr, value)
            }
            if (!p || w) r[rn] = adjusted
            return
        }

        // ARMv4T/ARMv5 extra load/store instructions used heavily by MRE ARMCC
        // output: STRH, LDRH, LDRSB and LDRSH. These encodings live in the same
        // major opcode space as data-processing/multiply, so they MUST be decoded
        // before MUL/data-processing or an LDRH can silently execute as ALU code.
        // Encoding has bits 7 and 4 set and S/H (bits 6:5) != 00.
        if ((insn and 0x0e000090) == 0x00000090 && ((insn ushr 5) and 0x3) != 0) {
            val p = (insn and (1 shl 24)) != 0
            val u = (insn and (1 shl 23)) != 0
            val immediate = (insn and (1 shl 22)) != 0
            val w = (insn and (1 shl 21)) != 0
            val load = (insn and (1 shl 20)) != 0
            val rn = (insn ushr 16) and 0xf
            val rd = (insn ushr 12) and 0xf
            val sh = (insn ushr 5) and 0x3
            val offset = if (immediate) {
                (((insn ushr 8) and 0xf) shl 4) or (insn and 0xf)
            } else {
                r[insn and 0xf]
            }
            val base = if (rn == 15) pc + 8 else r[rn]
            val adjusted = if (u) base + offset else base - offset
            val addr = if (p) adjusted else base

            if (load) {
                val value = when (sh) {
                    1 -> memory.read16(addr) // LDRH
                    2 -> memory.read8(addr).toByte().toInt() // LDRSB
                    3 -> memory.read16(addr).toShort().toInt() // LDRSH
                    else -> 0
                }
                if (rd == 15) {
                    thumb = (value and 1) != 0
                    r[15] = value and -2
                } else {
                    r[rd] = value
                }
            } else {
                // sh==1 is STRH. ARMv5 encodings with sh==2/3 and L=0 are
                // doubleword transfers; leave those explicit until required.
                if (sh != 1) unsupported(pc, insn, "ARM STRD/LDRD extra transfer")
                val value = if (rd == 15) pc + 12 else r[rd]
                memory.write16(addr, value)
            }

            if (!p || w) r[rn] = adjusted
            return
        }

        // MUL / MLA subset
        if ((insn and 0x0fc000f0) == 0x00000090) {
            val accumulate = (insn and (1 shl 21)) != 0
            val setFlags = (insn and (1 shl 20)) != 0
            val rd = (insn ushr 16) and 0xf
            val rn = (insn ushr 12) and 0xf
            val rs = (insn ushr 8) and 0xf
            val rm = insn and 0xf
            var result = r[rm] * r[rs]
            if (accumulate) result += r[rn]
            r[rd] = result
            if (setFlags) setNZ(result)
            return
        }

        // Data processing
        if ((insn and 0x0c000000) == 0x00000000) {
            val immediate = (insn and (1 shl 25)) != 0
            val opcode = (insn ushr 21) and 0xf
            val setFlags = (insn and (1 shl 20)) != 0
            val rn = (insn ushr 16) and 0xf
            val rd = (insn ushr 12) and 0xf
            val op1 = if (rn == 15) pc + 8 else r[rn]
            val (op2, shCarry) = if (immediate) decodeImmediate(insn) else decodeShiftedRegister(insn and 0xfff)
            when (opcode) {
                0 -> writeDp(rd, op1 and op2, setFlags, shCarry)
                1 -> writeDp(rd, op1 xor op2, setFlags, shCarry)
                2 -> {
                    val result = op1 - op2
                    writeDp(rd, result, setFlags, carrySub(op1, op2), overflowSub(op1, op2, result))
                }
                3 -> {
                    val result = op2 - op1
                    writeDp(rd, result, setFlags, carrySub(op2, op1), overflowSub(op2, op1, result))
                }
                4 -> {
                    val result = op1 + op2
                    writeDp(rd, result, setFlags, carryAdd(op1, op2), overflowAdd(op1, op2, result))
                }
                5 -> { // ADC
                    val carryIn = if (c) 1 else 0
                    val unsigned = op1.toUInt().toLong() + op2.toUInt().toLong() + carryIn.toLong()
                    val signed = op1.toLong() + op2.toLong() + carryIn.toLong()
                    val result = unsigned.toInt()
                    writeDp(
                        rd,
                        result,
                        setFlags,
                        unsigned > 0xffffffffL,
                        signed > Int.MAX_VALUE.toLong() || signed < Int.MIN_VALUE.toLong()
                    )
                }
                6 -> { // SBC: op1 - op2 - (1-C)
                    val borrow = if (c) 0 else 1
                    val subtrahend = op2.toUInt().toLong() + borrow.toLong()
                    val unsignedA = op1.toUInt().toLong()
                    val signed = op1.toLong() - op2.toLong() - borrow.toLong()
                    val result = (unsignedA - subtrahend).toInt()
                    writeDp(
                        rd,
                        result,
                        setFlags,
                        unsignedA >= subtrahend,
                        signed > Int.MAX_VALUE.toLong() || signed < Int.MIN_VALUE.toLong()
                    )
                }
                7 -> { // RSC: op2 - op1 - (1-C)
                    val borrow = if (c) 0 else 1
                    val subtrahend = op1.toUInt().toLong() + borrow.toLong()
                    val unsignedA = op2.toUInt().toLong()
                    val signed = op2.toLong() - op1.toLong() - borrow.toLong()
                    val result = (unsignedA - subtrahend).toInt()
                    writeDp(
                        rd,
                        result,
                        setFlags,
                        unsignedA >= subtrahend,
                        signed > Int.MAX_VALUE.toLong() || signed < Int.MIN_VALUE.toLong()
                    )
                }
                8 -> { val result = op1 and op2; setNZ(result); c = shCarry }
                9 -> { val result = op1 xor op2; setNZ(result); c = shCarry }
                10 -> { val result = op1 - op2; setNZ(result); c = carrySub(op1, op2); v = overflowSub(op1, op2, result) }
                11 -> { val result = op1 + op2; setNZ(result); c = carryAdd(op1, op2); v = overflowAdd(op1, op2, result) }
                12 -> writeDp(rd, op1 or op2, setFlags, shCarry)
                13 -> writeDp(rd, op2, setFlags, shCarry)
                14 -> writeDp(rd, op1 and op2.inv(), setFlags, shCarry)
                15 -> writeDp(rd, op2.inv(), setFlags, shCarry)
                else -> unsupported(pc, insn, "ARM data-processing opcode=$opcode")
            }
            return
        }

        if ((insn and 0x0f000000) == 0x0f000000) unsupported(pc, insn, "SWI/SVC")
        unsupported(pc, insn, "ARM instruction")
    }

    private fun stepThumb() {
        val pc = r[15]
        val op = memory.read16(pc, exec = true)
        if (trace) println("[THMB] pc=0x${hex(pc)} op=0x${op.toString(16).padStart(4, '0')}")
        r[15] = pc + 2

        // Thumb-1 long BL prefix/suffix.
        if ((op and 0xF800) == 0xF000) {
            var hi = op and 0x07ff
            if ((hi and 0x0400) != 0) hi = hi or -0x800
            thumbBlHigh = pc + 4 + (hi shl 12)
            return
        }
        if ((op and 0xF800) == 0xF800 && thumbBlHigh != null) {
            val base = thumbBlHigh!!
            val target = base + ((op and 0x07ff) shl 1)
            r[14] = (pc + 2) or 1
            r[15] = target and -2
            thumb = true
            thumbBlHigh = null
            return
        }
        // ARMv5T Thumb BLX (immediate) suffix. The first halfword uses the
        // same 0xF000 prefix as long BL, while the second halfword is 0xE800.
        // BLX aligns the ARM target to a word boundary and returns to Thumb via LR bit0.
        if ((op and 0xF800) == 0xE800 && thumbBlHigh != null) {
            val base = thumbBlHigh!!
            val target = base + ((op and 0x07ff) shl 1)
            r[14] = (pc + 2) or 1
            r[15] = target and -4
            thumb = false
            thumbBlHigh = null
            return
        }

        // Move shifted register.
        if ((op and 0xE000) == 0x0000 && (op and 0x1800) != 0x1800) {
            val kind = (op ushr 11) and 0x3
            val imm5 = (op ushr 6) and 0x1f
            val rs = (op ushr 3) and 0x7
            val rd = op and 0x7
            val value = r[rs]
            val (result, carry) = when (kind) {
                0 -> shiftLsl(value, imm5)
                1 -> shiftLsr(value, if (imm5 == 0) 32 else imm5)
                else -> shiftAsr(value, if (imm5 == 0) 32 else imm5)
            }
            r[rd] = result; setNZ(result); c = carry
            return
        }

        // ADD/SUB register/immediate3.
        if ((op and 0xF800) == 0x1800) {
            val immediate = (op and 0x0400) != 0
            val sub = (op and 0x0200) != 0
            val rnOrImm = (op ushr 6) and 0x7
            val rs = (op ushr 3) and 0x7
            val rd = op and 0x7
            val rhs = if (immediate) rnOrImm else r[rnOrImm]
            val lhs = r[rs]
            val result = if (sub) lhs - rhs else lhs + rhs
            r[rd] = result; setNZ(result)
            c = if (sub) carrySub(lhs, rhs) else carryAdd(lhs, rhs)
            v = if (sub) overflowSub(lhs, rhs, result) else overflowAdd(lhs, rhs, result)
            return
        }

        // MOV/CMP/ADD/SUB immediate8.
        if ((op and 0xE000) == 0x2000) {
            val kind = (op ushr 11) and 0x3
            val rd = (op ushr 8) and 0x7
            val imm = op and 0xff
            when (kind) {
                0 -> { r[rd] = imm; setNZ(r[rd]) }
                1 -> { val result = r[rd] - imm; setNZ(result); c = carrySub(r[rd], imm); v = overflowSub(r[rd], imm, result) }
                2 -> { val lhs = r[rd]; val result = lhs + imm; r[rd] = result; setNZ(result); c = carryAdd(lhs, imm); v = overflowAdd(lhs, imm, result) }
                3 -> { val lhs = r[rd]; val result = lhs - imm; r[rd] = result; setNZ(result); c = carrySub(lhs, imm); v = overflowSub(lhs, imm, result) }
            }
            return
        }

        // ALU operations.
        if ((op and 0xFC00) == 0x4000) {
            val kind = (op ushr 6) and 0xf
            val rs = (op ushr 3) and 7
            val rd = op and 7
            val a = r[rd]; val b = r[rs]
            when (kind) {
                0 -> { r[rd] = a and b; setNZ(r[rd]) }
                1 -> { r[rd] = a xor b; setNZ(r[rd]) }
                2 -> { val s = b and 0xff; val q = shiftLsl(a, s); r[rd] = q.first; setNZ(q.first); c = q.second }
                3 -> { val s = b and 0xff; val q = shiftLsr(a, s); r[rd] = q.first; setNZ(q.first); c = q.second }
                4 -> { val s = b and 0xff; val q = shiftAsr(a, s); r[rd] = q.first; setNZ(q.first); c = q.second }
                5 -> { // ADC
                    val carryIn = if (c) 1 else 0
                    val sum = a.toUInt().toLong() + b.toUInt().toLong() + carryIn
                    val result = sum.toInt()
                    r[rd] = result; setNZ(result); c = sum > 0xffffffffL
                    val signed = a.toLong() + b.toLong() + carryIn
                    v = signed > Int.MAX_VALUE.toLong() || signed < Int.MIN_VALUE.toLong()
                }
                6 -> { // SBC: a - b - (1-C)
                    val borrow = if (c) 0 else 1
                    val subtrahend = b.toUInt().toLong() + borrow
                    val result = (a.toUInt().toLong() - subtrahend).toInt()
                    r[rd] = result; setNZ(result); c = a.toUInt().toLong() >= subtrahend
                    val signed = a.toLong() - b.toLong() - borrow
                    v = signed > Int.MAX_VALUE.toLong() || signed < Int.MIN_VALUE.toLong()
                }
                7 -> { // ROR register
                    val amount = b and 0xff
                    if (amount != 0) {
                        val rot = amount and 31
                        val result = if (rot == 0) a else Integer.rotateRight(a, rot)
                        r[rd] = result; setNZ(result)
                        c = if (rot == 0) (a ushr 31) != 0 else ((a ushr (rot - 1)) and 1) != 0
                    } else setNZ(a)
                }
                8 -> { val result = a and b; setNZ(result) }
                9 -> { // NEG = 0 - Rs
                    val result = -b
                    r[rd] = result; setNZ(result); c = b == 0; v = b == Int.MIN_VALUE
                }
                10 -> { val result = a - b; setNZ(result); c = carrySub(a, b); v = overflowSub(a, b, result) }
                11 -> { val result = a + b; setNZ(result); c = carryAdd(a, b); v = overflowAdd(a, b, result) }
                12 -> { r[rd] = a or b; setNZ(r[rd]) }
                13 -> { r[rd] = a * b; setNZ(r[rd]) }
                14 -> { r[rd] = a and b.inv(); setNZ(r[rd]) }
                15 -> { r[rd] = b.inv(); setNZ(r[rd]) }
                else -> unsupportedThumb(pc, op, "Thumb ALU op=$kind")
            }
            return
        }

        // High register ops / BX.
        if ((op and 0xFC00) == 0x4400) {
            val kind = (op ushr 8) and 0x3
            val h1 = (op ushr 7) and 1
            val h2 = (op ushr 6) and 1
            val rs = ((op ushr 3) and 7) or (h2 shl 3)
            val rd = (op and 7) or (h1 shl 3)
            when (kind) {
                0 -> { val result = r[rd] + r[rs]; if (rd == 15) { thumb = (result and 1) != 0; r[15] = result and -2 } else r[rd] = result }
                1 -> { val result = r[rd] - r[rs]; setNZ(result); c = carrySub(r[rd], r[rs]); v = overflowSub(r[rd], r[rs], result) }
                2 -> { val value = r[rs]; if (rd == 15) { thumb = (value and 1) != 0; r[15] = value and -2 } else r[rd] = value }
                3 -> branchExchange(r[rs], link = false)
            }
            return
        }

        // PC-relative LDR.
        if ((op and 0xF800) == 0x4800) {
            val rd = (op ushr 8) and 7
            val addr = ((pc + 4) and -4) + ((op and 0xff) shl 2)
            r[rd] = memory.read32(addr)
            return
        }

        // Load/store with register offset (Thumb format 7/8).
        // Supports STR/STRH/STRB/LDRSB/LDR/LDRH/LDRB/LDRSH.
        if ((op and 0xF000) == 0x5000) {
            val kind = (op ushr 9) and 0x7
            val rm = (op ushr 6) and 0x7
            val rb = (op ushr 3) and 0x7
            val rd = op and 0x7
            val addr = r[rb] + r[rm]
            when (kind) {
                0 -> memory.write32(addr, r[rd])
                1 -> memory.write16(addr, r[rd])
                2 -> memory.write8(addr, r[rd])
                3 -> r[rd] = memory.read8(addr).toByte().toInt()
                4 -> r[rd] = memory.read32(addr)
                5 -> r[rd] = memory.read16(addr)
                6 -> r[rd] = memory.read8(addr)
                7 -> r[rd] = memory.read16(addr).toShort().toInt()
            }
            return
        }

        // Load/store immediate word/byte.
        if ((op and 0xE000) == 0x6000) {
            val byte = (op and 0x1000) != 0
            val load = (op and 0x0800) != 0
            val imm5 = (op ushr 6) and 0x1f
            val rb = (op ushr 3) and 7
            val rd = op and 7
            val addr = r[rb] + if (byte) imm5 else imm5 * 4
            if (load) r[rd] = if (byte) memory.read8(addr) else memory.read32(addr)
            else if (byte) memory.write8(addr, r[rd]) else memory.write32(addr, r[rd])
            return
        }

        // SP-relative LDR/STR.
        if ((op and 0xF000) == 0x9000) {
            val load = (op and 0x0800) != 0
            val rd = (op ushr 8) and 7
            val addr = r[13] + ((op and 0xff) shl 2)
            if (load) r[rd] = memory.read32(addr) else memory.write32(addr, r[rd])
            return
        }

        // Load address: ADD Rd, PC/SP, #imm.
        if ((op and 0xF000) == 0xA000) {
            val useSp = (op and 0x0800) != 0
            val rd = (op ushr 8) and 7
            val base = if (useSp) r[13] else ((pc + 4) and -4)
            r[rd] = base + ((op and 0xff) shl 2)
            return
        }

        // ADD/SUB SP.
        if ((op and 0xFF00) == 0xB000) {
            val sub = (op and 0x0080) != 0
            val amount = (op and 0x7f) shl 2
            r[13] = if (sub) r[13] - amount else r[13] + amount
            return
        }

        // PUSH / POP.
        if ((op and 0xF600) == 0xB400) {
            val pop = (op and 0x0800) != 0
            val extra = (op and 0x0100) != 0
            val list = op and 0xff
            if (!pop) {
                val count = Integer.bitCount(list) + if (extra) 1 else 0
                var addr = r[13] - count * 4
                val newSp = addr
                for (reg in 0..7) if ((list and (1 shl reg)) != 0) { memory.write32(addr, r[reg]); addr += 4 }
                if (extra) memory.write32(addr, r[14])
                r[13] = newSp
            } else {
                var addr = r[13]
                for (reg in 0..7) if ((list and (1 shl reg)) != 0) { r[reg] = memory.read32(addr); addr += 4 }
                if (extra) {
                    val value = memory.read32(addr); addr += 4
                    thumb = (value and 1) != 0; r[15] = value and -2
                }
                r[13] = addr
            }
            return
        }

        // STMIA / LDMIA.
        //
        // Thumb-1 LDMIA has an important base-register-in-list case used by
        // ARMCC output. Example: LDMIA r1!, {r0,r1}. The loaded value for r1
        // must survive; blindly applying write-back after the loads corrupts it
        // with base+4*N. This previously broke Spider-Man's formatter state and
        // ultimately caused an attempted write into the RO format string
        // "%c:\\%s" at 0x1001BCAC.
        if ((op and 0xF000) == 0xC000) {
            val load = (op and 0x0800) != 0
            val rb = (op ushr 8) and 7
            val list = op and 0xff
            var addr = r[rb]
            for (reg in 0..7) if ((list and (1 shl reg)) != 0) {
                if (load) r[reg] = memory.read32(addr) else memory.write32(addr, r[reg])
                addr += 4
            }

            val baseInList = (list and (1 shl rb)) != 0
            if (!(load && baseInList)) {
                r[rb] = addr
            }
            return
        }

        // Conditional branch / SWI.
        if ((op and 0xF000) == 0xD000) {
            val cond = (op ushr 8) and 0xf
            if (cond == 0xf) unsupportedThumb(pc, op, "SWI")
            var imm = op and 0xff
            if ((imm and 0x80) != 0) imm = imm or -0x100
            if (conditionPassed(cond)) r[15] = pc + 4 + (imm shl 1)
            return
        }

        // Unconditional branch.
        if ((op and 0xF800) == 0xE000) {
            var imm11 = op and 0x7ff
            if ((imm11 and 0x400) != 0) imm11 = imm11 or -0x800
            r[15] = pc + 4 + (imm11 shl 1)
            return
        }

        unsupportedThumb(pc, op, "Thumb instruction")
    }

    private fun branchExchange(target: Int, link: Boolean, returnAddress: Int = r[15]) {
        if (link) r[14] = returnAddress or if (thumb) 1 else 0
        thumb = (target and 1) != 0
        r[15] = target and -2
    }

    private fun writeDp(rd: Int, result: Int, flags: Boolean, carry: Boolean = c, overflow: Boolean = v) {
        if (rd == 15) {
            thumb = (result and 1) != 0
            r[15] = result and -2
        } else r[rd] = result
        if (flags) { setNZ(result); c = carry; v = overflow }
    }

    private fun decodeImmediate(insn: Int): Pair<Int, Boolean> {
        val imm8 = insn and 0xff
        val rot = ((insn ushr 8) and 0xf) * 2
        if (rot == 0) return imm8 to c
        val value = Integer.rotateRight(imm8, rot)
        return value to ((value ushr 31) != 0)
    }

    private fun decodeShiftedRegister(op2: Int): Pair<Int, Boolean> {
        val rm = op2 and 0xf
        val value = r[rm]
        if ((op2 and 0x10) != 0) {
            val rs = (op2 ushr 8) and 0xf
            val amount = r[rs] and 0xff
            return shift(value, (op2 ushr 5) and 3, amount, registerShift = true)
        }
        val amount = (op2 ushr 7) and 0x1f
        return shift(value, (op2 ushr 5) and 3, amount, registerShift = false)
    }

    private fun shift(value: Int, kind: Int, amountRaw: Int, registerShift: Boolean): Pair<Int, Boolean> {
        var amount = amountRaw
        return when (kind) {
            0 -> shiftLsl(value, amount)
            1 -> { if (!registerShift && amount == 0) amount = 32; shiftLsr(value, amount) }
            2 -> { if (!registerShift && amount == 0) amount = 32; shiftAsr(value, amount) }
            else -> {
                if (!registerShift && amount == 0) {
                    val result = (if (c) 0x80000000.toInt() else 0) or (value ushr 1)
                    result to ((value and 1) != 0)
                } else {
                    amount = amount and 31
                    if (amount == 0) value to c else Integer.rotateRight(value, amount) to (((value ushr (amount - 1)) and 1) != 0)
                }
            }
        }
    }

    private fun shiftLsl(value: Int, amount: Int): Pair<Int, Boolean> = when {
        amount == 0 -> value to c
        amount < 32 -> (value shl amount) to (((value ushr (32 - amount)) and 1) != 0)
        amount == 32 -> 0 to ((value and 1) != 0)
        else -> 0 to false
    }

    private fun shiftLsr(value: Int, amount: Int): Pair<Int, Boolean> = when {
        amount == 0 -> value to c
        amount < 32 -> (value ushr amount) to (((value ushr (amount - 1)) and 1) != 0)
        amount == 32 -> 0 to ((value ushr 31) != 0)
        else -> 0 to false
    }

    private fun shiftAsr(value: Int, amount: Int): Pair<Int, Boolean> = when {
        amount == 0 -> value to c
        amount < 32 -> (value shr amount) to (((value ushr (amount - 1)) and 1) != 0)
        else -> (if (value < 0) -1 else 0) to (value < 0)
    }

    private fun conditionPassed(cond: Int): Boolean = when (cond) {
        0 -> z
        1 -> !z
        2 -> c
        3 -> !c
        4 -> n
        5 -> !n
        6 -> v
        7 -> !v
        8 -> c && !z
        9 -> !c || z
        10 -> n == v
        11 -> n != v
        12 -> !z && n == v
        13 -> z || n != v
        14 -> true
        else -> false
    }

    private fun setNZ(value: Int) { n = value < 0; z = value == 0 }
    private fun carryAdd(a: Int, b: Int): Boolean = a.toUInt().toLong() + b.toUInt().toLong() > 0xffffffffL
    private fun carrySub(a: Int, b: Int): Boolean = a.toUInt() >= b.toUInt()
    private fun overflowAdd(a: Int, b: Int, result: Int): Boolean = ((a xor result) and (b xor result) and Int.MIN_VALUE) != 0
    private fun overflowSub(a: Int, b: Int, result: Int): Boolean = ((a xor b) and (a xor result) and Int.MIN_VALUE) != 0

    private fun unsupported(pc: Int, insn: Int, what: String): Nothing =
        error("Unsupported $what at PC=0x${hex(pc)}, insn=0x${hex(insn)}")

    private fun unsupportedThumb(pc: Int, op: Int, what: String): Nothing =
        error("Unsupported $what at PC=0x${hex(pc)}, op=0x${op.toString(16).padStart(4, '0')}")

    private fun hex(v: Int) = v.toUInt().toString(16).padStart(8, '0')
}
