package com.drkkr.strategos.ui.calm.talk

/** The text a new project was started with, held until Talk opens that project and sends it. In memory only. */
object PendingFirstMessage {
    private var projectId: String? = null
    private var text: String? = null

    @Synchronized fun set(id: String, message: String) { projectId = id; text = message }

    @Synchronized fun take(id: String): String? =
        if (projectId == id) text.also { projectId = null; text = null } else null
}
