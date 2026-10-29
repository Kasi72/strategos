package com.drkkr.strategos.llm

/**
 * Developer-only trace of requests, replies and turn decisions. Off unless [sink] is set, and the app sets it only in
 * debug builds (StrategosApplication), so a release build never writes any of this. The API key is never passed in.
 */
object DebugLog {
    @Volatile var sink: ((String) -> Unit)? = null

    fun d(message: () -> String) { sink?.invoke(message()) }
}
