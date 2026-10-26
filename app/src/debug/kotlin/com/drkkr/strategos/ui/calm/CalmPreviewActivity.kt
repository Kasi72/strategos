package com.drkkr.strategos.ui.calm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.drkkr.strategos.domain.agent.BrainFact
import com.drkkr.strategos.domain.agent.BrainFactKind
import com.drkkr.strategos.domain.agent.InfluenceEdge
import com.drkkr.strategos.domain.agent.InfluenceGraph
import com.drkkr.strategos.domain.agent.InfluenceNode
import com.drkkr.strategos.domain.agent.Pattern
import com.drkkr.strategos.domain.agent.PatternVariable
import com.drkkr.strategos.domain.agent.PersonMemory
import com.drkkr.strategos.domain.analysis.MomentumEvent
import com.drkkr.strategos.domain.analysis.MomentumStatus
import com.drkkr.strategos.domain.analysis.PendingCheck
import com.drkkr.strategos.domain.model.Provenance
import com.drkkr.strategos.ui.calm.overview.ChartUi
import com.drkkr.strategos.ui.calm.overview.GuidanceRowUi
import com.drkkr.strategos.ui.calm.overview.JournalTeaserUi
import com.drkkr.strategos.ui.calm.overview.OverviewActions
import com.drkkr.strategos.ui.calm.overview.OverviewChart
import com.drkkr.strategos.ui.calm.overview.OverviewScreen
import com.drkkr.strategos.ui.calm.overview.OverviewUiState
import com.drkkr.strategos.ui.calm.overview.ThreadRowUi
import com.drkkr.strategos.ui.calm.projects.ProjectCardUi
import com.drkkr.strategos.ui.calm.projects.ProjectsActions
import com.drkkr.strategos.ui.calm.projects.ProjectsScreen
import com.drkkr.strategos.ui.calm.projects.ProjectsUiState
import com.drkkr.strategos.ui.calm.theme.StrategosTheme

/**
 * Debug-only harness for screenshotting redesign screens. Launch with:
 *   adb shell am start -n com.drkkr.strategos/.ui.calm.CalmPreviewActivity --ez dark true|false --es screen projects
 * It is in the debug source set only, so it never ships and MainActivity is untouched.
 */
class CalmPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val screen = intent.getStringExtra("screen") ?: "projects"
        setContent {
            StrategosTheme(darkTheme = dark) {
                when (screen) {
                    "firstrun" -> ProjectsScreen(ProjectsUiState(projects = emptyList(), archivedCount = 0, hasKey = true, loading = false), ProjectsActions())
                    "overview" -> OverviewScreen(SAMPLE_OVERVIEW, OverviewActions())
                    else -> ProjectsScreen(ProjectsUiState(projects = SAMPLE, archivedCount = 1, hasKey = true, loading = false), ProjectsActions())
                }
            }
        }
    }

    private companion object {
        val SAMPLE = listOf(
            ProjectCardUi("1", "Loan for my brother", "Today · 3 topics", MomentumStatus.IMPROVING,
                listOf(50f, 54f, 58f, 55f, 62f, 66f), listOf(60f, 56f, 54f, 50f, 46f, 42f)),
            ProjectCardUi("2", "Co-founder deadlock", "2 days ago · 1 topic", MomentumStatus.STEADY,
                listOf(50f, 51f, 49f, 50f, 50f), listOf(50f, 49f, 51f, 50f, 50f)),
            ProjectCardUi("3", "Saying no to my manager about the weekend rota", "Last week · 2 topics", MomentumStatus.TENSE,
                listOf(55f, 52f, 48f, 44f, 40f), listOf(45f, 50f, 56f, 61f, 66f))
        )

        private val TRUST = listOf(48f, 52f, 58f, 63f, 66f)
        private val TENSION = listOf(60f, 56f, 52f, 47f, 42f)

        val SAMPLE_OVERVIEW = OverviewUiState(
            loading = false,
            projectName = "Loan for my brother",
            subtitle = "3 topics · active today",
            linkedJournalCount = 3,
            status = MomentumStatus.IMPROVING,
            chart = ChartUi(
                trustPast = TRUST, tensionPast = TENSION,
                trustHold = OverviewChart.hold(TRUST), tensionHold = OverviewChart.hold(TENSION),
                trustRevert = OverviewChart.revert(TRUST), tensionRevert = OverviewChart.revert(TENSION)
            ),
            momentumBlurb = "Trust has been climbing since you held your limit. Tension is easing, but the next few days matter.",
            events = listOf(
                MomentumEvent("He first asked you to co-sign", 0),
                MomentumEvent("You told him: not yet, give me a day", 0),
                MomentumEvent("He said he'll ask your parents", 0)
            ),
            pendingCheck = PendingCheck("How did the last talk with Brother go?", "Brother"),
            guidance = listOf(
                GuidanceRowUi("Say your limit once, kindly: \"My limit on the house doesn't change.\"", true, "From Main", null),
                GuidanceRowUi("Give the quiet a day before you reach out.", true, "From your journal", null),
                GuidanceRowUi("Don't send a \"just checking in\" message.", false, "From your journal", null),
                GuidanceRowUi("Don't co-sign just to keep the peace.", false, "From Main", null)
            ),
            pattern = Pattern(
                "stag-hunt", "Mutual trust needed",
                "You both gain if this works, but only if each of you trusts the other to carry their share. If either plays it safe, the safe move wins and the bigger prize is lost. The whole thing turns on whether each of you believes the other will commit.",
                listOf(
                    PatternVariable("Your limit", "Nothing that puts the house at risk"),
                    PatternVariable("Brother's need", "Money for the café in about three weeks"),
                    PatternVariable("Your parents", "Can tip it either way"),
                    PatternVariable("The doubt", "Each of you wondering if the other will follow through")
                ),
                "https://en.wikipedia.org/wiki/Stag_hunt"
            ),
            threads = listOf(
                ThreadRowUi("1", "Main", "Let him ask. Keep your answer the same.", "2h", true),
                ThreadRowUi("2", "Money angle", "How much could you give without it hurting?", "1d", false),
                ThreadRowUi("3", "Trust angle", "What would help him feel respected?", "Oct 22", false)
            ),
            chatLabel = "Continue chat",
            people = listOf(
                PersonMemory("you", "You", "Y", true, listOf(
                    BrainFact("To help him without losing the house", BrainFactKind.WANT, Provenance.USER),
                    BrainFact("Losing him, or being the reason it falls apart", BrainFactKind.FEAR, Provenance.INFERRED)
                )),
                PersonMemory("bro", "Your brother", "B", false, listOf(
                    BrainFact("Money for his café within about three weeks", BrainFactKind.WANT, Provenance.USER),
                    BrainFact("Being told no again", BrainFactKind.FEAR, Provenance.INFERRED)
                ))
            ),
            limits = listOf(
                BrainFact("Don't risk our house", BrainFactKind.LIMIT, Provenance.USER),
                BrainFact("Keep the relationship with him intact", BrainFactKind.LIMIT, Provenance.USER)
            ),
            inferredCount = 28, reviewedCount = 0, inferredTotal = 28,
            modelStrip = "4 people · 6 options · 18/18 outcomes",
            influence = InfluenceGraph(
                nodes = listOf(
                    InfluenceNode("you", "You", "Y", true, false),
                    InfluenceNode("wife", "Wife", "W", false, false),
                    InfluenceNode("bro", "Brother", "B", false, false),
                    InfluenceNode("par", "Parents", "P", false, true)
                ),
                edges = listOf(
                    InfluenceEdge("wife", "you", 0.9, "shares your limit", false),
                    InfluenceEdge("bro", "you", 0.6, "pushing for a yes", false),
                    InfluenceEdge("par", "you", 0.4, "can tip it", true)
                )
            ),
            journalTeasers = listOf(
                JournalTeaserUi("n1", "Silence triggers urgency", "Suggested takeaway: give the quiet a day before you reach out.", "Oct 23")
            )
        )
    }
}
