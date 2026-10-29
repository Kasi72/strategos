package com.drkkr.strategos

import com.drkkr.strategos.ui.calm.CalmNav
import com.drkkr.strategos.ui.calm.CalmRoute
import com.drkkr.strategos.ui.calm.DockTab
import org.junit.Assert.*
import org.junit.Test

class CalmNavTest {
    @Test fun TC_NAV_01_backFromAProjectsRootExits() {
        val n = CalmNav()
        assertFalse(n.canBack); assertFalse(n.back())
    }

    @Test fun TC_NAV_02_backFromAnotherTabReturnsToThePreviousTab() {
        val n = CalmNav()
        n.selectTab(DockTab.SETTINGS)
        assertTrue(n.canBack); assertTrue(n.back())
        assertEquals(DockTab.PROJECTS, n.tab); assertEquals(CalmRoute.Projects, n.current)
        assertFalse(n.back())
    }

    @Test fun TC_NAV_03_backWalksTabsInReverseOrderWithoutLoops() {
        val n = CalmNav()
        n.selectTab(DockTab.JOURNAL); n.selectTab(DockTab.SETTINGS); n.selectTab(DockTab.JOURNAL)
        assertTrue(n.back()); assertEquals(DockTab.SETTINGS, n.tab)
        assertTrue(n.back()); assertEquals(DockTab.PROJECTS, n.tab)
        assertFalse(n.back())
    }

    @Test fun TC_NAV_04_backInsideATabPopsTheScreenFirst() {
        val n = CalmNav()
        n.go(CalmRoute.Overview("p1")); n.go(CalmRoute.Talk("p1"))
        n.selectTab(DockTab.SETTINGS)               // e.g. "Open Settings" from Talk
        assertTrue(n.back()); assertEquals(CalmRoute.Talk("p1"), n.current)   // back to where I was
        assertTrue(n.back()); assertEquals(CalmRoute.Overview("p1"), n.current)
        assertTrue(n.back()); assertEquals(CalmRoute.Projects, n.current)
        assertFalse(n.back())
    }
}
