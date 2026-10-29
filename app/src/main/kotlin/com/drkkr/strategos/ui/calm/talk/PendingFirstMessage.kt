package com.drkkr.strategos.ui.calm.talk

/** The text a new project was started with, held until Talk opens that project and sends it. In memory only. */
object PendingFirstMessage {
    private var projectId: String? = null
    private var text: String? = null

    @Synchronized fun set(id: String, message: String) { projectId = id; text = message }

    @Synchronized fun take(id: String): String? =
        if (projectId == id) text.also { projectId = null; text = null } else null
}

/** The topic the Overview asked Talk to open. In memory only. */
object PendingThread {
    private var projectId: String? = null
    private var threadId: String? = null

    @Synchronized fun set(project: String, thread: String) { projectId = project; threadId = thread }

    @Synchronized fun take(project: String): String? =
        if (projectId == project) threadId.also { projectId = null; threadId = null } else null
}

/** Remembers which projects were already offered an automatic title this session, so it is asked for at most once. */
object AutoNaming {
    private val tried = mutableSetOf<String>()
    @Synchronized fun begin(projectId: String): Boolean = tried.add(projectId)
}
