package com.drkkr.strategos.domain.human

/** What the radar found: at most two things to watch for, or a reason to stop giving advice and offer support instead. */
data class RadarResult(val watchFor: List<WatchFor>, val routeToSupport: Boolean)

/**
 * The other half of the Human Layer: not what the user can do to others, but what may be being done TO them, and what protects
 * them. It reads only behaviour the user described (the `their_tactics` tags), never a guess, and the words it shows describe the
 * behaviour without accusing anyone.
 *
 *  - In an intimate or family relationship the coercive-control patterns (isolation, keeping you dependent, warmth and coldness that
 *    alternate; see [CoerciveControl] for the exact rules) are not "tactics to counter": the user is routed to support and no advice
 *    about handling the person is given (invariant I-H5). In a friendship they are ordinary watch-for items after two turns.
 *  - Anything else is shown only after it has been described on two turns (one delay or one quiet moment is not a pattern), at most two
 *    at a time, newest first.
 *  - [WatchFor.source] (the law or chapter it comes from) is for tests and the safety review only; it never reaches the coach or the user.
 */
object ManipulationRadar {
    private class Entry(val sign: String, val protect: String, val source: String)

    private val entries: Map<String, Entry> = mapOf(
        "controls_the_options" to Entry("The choices you're offered all seem to suit them.", "Name a third option out loud before you choose.", "L31 reversal"),
        "gives_then_demands" to Entry("The favours come right before a big ask.", "Accept or decline the favour separately from the ask.", "L12, L40"),
        "guilt_or_gratitude" to Entry("Past favours keep coming up when they want a yes.", "Decide on the merits; thank them and still answer the question.", "L13 (reversed)"),
        "silent_treatment" to Entry("Long silences keep leaving you to do the chasing.", "Say once when you're available, then stop chasing.", "L16 (reversed)"),
        "public_undermining" to Entry("You're being corrected or doubted in front of others.", "Take it private and put the facts in writing.", "L5, L1 (reversed)"),
        "moves_goalposts" to Entry("The target changes after you meet it.", "Agree the finish line in writing first.", "L31; Nudge (feedback)"),
        "flattery_before_ask" to Entry("Praise arrives right before a request.", "Pause and decide on the request alone.", "L12"),
        "creates_chaos" to Entry("Their crises keep pulling you off course.", "Set what you will and won't do, and protect your own time.", "L10, reframed as a boundary"),
        "artificial_deadline" to Entry("The pressure to decide right now may be manufactured.", "Ask for the reason behind the deadline; take a night if the decision is big and rare.", "L35; Nudge Ch.17"),
        "extreme_anchor" to Entry("Their first number is setting the range.", "Re-anchor on your own walk-away and an outside yardstick.", "L28; Nudge Ch.1"),
        "self_serving_default" to Entry("What happens if you do nothing has been set up in their favour.", "Make an active choice, and ask what the default would be if you set it.", "Nudge Ch.4/8/17"),
        "adds_friction_to_your_exit" to Entry("Saying no or leaving keeps getting made harder.", "Name the exit you want and put it in writing.", "Nudge Ch.13"),
        "reaches_you_through_others" to Entry("They're working on you through someone close to you.", "Talk to the person directly, and make up your own mind.", "L33"),
        // The three coercive patterns. Close relationships never get these (they route to support); at work they are ordinary power dynamics.
        "hot_cold" to Entry("Warmth and coldness alternate unpredictably.", "Fix your position and write agreements down.", "L17"),
        "isolates_you" to Entry("You're being kept apart from the people who could back you up.", "Keep your own contacts and sources outside this person.", "L42 reversed"),
        "keeps_you_dependent" to Entry("Things are set up so you need them for everything.", "Build a second option or source so you don't rely on one person.", "L11 reversed"),
        // Power Mode: unusually curious about your plans or limits.
        "probes_you" to Entry("They're digging for your limits.", "Answer questions about your limits with ranges, not your bottom line.", "L14 (defensive)")
    )

    /** The radar's words for one pattern, whether or not it is confirmed yet (Power Read shows it from the first mention). */
    fun describe(tag: String): WatchFor? = entries[tag]?.let { WatchFor(tag, it.sign, it.protect, it.source) }

    fun scan(ctx: HumanContext?, domain: RelationshipDomain): RadarResult {
        if (ctx == null) return RadarResult(emptyList(), false)
        if (CoerciveControl.routes(ctx, domain)) return RadarResult(emptyList(), true)
        // In an intimate or family relationship these three never appear as an ordinary tip (they route, or they have expired).
        val skip = if (CoerciveControl.isRoutingDomain(domain)) CoerciveControl.TAGS else emptySet()
        val shown = ctx.confirmedTactics().filter { it !in skip }.mapNotNull { tag -> entries[tag]?.let { WatchFor(tag, it.sign, it.protect, it.source) } }.take(2)
        return RadarResult(shown, false)
    }
}
