package com.drkkr.strategos.ui.calm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The destinations in the calm UI. Overview and Talk live under the Projects tab; the dock has the three tops. */
sealed interface CalmRoute {
    data object Projects : CalmRoute
    data class Overview(val projectId: String) : CalmRoute
    data class Talk(val projectId: String) : CalmRoute
    data object Journal : CalmRoute
    data object Settings : CalmRoute

    /** Which dock tab this route belongs to. */
    val tab: DockTab
        get() = when (this) {
            Journal -> DockTab.JOURNAL
            Settings -> DockTab.SETTINGS
            else -> DockTab.PROJECTS
        }
}

enum class DockTab { PROJECTS, JOURNAL, SETTINGS }

/**
 * A tiny hand-rolled navigator (no nav dependency): one back stack per dock tab, so switching tabs
 * keeps each tab's depth. [back] returns false when there is nowhere left to go (let the system exit).
 */
@Stable
class CalmNav {
    private val stacks = mutableMapOf(
        DockTab.PROJECTS to listOf<CalmRoute>(CalmRoute.Projects),
        DockTab.JOURNAL to listOf<CalmRoute>(CalmRoute.Journal),
        DockTab.SETTINGS to listOf<CalmRoute>(CalmRoute.Settings)
    )
    var tab by mutableStateOf(DockTab.PROJECTS)
        private set
    var current by mutableStateOf<CalmRoute>(CalmRoute.Projects)
        private set

    /** True when the current tab has somewhere to go back to (so the system can exit at a root). */
    val canBack: Boolean get() = stacks.getValue(tab).size > 1

    private fun sync() { current = stacks.getValue(tab).last() }

    fun selectTab(t: DockTab) { tab = t; sync() }

    fun go(route: CalmRoute) {
        val t = route.tab
        tab = t
        stacks[t] = stacks.getValue(t) + route
        sync()
    }

    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.size <= 1) return false
        stacks[tab] = stack.dropLast(1)
        sync()
        return true
    }
}

@Composable
fun rememberCalmNav(): CalmNav = remember { CalmNav() }
