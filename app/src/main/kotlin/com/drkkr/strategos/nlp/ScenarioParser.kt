package com.drkkr.strategos.nlp

import com.drkkr.strategos.domain.agent.LanguageDetector
import com.drkkr.strategos.domain.model.*

/**
 * Heuristic NLP Scenario Parser.
 *
 * Extracts a first-pass structured draft from free text — stakeholders (by role
 * keywords) and candidate actions (by decision verbs) — so the user starts from
 * a furnished model instead of an empty one. Everything it infers is marked
 * [FactStatus.AI_INFERRED] and is meant to be reviewed/edited.
 *
 * It is deliberately offline and deterministic (no network/LLM). A future
 * version can swap [parse] for an LLM extraction call behind the same contract;
 * the heuristic stays as the no-network fallback.
 */
object ScenarioParser {

    data class ParseResult(
        val draft: Scenario,
        val clarificationQuestions: List<String>,
        val language: String
    )

    // Role nouns that signal a stakeholder. Order matters only for readability.
    private val roleKeywords = listOf(
        "administration", "administrator", "department", "committee", "council",
        "director", "manager", "officer", "regulator", "advocate", "agency",
        "authority", "commission", "government", "minister", "president",
        "chairman", "chairperson", "chair", "board", "union", "consultant",
        "physician", "clinician", "doctor", "nurse", "patient", "counsel",
        "partner", "supplier", "vendor", "client", "customer", "investor",
        "shareholder", "analyst", "lead", "chief", "head", "owner",
        "representative", "stakeholder", "team", "staff", "ceo", "cfo", "coo", "cto"
    )

    // Role nouns whose holder typically carries formal power / veto rights.
    private val highAuthorityRoles = setOf(
        "director", "head", "chief", "ceo", "cfo", "coo", "cto", "president",
        "board", "administration", "regulator", "authority", "government",
        "minister", "commission", "chair", "chairman", "chairperson", "owner"
    )
    private val vetoRoles = setOf(
        "regulator", "authority", "government", "minister", "commission",
        "board", "director", "chief", "president", "council", "union"
    )

    // Decision verbs that signal a candidate action.
    private val actionVerbs = listOf(
        "adopt", "pilot", "implement", "approve", "reject", "veto", "delay",
        "block", "negotiate", "escalate", "withdraw", "exit", "launch", "cancel",
        "postpone", "merge", "acquire", "divest", "expand", "reduce", "invest",
        "settle", "litigate", "mediate", "arbitrate", "restructure", "outsource",
        "terminate", "renew", "suspend", "appeal", "comply", "resist", "cooperate",
        "defect", "collaborate", "compete", "concede", "refuse", "accept", "propose"
    )

    private val leadingNoise = setOf("the", "a", "an", "our", "their", "its", "his", "her", "my", "your", "this", "that")

    fun parse(rawText: String, existingId: String? = null): ParseResult {
        val language = LanguageDetector.detect(rawText)
        val title = extractTitle(rawText)

        val stakeholders = extractStakeholders(rawText)
        val actions = extractActions(rawText, stakeholders)

        val questions = buildClarifications(rawText, stakeholders, actions)

        val assumptions = buildList {
            add(AssumptionEntry("Scenario parsed from free text — all fields require review", FactStatus.AI_INFERRED))
            if (stakeholders.isNotEmpty())
                add(AssumptionEntry("Stakeholders inferred from role keywords in the text", FactStatus.AI_INFERRED))
            if (actions.isNotEmpty())
                add(AssumptionEntry("Actions inferred from decision verbs; owners are best-guess", FactStatus.AI_INFERRED))
            add(AssumptionEntry("Payoffs, beliefs, constraints and evidence not yet populated", FactStatus.UNKNOWN))
        }

        val scenario = Scenario(
            id = existingId ?: java.util.UUID.randomUUID().toString(),
            title = title,
            description = rawText,
            language = language,
            stakeholders = stakeholders,
            actions = actions,
            assumptionLog = assumptions
        )
        return ParseResult(scenario, questions, language)
    }

    // ── Stakeholder extraction ────────────────────────────────────────────────
    private fun extractStakeholders(text: String): List<Stakeholder> {
        // Up to two words preceding a role keyword, e.g. "clinical director",
        // "psychiatry department head".
        val keywordAlt = roleKeywords.joinToString("|")
        val rx = Regex("((?:[A-Za-z][A-Za-z'\\-]*\\s+){0,2})($keywordAlt)\\b", RegexOption.IGNORE_CASE)

        data class Cand(val phrase: String, val keyword: String)
        val found = mutableListOf<Cand>()
        for (m in rx.findAll(text)) {
            val pre = m.groupValues[1].trim()
            val kw = m.groupValues[2].lowercase()
            val preWords = pre.split(Regex("\\s+"))
                .filter { it.isNotBlank() && it.lowercase() !in leadingNoise && !it.endsWith("'s") }
            val phrase = (preWords + m.groupValues[2]).joinToString(" ").trim()
            if (phrase.length in 3..48) found += Cand(titleCase(phrase), kw)
        }

        // Dedup: drop any phrase that is contained within a longer kept phrase.
        val kept = mutableListOf<Cand>()
        for (c in found.sortedByDescending { it.phrase.length }) {
            val norm = c.phrase.lowercase()
            if (kept.none { it.phrase.lowercase().contains(norm) }) kept += c
        }

        return kept.take(6).map { c ->
            val high = c.keyword in highAuthorityRoles
            Stakeholder(
                name = c.phrase,
                role = c.phrase,
                authority = if (high) 7 else 4,
                influence = if (high) 6 else 4,
                canVeto = c.keyword in vetoRoles
            )
        }
    }

    // ── Action extraction ─────────────────────────────────────────────────────
    private fun extractActions(text: String, stakeholders: List<Stakeholder>): List<Action> {
        val verbAlt = actionVerbs.joinToString("|")
        // A decision verb plus the remainder of its clause (stops at punctuation).
        val rx = Regex("\\b($verbAlt)\\b([^.,;:!?]*)", RegexOption.IGNORE_CASE)

        data class Cand(val label: String, val at: Int)
        val cands = mutableListOf<Cand>()
        for (m in rx.findAll(text)) {
            val verb = m.groupValues[1]
            val tail = m.groupValues[2].trim()
            val words = (listOf(verb) + tail.split(Regex("\\s+")).filter { it.isNotBlank() }).take(7)
            val label = words.joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd(' ', '-')
            if (label.length in 3..60) cands += Cand(capitalizeFirst(label), m.range.first)
        }

        // Dedup by normalized label, keep earliest occurrence, cap the list.
        val seen = mutableListOf<Action>()
        val usedNorms = mutableSetOf<String>()
        for (c in cands.sortedBy { it.at }) {
            val norm = c.label.lowercase()
            if (usedNorms.any { it.startsWith(norm) || norm.startsWith(it) }) continue
            usedNorms += norm
            val ownerId = bestOwner(text, c.at, stakeholders)
            seen += Action(stakeholderId = ownerId, label = c.label)
            if (seen.size >= 8) break
        }
        return seen
    }

    /** Assign the action to the stakeholder whose name tokens best match the clause it sits in. */
    private fun bestOwner(text: String, at: Int, stakeholders: List<Stakeholder>): String {
        if (stakeholders.isEmpty()) return ""
        val clauseStart = text.lastIndexOfAny(charArrayOf('.', ';', '\n'), at).let { if (it < 0) 0 else it + 1 }
        val clause = text.substring(clauseStart, at).lowercase()
        val clauseTokens = clause.split(Regex("[^a-z]+")).filter { it.length > 2 }.toSet()
        var best: Stakeholder? = null
        var bestScore = 0
        for (s in stakeholders) {
            val nameTokens = s.name.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
            val score = nameTokens.count { it in clauseTokens }
            if (score > bestScore) { bestScore = score; best = s }
        }
        return if (bestScore > 0) best!!.id else ""
    }

    // ── Clarifications ────────────────────────────────────────────────────────
    private fun buildClarifications(
        text: String,
        stakeholders: List<Stakeholder>,
        actions: List<Action>
    ): List<String> = buildList {
        if (text.trim().length < 30) add("Please describe the situation in more detail.")
        if (stakeholders.isEmpty()) add("Who are the key stakeholders and what do they want?")
        else add("Review the ${stakeholders.size} inferred stakeholder(s); add goals, authority and influence.")
        if (actions.isEmpty()) add("What are the available actions for each party?")
        else add("Review the ${actions.size} inferred action(s); confirm the owner of each.")
        add("What are the payoffs/priorities for each party across outcomes?")
        add("What constraints (legal, ethical, safety) are non-negotiable?")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun detectLanguage(text: String): String {
        val hasTamil = text.any { it in '஀'..'௿' }
        val hasEnglish = text.any { it.isLetter() && it.code < 0x200 }
        return when {
            hasTamil && hasEnglish -> "ta-en"
            hasTamil -> "ta"
            else -> "en"
        }
    }

    private fun extractTitle(text: String): String {
        val firstSentence = text.trim().split(Regex("[.\\n]")).firstOrNull { it.isNotBlank() }?.trim()
            ?: return "Unnamed scenario"
        if (firstSentence.length <= 60) return firstSentence
        // Trim to a word boundary under 60 chars.
        val cut = firstSentence.take(60)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > 20) cut.take(lastSpace) else cut).trim() + "…"
    }

    private fun titleCase(s: String): String =
        s.split(Regex("\\s+")).joinToString(" ") { w ->
            if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1)
        }

    private fun capitalizeFirst(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
}
