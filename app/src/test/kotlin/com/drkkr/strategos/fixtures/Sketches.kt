package com.drkkr.strategos.fixtures

/** Model replies for the loan story, as the one-shot flow expects them. */
object Sketches {
    private val mine = listOf("Co-sign", "Give a fixed amount", "Wait")
    private val his = listOf("Take the loan", "Give up the shop", "Ask parents")

    /** First message: 4 people, 3 + 3 options, all 9 outcomes for both players, one limit. */
    val loanStory: String = buildString {
        append("""{"goal":"Help my brother without risking our savings or peace at home",""")
        append(""""people":[{"name":"You","role":"PLAYER","wants":"Keep peace at home","fears":"Losing our savings"},""")
        append("""{"name":"Brother","role":"PLAYER","wants":"Open his shop"},""")
        append("""{"name":"Wife","role":"INFLUENCER","veto":true,"fears":"Debt"},{"name":"Parents","role":"INFLUENCER"}],""")
        append(""""options":[""" + mine.joinToString(",") { """{"for":"You","label":"$it"}""" } + "," + his.joinToString(",") { """{"for":"Brother","label":"$it"}""" } + "],")
        append(""""dims":["WELLBEING","RELATIONSHIPS","COST"],"outcomes":[""")
        append(mine.indices.flatMap { i -> his.indices.map { j ->
            """{"picks":{"You":"${mine[i]}","Brother":"${his[j]}"},"scores":{"You":[${3 + i + j},${6 - j},${8 - 3 * i}],"Brother":[${8 - 2 * i - j},${5 + i - j},${2 + j}]}}"""
        } }.joinToString(","))
        append("""],"limits":[{"label":"Don't risk our house","kind":"ETHICAL"}]}""")
    }

    /** Follow-up scores follow the saved dims order (COST, RELATIONSHIPS, WELLBEING). Same people again (must not be duplicated), one new detail, and one changed outcome. */
    val followUp: String = """{"people":[{"name":"Brother","role":"PLAYER","fears":"Losing face"},{"name":"You","role":"PLAYER"}],
        "options":[{"for":"You","label":"wait"}],
        "outcomes":[{"picks":{"You":"Wait","Brother":"Ask parents"},"scores":{"You":[1,8,9],"Brother":[3,6,6]}}]}"""

    /** A first reply that forgot the other player's options and every outcome (seen live). */
    val loanStoryIncomplete: String = """{"goal":"Help my brother without risking our savings",
        "people":[{"name":"You","role":"PLAYER"},{"name":"Brother","role":"PLAYER"},{"name":"Wife","role":"INFLUENCER","veto":true}],
        "options":[{"for":"You","label":"Co-sign"},{"for":"You","label":"Wait"}]}"""

    /** The targeted follow-up: Brother's options and all four outcomes, by the same names. */
    val loanRepair: String = """{"dims":["WELLBEING","RELATIONSHIPS","COST"],
        "options":[{"for":"Brother","label":"Take the loan"},{"for":"Brother","label":"Give up the shop"}],
        "outcomes":[{"picks":{"You":"Co-sign","Brother":"Take the loan"},"scores":{"You":[4,6,8],"Brother":[8,5,2]}},
                    {"picks":{"You":"Co-sign","Brother":"Give up the shop"},"scores":{"You":[5,5,5],"Brother":[3,4,2]}},
                    {"picks":{"You":"Wait","Brother":"Take the loan"},"scores":{"You":[7,4,1],"Brother":[5,3,3]}},
                    {"picks":{"You":"Wait","Brother":"Give up the shop"},"scores":{"You":[8,6,1],"Brother":[2,4,2]}}]}"""

    const val ASK = """{"ask":"Who else is affected, and what could you do?"}"""
}
