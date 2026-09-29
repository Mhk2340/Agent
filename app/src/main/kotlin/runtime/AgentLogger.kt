package runtime

/**
 * GEN-011 — Logger (Timber / Structured standard abstraction)
 */
object AgentLogger {
    interface LogSink {
        fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null)
    }

    private var sink: LogSink = object : LogSink {
        override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
            println("[$priority] [$tag] $message" + (throwable?.let { " : ${it.message}" } ?: ""))
        }
    }

    fun setSink(newSink: LogSink) {
        sink = newSink
    }

    fun d(tag: String, message: String) = sink.log(3, tag, message)
    fun i(tag: String, message: String) = sink.log(4, tag, message)
    fun w(tag: String, message: String) = sink.log(5, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = sink.log(6, tag, message, throwable)
}
