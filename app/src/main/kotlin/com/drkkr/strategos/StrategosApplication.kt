package com.drkkr.strategos

import android.app.Application

class StrategosApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Tests only: swap in a container with an in-memory database and a fake LLM client. */
    internal fun replaceContainerForTests(c: AppContainer) {
        container = c
    }
}
