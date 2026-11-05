package com.drkkr.strategos.domain.human

/**
 * Every number the Human Layer uses, in one place (invariant I-H6: bounded, named, sourced, stress-tested).
 * All shifts are in points on the engine's 0-10 payoff scale and apply to the COUNTERPART's perceived payoffs only.
 */
data class HumanConstants(
    /** Loss weight from the status quo. Nudge Ch.1 ("losses loom about twice as large as gains"); 2.0 rather than the 2.25 of prospect theory. */
    val lossAversion: Double = 2.0,
    /** Points a higher-status counterpart loses when corrected in public. 48 Laws L1 (never outshine the master) and L9. Judgement call, bounded and stress-tested. */
    val faceCost: Double = 1.5,
    /** Share of [faceCost] that a private correction still costs. Judgement call (a private correction stings less, not zero). */
    val privateFaceShare: Double = 1.0 / 3,
    /** Shift for appealing to gratitude (minus) or to their own interest (plus). 48 Laws L13. Judgement call. */
    val interestShift: Double = 0.5,
    /** Bonus to doing nothing when the ask is open-ended and has no deadline. Nudge Ch.1 ("yeah, whatever") and Ch.5 (defaults). */
    val inertia: Double = 0.5,
    /** Cost per extra step the counterpart must take to say yes. Nudge Ch.3 (channel factors). */
    val frictionPerStep: Double = 0.25,
    /** Ceiling on the total friction cost. A cap, not an effect size, so [scaled] leaves it alone. */
    val frictionMax: Double = 0.75,
    /** Public-setting shift (minus when they must break from a group, plus for a commitment that sticks). Nudge Ch.3 (conformity); 48 Laws L1, L38. */
    val settingShift: Double = 0.5,
    /** Public-setting bonus for a lasting commitment when there is no face cost. Nudge Ch.3 (public commitments). */
    val commitShift: Double = 0.25,
    /** Largest total adjustment to any one counterpart cell. A cap, so [scaled] leaves it alone. */
    val cellCap: Double = 2.0,
    /** Smallest gain in the chance they say yes for a tactic to be offered. A threshold, so [scaled] leaves it alone. */
    val deltaPMin: Double = 0.05,
    /** Most delivery tactics per move. A count, so [scaled] leaves it alone. */
    val maxTactics: Int = 2
) {
    /**
     * Multiplies every effect size by [f], for the sensitivity check (all halved, all doubled). Thresholds, caps and
     * counts stay put, and so does [privateFaceShare] (a share of [faceCost], which already scales).
     */
    fun scaled(f: Double): HumanConstants = copy(
        lossAversion = lossAversion * f,
        faceCost = faceCost * f,
        interestShift = interestShift * f,
        inertia = inertia * f,
        frictionPerStep = frictionPerStep * f,
        settingShift = settingShift * f,
        commitShift = commitShift * f
    )

    companion object {
        val DEFAULT = HumanConstants()
    }
}
