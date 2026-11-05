package com.drkkr.strategos.domain.human

/**
 * One thing to watch for: a pattern someone is using ON the user, in plain words, and the protective move.
 * [source] (the law or chapter it comes from) is for tests and clinician review only; it never reaches the coach or the user.
 * Filled by the radar in a later stage; the type exists now so the directive has its place.
 */
data class WatchFor(val pattern: String, val sign: String, val protect: String, val source: String)
