package com.drkkr.strategos

import com.drkkr.strategos.ui.calm.talk.PendingFirstMessage
import org.junit.Assert.*
import org.junit.Test

class PendingFirstMessageTest {
    @Test fun TC_ONB_01_takenOnceAndOnlyForItsProject() {
        PendingFirstMessage.set("p1", "hello")
        assertNull(PendingFirstMessage.take("p2"))
        assertEquals("hello", PendingFirstMessage.take("p1"))
        assertNull(PendingFirstMessage.take("p1"))
    }
}
