package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.LanguageDetector
import com.drkkr.strategos.nlp.ScenarioParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {
    @Test
    fun TC_LANG_01_detectsEnglishTamilAndMixed() {
        assertEquals("en", LanguageDetector.detect("Hello"))
        assertEquals("ta", LanguageDetector.detect("வணக்கம்"))
        assertEquals("ta-en", LanguageDetector.detect("Hello வணக்கம்"))
    }

    @Test
    fun scenarioParserDelegatesToDetector() {
        assertEquals("ta-en", ScenarioParser.parse("Hello வணக்கம்").language)
    }
}
