package com.drkkr.strategos.domain.human.power

/**
 * Plain explanations for the law and chapter labels on the Power Read card (STEPS PM5.2). Written in our own words, two or three
 * short lines each; no quotes from the books. Each sheet links to the book's Wikipedia page for more.
 */
object LawNotes {
    const val LAWS_URL = "https://en.wikipedia.org/wiki/The_48_Laws_of_Power"
    const val NUDGE_URL = "https://en.wikipedia.org/wiki/Nudge_(book)"

    data class Note(val label: String, val text: String, val url: String)

    private val LAWS = mapOf(
        "L1" to "Don't make the person above you look worse than you. Showing them up in front of others tends to cost you more than it gains.",
        "L3" to "Keep your exact intentions and limits to yourself. Once the other side knows your bottom line, they negotiate to it.",
        "L4" to "Say less than you need to. The more you explain, the more you reveal and the more there is to argue with.",
        "L5" to "Your reputation does a lot of your work for you. Protect it, because it is slow to build and quick to lose.",
        "L8" to "Get the other side to come to you. The one who moves first often reveals more and sets the terms for the other.",
        "L10" to "Some people bring a constant stream of crises. Keeping some distance protects your time and judgement.",
        "L11" to "Being hard to replace is real leverage. The honest version is owning something genuinely valuable, not making others helpless.",
        "L12" to "A gift or favour placed just before a request can soften your judgement. Treat the favour and the ask separately.",
        "L13" to "Appeal to what the other person gains, not to what they owe you. People act on interest more reliably than on gratitude.",
        "L14" to "Friendly questions can be a way of gathering intelligence. Notice when someone keeps asking about your limits.",
        "L16" to "Absence can raise your value and silence can be used as pressure. Stop chasing and say once when you are available.",
        "L17" to "Unpredictability keeps others off balance. When it is done to you, fix your own position and write agreements down.",
        "L18" to "Don't stand alone. Having a few people who already agree with you changes how a meeting goes.",
        "L20" to "Keep your options open and don't commit too early. Real alternatives are what make 'no' a credible answer.",
        "L22" to "Knowing when to yield matters. Reversed: a real willingness to walk away is what gives you strength in a deal.",
        "L23" to "Concentrate your effort where it counts, including on the person who actually makes the decision.",
        "L28" to "Act with confidence. A hesitant ask invites a smaller answer; a firm, justified one sets a stronger frame.",
        "L30" to "Make it look effortless. Showing how much you need something, or how hard it was, weakens your position.",
        "L31" to "Whoever sets the options shapes the choice. Lay out real options honestly, and watch for a menu built against you.",
        "L33" to "Pressure can be applied through people close to you. Talk to the person directly and make up your own mind.",
        "L34" to "Act with the standing your role gives you, and take the matter to the level where it is decided.",
        "L35" to "Timing is a lever. Slow down when time is on your side; when it isn't, set a real, specific deadline.",
        "L36" to "Not every provocation deserves an answer. Ignoring the jab and dealing with the substance later keeps control with you.",
        "L39" to "Stirring someone up makes them easier to steer. Staying calm when provoked keeps your judgement intact.",
        "L40" to "Things given free often come with strings. Be clear about what you are agreeing to in return.",
        "L42" to "Groups often follow one key person. Win that person over, or go to where the decision is really made.",
        "L43" to "People resist being forced and respond to being persuaded. Win support before the meeting, not in it.",
        "L48" to "In a contest, being predictable lets the other side plan against you. Vary your approach and keep options open."
    )
    private val NUDGE = mapOf(
        "Ch.1" to "The first number on the table pulls everything toward it. Anchoring works on both sides of a deal, so set yours with care.",
        "Ch.4" to "What happens if nobody chooses (the default) decides a lot. Ask who set it and make an active choice.",
        "Ch.8" to "Defaults and framing shape decisions about money. A choice laid out in your favour is worth noticing.",
        "Ch.13" to "Making it harder to leave keeps people in. Name the exit you want and put it in writing.",
        "Ch.15" to "Each side tends to think its own view of a fair deal is the objective one. An outside yardstick helps both.",
        "Ch.17" to "If you'd be uncomfortable explaining a nudge openly, it probably isn't a fair one. Pressure that can't be explained is a warning sign."
    )

    /** The notes behind a card label such as "L3/L4" or "Nudge Ch.15". Unknown labels get none. */
    fun forLabel(label: String): List<Note> {
        val laws = Regex("""L\d+""").findAll(label).mapNotNull { m -> LAWS[m.value]?.let { Note(m.value, it, LAWS_URL) } }.toList()
        val chapters = Regex("""Ch\.\s?(\d+)""").findAll(label).mapNotNull { m -> NUDGE["Ch.${m.groupValues[1]}"]?.let { Note("Nudge Ch.${m.groupValues[1]}", it, NUDGE_URL) } }.toList()
        return laws + chapters
    }
}
