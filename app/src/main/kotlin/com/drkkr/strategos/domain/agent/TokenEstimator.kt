package com.drkkr.strategos.domain.agent

/** Rough token count: (UTF-8 bytes + 3) / 4 (blueprint §10.4). */
object TokenEstimator {
    fun estimate(text: String): Int = (text.toByteArray(Charsets.UTF_8).size + 3) / 4
}
