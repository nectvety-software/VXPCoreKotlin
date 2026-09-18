package android.content
open class Context {
    open val applicationContext: Context get() = this
    open fun getSystemService(name: String): Any? = null
    companion object { const val AUDIO_SERVICE = "audio" }
}
