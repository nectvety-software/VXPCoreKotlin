package vxpcore

class MreEventLoop(
    private val cpu: ArmCpu,
    private val runtime: MreRuntime,
    private val maxInstructionsPerCallback: Long = 2_000_000L,
    private val maxRuntimeMs: Long = 0L
) {
    data class Stats(
        val eventsDispatched: Long,
        val timerCallbacks: Long,
        val timedOut: Boolean
    )

    fun run(vmMain: Int, invokeVmMain: Boolean = true): Stats {
        require(vmMain != 0) { "vm_main/entry address is null" }

        if (invokeVmMain) {
            if (cpu.trace) println("[MRE] invoke vm_main @0x${vmMain.toUInt().toString(16)}")
            cpu.callGuest(vmMain, intArrayOf(), maxInstructionsPerCallback)
        } else if (cpu.trace) {
            println("[MRE] vm_main already invoked by ELF bootstrap")
        }

        if (runtime.exitRequested) return Stats(0, 0, false)

        if (runtime.systemCallback == 0) {
            System.err.println("[WARN] vm_main returned without vm_reg_sysevt_callback(); no CREATE/PAINT callback can be delivered")
            if (runtime.timerCount() == 0) return Stats(0, 0, false)
        } else {
            runtime.postSystemEvent(MreEventId.VM_MSG_CREATE, 0)
            runtime.postSystemEvent(MreEventId.VM_MSG_PAINT, 0)
        }

        val started = System.nanoTime()
        var eventsDispatched = 0L
        var timerCallbacks = 0L
        var timedOut = false

        while (!runtime.exitRequested && !cpu.halted) {
            if (maxRuntimeMs > 0L && (System.nanoTime() - started) / 1_000_000L >= maxRuntimeMs) {
                timedOut = true
                break
            }

            runtime.enqueueDueTimers()
            val event = runtime.pollEvent()
            if (event != null) {
                when (event) {
                    is MreEvent.System -> {
                        val cb = runtime.systemCallback
                        if (cb != 0) {
                            if (cpu.trace) println("[EVT ] system message=${event.message} param=${event.param} cb=0x${cb.toUInt().toString(16)}")
                            cpu.callGuest(cb, intArrayOf(event.message, event.param), maxInstructionsPerCallback)
                            eventsDispatched++
                        }
                    }
                    is MreEvent.Timer -> {
                        if (cpu.trace) println("[EVT ] timer id=${event.timerId} cb=0x${event.callback.toUInt().toString(16)}")
                        cpu.callGuest(event.callback, intArrayOf(event.timerId), maxInstructionsPerCallback)
                        eventsDispatched++
                        timerCallbacks++
                    }
                    is MreEvent.Keyboard -> {
                        val cb = runtime.keyboardCallback
                        if (cb != 0) {
                            if (cpu.trace) println("[EVT ] key event=${event.eventType} key=${event.keyCode} cb=0x${cb.toUInt().toString(16)}")
                            cpu.callGuest(cb, intArrayOf(event.eventType, event.keyCode), maxInstructionsPerCallback)
                            eventsDispatched++
                        }
                    }
                    is MreEvent.Pen -> {
                        val cb = runtime.penCallback
                        if (cb != 0) {
                            if (cpu.trace) println("[EVT ] pen event=${event.eventType} x=${event.x} y=${event.y} cb=0x${cb.toUInt().toString(16)}")
                            cpu.callGuest(cb, intArrayOf(event.eventType, event.x, event.y), maxInstructionsPerCallback)
                            eventsDispatched++
                        }
                    }
                }
                continue
            }

            // MRE is event-driven. If nothing is ready, yield instead of burning a host CPU core.
            val waitNs = runtime.nanosUntilNextTimer()
            val sleepMs = when {
                waitNs == null -> 2L
                waitNs <= 0L -> 0L
                else -> (waitNs / 1_000_000L).coerceIn(1L, 5L)
            }
            if (sleepMs > 0L) Thread.sleep(sleepMs) else Thread.yield()
        }

        return Stats(eventsDispatched, timerCallbacks, timedOut)
    }
}
