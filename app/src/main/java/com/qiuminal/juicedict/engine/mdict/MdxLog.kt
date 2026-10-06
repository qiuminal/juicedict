package com.qiuminal.juicedict.engine.mdict

/**
 * Diagnostics sink for the MDict parser.
 *
 * The parser is a pure-JVM module so it can be unit tested without an Android runtime, and
 * `android.util.Log` would break that (stub methods throw in plain JVM tests). Recoverable
 * problems — a stale header entry count, a truncated tail, an unreadable resource pack —
 * are worth surfacing in logcat but must never fail a lookup, so they are routed through
 * this indirection instead. The app layer installs [sink]; tests and tools leave it null
 * and the messages are simply dropped.
 */
internal object MdxLog {

    /** Receives `(tag, message)`. Null when nothing is listening. */
    @JvmField
    var sink: ((tag: String, message: String) -> Unit)? = null

    fun warn(tag: String, message: String) {
        sink?.invoke(tag, message)
    }

    fun warn(tag: String, message: String, error: Throwable) {
        sink?.invoke(tag, "$message: $error")
    }
}
