package com.drkkr.strategos.domain.human.power

/**
 * Power Mode (blueprint §1, §5): when the user asks for help with something the engine never plans (a false claim, exploiting a
 * weakness, isolation, dependency, a fake friendship, a scapegoat), the coach opens with one of these fixed lines and then gives the
 * strongest legitimate move. One sentence, no lecture, no law or book. Written in Kotlin, never by the model.
 */
object DeclineNotes {
    private val NOTES = mapOf(
        "false_facts" to "I won't help build an offer or claim that isn't true — here's how to create real pressure instead.",
        "exploit_weakness" to "I won't plan around someone's insecurities — here's how to strengthen your own position instead.",
        "isolate" to "I won't help cut someone off from the people around them — here's how to build your own support instead.",
        "make_dependent" to "I won't plan ways to make someone depend on you — here's how to strengthen your own position.",
        "fake_friend" to "I won't help fake a friendship to get information — here's how to find out what you need openly.",
        "scapegoat" to "I won't help pin this on someone who didn't cause it — here's how to deal with what actually happened."
    )

    fun of(userAsksFor: String?): String? = userAsksFor?.let { NOTES[it] }
}
