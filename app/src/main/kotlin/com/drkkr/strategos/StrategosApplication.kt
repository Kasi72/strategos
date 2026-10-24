package com.drkkr.strategos

import android.app.Application

class StrategosApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: trace requests and replies to logcat (tag StrategosDebug). Release builds never set this.
        if (BuildConfig.DEBUG) com.drkkr.strategos.llm.DebugLog.sink = { m -> m.chunked(3_500).forEachIndexed { i, c -> android.util.Log.d("StrategosDebug", if (i == 0) c else "…$c") } }
        container = AppContainer(this)
    }

    /** Tests only: swap in a container with an in-memory database and a fake LLM client. */
    internal fun replaceContainerForTests(c: AppContainer) {
        container = c
    }
}
