package com.drkkr.strategos.framework.behavioral

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.*

class CoalitionAnalysisModule : FrameworkModule {
    override val id = "coalition-analysis"
    override val name = "Coalition & Veto Player Analysis"
    override val version = "1.0"
    override val category = "Behavioral"

    override fun canAnalyze(model: Scenario): Applicability {
        if (model.stakeholders.size < 3) return Applicability(0.0, listOf("Need ≥3 stakeholders for coalition analysis."))
        val hasAuthorityData = model.stakeholders.any { it.authority > 0 }
        if (!hasAuthorityData) return Applicability(
            0.45,
            listOf(
                "No authority data — using influence scores as proxy. Add authority values for accurate veto-player identification.",
                "Identifies declared coalitions and formal veto holders from canVeto flags."
            )
        )
        return Applicability(
            0.82,
            listOf(
                "Identifies veto players, minimum winning coalitions, and blocking coalitions.",
                "Estimates Shapley-inspired pivotal influence for each stakeholder.",
                "Uses declared coalition links (coalitionsWith) and formal authority/veto data.",
                "Applies Tsebelis (2002) veto player theory: more veto players = higher status-quo bias."
            )
        )
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()
        val stakeholders = model.stakeholders
        if (stakeholders.isEmpty()) return emptyResult()

        // Use authority if available, otherwise influence as proxy
        val weightFn: (Stakeholder) -> Int = { s -> if (s.authority > 0) s.authority else s.influence }
        val totalWeight = stakeholders.sumOf { weightFn(it) }.coerceAtLeast(1)
        val majorityThreshold = totalWeight.toDouble() / 2.0

        // ── 1. Veto player analysis (Tsebelis 2002) ──────────────────────────
        val formalVetoPlayers = stakeholders.filter { it.canVeto }
        if (formalVetoPlayers.isEmpty()) {
            findings += Finding(
                "No formal veto players registered",
                "No stakeholder has canVeto=true. If any actor has formal blocking rights (board approval, regulatory sign-off, union veto), mark them to enable blocking coalition analysis.",
                Finding.Severity.NOTE
            )
        } else {
            findings += Finding(
                "Formal veto players (${formalVetoPlayers.size}): ${formalVetoPlayers.joinToString(", ") { it.name }}",
                buildString {
                    append("Veto player theory (Tsebelis 2002): each additional veto player increases the difficulty of policy change by shrinking the winset — the range of outcomes preferred to the status quo by ALL veto players simultaneously.\n\n")
                    for (vp in formalVetoPlayers) {
                        append("• ${vp.name} [authority ${vp.authority}, influence ${vp.influence}]: can unilaterally block any decision requiring their assent.\n")
                    }
                    if (formalVetoPlayers.size >= 3) {
                        append("\n${formalVetoPlayers.size} veto players — status-quo bias is HIGH. Any proposal must satisfy all ${formalVetoPlayers.size} simultaneously. Package deals and side payments become necessary.")
                    } else if (formalVetoPlayers.size == 2) {
                        append("\n2 veto players — status-quo bias is MODERATE. Both must be aligned for change to proceed.")
                    }
                },
                if (formalVetoPlayers.size >= 3) Finding.Severity.WARNING else Finding.Severity.NOTE
            )
        }

        // ── 2. Declared coalitions ────────────────────────────────────────────
        val coalitionLinks = buildCoalitionLinks(stakeholders)
        val coalitionGroups = findCoalitionGroups(stakeholders, coalitionLinks)

        if (coalitionGroups.isNotEmpty()) {
            findings += Finding(
                "${coalitionGroups.size} declared coalition group(s) identified",
                buildString {
                    append("Declared coalition links (from coalitionsWith field):\n")
                    for ((i, group) in coalitionGroups.withIndex()) {
                        val groupWeight = group.sumOf { weightFn(it) }
                        val groupPct = (groupWeight.toDouble() / totalWeight * 100).toInt()
                        val hasVeto = group.any { it.canVeto }
                        append("Group ${i + 1}: ${group.joinToString(", ") { it.name }} — combined authority $groupWeight/${totalWeight} (${groupPct}%)${if (hasVeto) " [includes veto player]" else ""}\n")
                    }
                },
                Finding.Severity.NOTE
            )

            // Check if any coalition group holds majority
            for ((i, group) in coalitionGroups.withIndex()) {
                val groupWeight = group.sumOf { weightFn(it) }
                if (groupWeight > majorityThreshold) {
                    findings += Finding(
                        "Coalition group ${i + 1} holds MAJORITY: ${group.joinToString(", ") { it.name }}",
                        "Combined authority ${groupWeight}/${totalWeight} exceeds the majority threshold of ${majorityThreshold.toInt()}. This group can pass or block proposals without additional allies. Decision owner must engage this coalition directly.",
                        Finding.Severity.WARNING
                    )
                }
            }
        }

        // ── 3. Minimum winning coalition (Riker 1962) ────────────────────────
        val mwc = findMinimumWinningCoalition(stakeholders, weightFn, majorityThreshold)
        if (mwc != null) {
            val mwcWeight = mwc.sumOf { weightFn(it) }
            findings += Finding(
                "Minimum winning coalition (Riker 1962): ${mwc.joinToString(", ") { it.name }}",
                buildString {
                    append("Riker's minimum winning coalition principle: rational actors form the smallest coalition needed to win, to maximise per-member payoff. This coalition (authority ${mwcWeight}/${totalWeight}) is the most cost-efficient path to a majority.\n\n")
                    append("Members: ${mwc.joinToString(", ") { s -> "${s.name} [auth ${weightFn(s)}]" }}\n")
                    append("Excludes: ${stakeholders.filter { it !in mwc }.joinToString(", ") { it.name }} — these actors are unnecessary to the majority and can be excluded from concessions.")
                },
                Finding.Severity.INFO
            )

            val mwcOwner = mwc.firstOrNull()
            mwcOwner?.let {
                recs += RecommendedAction(
                    it.id,
                    "Build support among: ${mwc.joinToString(", ") { s -> s.name }}",
                    "Minimum winning coalition strategy: secure exactly ${mwc.size} key stakeholder(s) instead of universal buy-in. Concentrate relationship investment on this coalition. Riker's principle — surplus coalitions dilute payoffs and generate unnecessary commitments.",
                    lens = RecommendedAction.Lens.RELATIONSHIP
                )
            }
        }

        // ── 4. Blocking coalition ─────────────────────────────────────────────
        val potentialBlockers = findBlockingCoalition(stakeholders, weightFn, majorityThreshold)
        if (potentialBlockers != null && potentialBlockers.size <= stakeholders.size / 2) {
            findings += Finding(
                "Minimum blocking coalition: ${potentialBlockers.joinToString(", ") { it.name }}",
                "This group can defeat any proposal by coordinating. Combined authority: ${potentialBlockers.sumOf { weightFn(it) }}/${totalWeight}. If these stakeholders align against the proposed decision, no majority can be assembled without at least one of them switching. Monitor alignment among this group.",
                Finding.Severity.WARNING
            )
        }

        // ── 5. Pivotal / swing stakeholder (Banzhaf-inspired) ────────────────
        val pivotal = findPivotalStakeholder(stakeholders, weightFn, majorityThreshold)
        if (pivotal != null) {
            val sortedByAuth = stakeholders.sortedByDescending { weightFn(it) }
            val pivotalRank = sortedByAuth.indexOfFirst { it.id == pivotal.id } + 1
            findings += Finding(
                "Pivotal stakeholder (rank #$pivotalRank by authority): ${pivotal.name}",
                buildString {
                    append("${pivotal.name} is the most frequently decisive voter in coalition orderings — their support swings the outcome from minority to majority in the largest share of possible coalition formations.\n")
                    append("Authority: ${weightFn(pivotal)}, Influence: ${pivotal.influence}, Veto: ${pivotal.canVeto}.\n")
                    append("Implication: disproportionate negotiating leverage. ${pivotal.name} should receive priority engagement, independent of their formal position in the hierarchy.")
                },
                Finding.Severity.WARNING
            )
            recs += RecommendedAction(
                pivotal.id,
                "Priority engagement: ${pivotal.name} is the pivotal decision actor",
                "Pivotal player analysis: securing ${pivotal.name}'s support is necessary and often sufficient for majority formation. Prioritise this relationship above others in the coalition.",
                lens = RecommendedAction.Lens.RELATIONSHIP
            )
        }

        // ── 6. Exit options and outside strategies ───────────────────────────
        val exitCapable = stakeholders.filter { it.canExit && weightFn(it) >= 4 }
        if (exitCapable.isNotEmpty()) {
            findings += Finding(
                "Exit-capable stakeholders with significant authority: ${exitCapable.joinToString(", ") { it.name }}",
                "These actors can credibly threaten to withdraw from the process — removing their authority weight from any coalition and potentially delegitimising the outcome. Exit threat functions as informal veto power. Address their interests before announcing decisions.",
                Finding.Severity.WARNING
            )
        }

        // ── 7. Isolated stakeholders ─────────────────────────────────────────
        val isolated = stakeholders.filter { s ->
            s.coalitionsWith.isEmpty() &&
            !s.canVeto &&
            s.coalitionsWith.none { id -> stakeholders.any { it.id == id } }
        }
        if (isolated.size >= 2) {
            findings += Finding(
                "${isolated.size} isolated stakeholder(s) with no declared alliances",
                "Unaligned actors: ${isolated.joinToString(", ") { it.name }}. These stakeholders are available coalition partners for any actor who reaches out first — including opposition coalitions. Engaging them proactively is lower-cost than recovering from their opposition.",
                Finding.Severity.INFO
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Authority weight used for coalition calculation; influence used as fallback when authority = 0."),
                Assumption("Majority threshold = total_weight / 2. Adjust if your decision rule requires supermajority."),
                Assumption("Minimum winning coalition uses greedy descent from highest-authority actors (exact MWC is NP-hard for large N)."),
                Assumption("Pivotal analysis approximates Banzhaf power index — exact index requires 2^N coalition enumeration."),
                Assumption("Tsebelis (2002) veto player theory: policy change requires proposals in the winset of ALL veto players simultaneously.")
            ),
            calculations = mapOf(
                "stakeholderCount" to stakeholders.size,
                "totalAuthority" to totalWeight,
                "majorityThreshold" to majorityThreshold,
                "formalVetoPlayers" to formalVetoPlayers.size,
                "coalitionGroups" to coalitionGroups.size,
                "exitCapableHighAuthority" to exitCapable.size,
                "isolatedActors" to isolated.size
            ),
            confidence = if (model.stakeholders.all { it.authority > 0 }) 0.78 else 0.55,
            visualizations = emptyList(),
            explanation = "Coalition analysis: ${formalVetoPlayers.size} veto player(s), ${coalitionGroups.size} declared coalition group(s). ${if (pivotal != null) "Pivotal actor: ${pivotal.name}." else ""} ${if (mwc != null) "Minimum winning coalition: ${mwc.joinToString(", ") { it.name }}." else ""}"
        )
    }

    private fun buildCoalitionLinks(stakeholders: List<Stakeholder>): Map<String, Set<String>> {
        return stakeholders.associate { s ->
            s.id to s.coalitionsWith.toSet()
        }
    }

    private fun findCoalitionGroups(
        stakeholders: List<Stakeholder>,
        links: Map<String, Set<String>>
    ): List<List<Stakeholder>> {
        val visited = mutableSetOf<String>()
        val groups = mutableListOf<List<Stakeholder>>()

        for (s in stakeholders) {
            if (s.id in visited || s.coalitionsWith.isEmpty()) continue
            val group = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            queue += s.id
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                if (curr in group) continue
                group += curr
                val neighbors = links[curr] ?: continue
                for (neighbor in neighbors) {
                    if (neighbor !in group && stakeholders.any { it.id == neighbor }) queue += neighbor
                }
            }
            if (group.size >= 2) {
                visited += group
                groups += group.mapNotNull { id -> stakeholders.firstOrNull { it.id == id } }
            }
        }
        return groups
    }

    // Greedy minimum winning coalition: sort by weight descending, take until majority
    private fun findMinimumWinningCoalition(
        stakeholders: List<Stakeholder>,
        weightFn: (Stakeholder) -> Int,
        threshold: Double
    ): List<Stakeholder>? {
        if (stakeholders.isEmpty()) return null
        val sorted = stakeholders.sortedByDescending { weightFn(it) }
        var cumulative = 0
        val coalition = mutableListOf<Stakeholder>()
        for (s in sorted) {
            coalition += s
            cumulative += weightFn(s)
            if (cumulative > threshold) return coalition
        }
        return if (cumulative > threshold) coalition else null
    }

    private fun findBlockingCoalition(
        stakeholders: List<Stakeholder>,
        weightFn: (Stakeholder) -> Int,
        threshold: Double
    ): List<Stakeholder>? {
        val total = stakeholders.sumOf { weightFn(it) }.toDouble()
        // Blocking = deny majority to others: need > (total - threshold)
        val blockingThreshold = total - threshold
        val sorted = stakeholders.sortedByDescending { weightFn(it) }
        var cumulative = 0
        val coalition = mutableListOf<Stakeholder>()
        for (s in sorted) {
            coalition += s
            cumulative += weightFn(s)
            if (cumulative > blockingThreshold) return coalition
        }
        return null
    }

    // Simplified Banzhaf index: for each stakeholder, count how often adding them to
    // a random subset of others crosses the threshold (approximated by sampling subsets)
    private fun findPivotalStakeholder(
        stakeholders: List<Stakeholder>,
        weightFn: (Stakeholder) -> Int,
        threshold: Double
    ): Stakeholder? {
        if (stakeholders.size > 12) {
            // For large N, use simple greedy estimate
            val sorted = stakeholders.sortedByDescending { weightFn(it) }
            var cum = 0
            for ((_, s) in sorted.withIndex()) {
                val before = cum
                cum += weightFn(s)
                if (before <= threshold && cum > threshold) return s
            }
            return sorted.firstOrNull()
        }

        // For small N enumerate all subsets
        val n = stakeholders.size
        val swingCount = IntArray(n)
        val totalSubsets = 1 shl n

        for (mask in 0 until totalSubsets) {
            val subsetWeight = (0 until n).filter { (mask shr it) and 1 == 1 }.sumOf { weightFn(stakeholders[it]) }
            for (i in 0 until n) {
                if ((mask shr i) and 1 == 1) continue  // i already in subset
                val withI = subsetWeight + weightFn(stakeholders[i])
                if (subsetWeight <= threshold && withI > threshold) swingCount[i]++
            }
        }

        val maxIdx = swingCount.indices.maxByOrNull { swingCount[it] } ?: return null
        return if (swingCount[maxIdx] > 0) stakeholders[maxIdx] else null
    }

    private fun emptyResult() = AnalysisResult(
        moduleId = id, moduleName = name,
        applicability = Applicability(0.0, listOf("No stakeholders.")),
        findings = emptyList(), recommendedActions = emptyList(),
        assumptions = emptyList(), calculations = emptyMap(),
        confidence = 0.0, visualizations = emptyList(),
        explanation = "No stakeholders to analyse."
    )
}
