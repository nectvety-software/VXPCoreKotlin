package android.os
object Build { object VERSION { @JvmField var SDK_INT: Int = 35 } }
open class Looper
open class Handler(val looper: Looper? = null)
open class HandlerThread(name: String) {
    val looper = Looper()
    fun start() = Unit
    fun quitSafely(): Boolean = true
}
