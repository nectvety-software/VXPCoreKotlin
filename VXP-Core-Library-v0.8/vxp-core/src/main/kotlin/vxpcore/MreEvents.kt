package vxpcore

object MreEventId {
    const val VM_MSG_PAINT = 1
    const val VM_MSG_CREATE = 4

    const val VM_KEY_EVENT_UP = 1
    const val VM_KEY_EVENT_DOWN = 2
    const val VM_KEY_EVENT_LONG_PRESS = 3
    const val VM_KEY_EVENT_REPEAT = 4

    const val VM_PEN_EVENT_DOWN = 1
    const val VM_PEN_EVENT_UP = 2
    const val VM_PEN_EVENT_MOVE = 3
}

sealed interface MreEvent {
    data class System(val message: Int, val param: Int = 0) : MreEvent
    data class Timer(val timerId: Int, val callback: Int) : MreEvent
    data class Keyboard(val eventType: Int, val keyCode: Int) : MreEvent
    data class Pen(val eventType: Int, val x: Int, val y: Int) : MreEvent
}
