package com.drkkr.strategos.ui.calm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    // The chat-first project flow over demo data: title opens the overview sheet, the check-in sits in the chat.
                    "flow" -> ProjectFlowPreview(intent.getStringExtra("name") ?: "Loan for my brother", intent.getBooleanExtra("power", false))
                    "talk" -> com.drkkr.strategos.ui.calm.talk.TalkScreen(
                        com.drkkr.strategos.ui.calm.talk.TalkUiState(
                            projectName = "CoFounder conflict",
                            topics = listOf(com.drkkr.strategos.ui.calm.talk.TopicUi("1", "Main Thread", "", "now", true)),
                            turns = listOf(
                                com.drkkr.strategos.ui.calm.talk.TalkUi.turns(listOf(
                                    com.drkkr.strategos.agent.ChatItem.AssistantText("a1",
                                        "What's happening underneath: Alex feels cornered and hears \"give up equity\" as losing his say.\n" +
                                        "Clear-win move: Wait a day, then offer to write the decision criteria together.\n" +
                                        "What to say: \"I know you want to push sales instead of giving up equity. Let's write the criteria together before Monday.\"\n" +
                                        "Do this:\n• Wait a day before raising it again.\n• Ask Alex to co-draft the decision criteria.\n" +
                                        "Don't do this:\n• Don't push now, he'll get defensive.\n• Don't decide alone, he'll feel sidelined.\n" +
                                        "Try the co-drafting line once he's cooled off and tell me how he responds.")
                                )).first()
                            )
                        ), com.drkkr.strategos.ui.calm.talk.TalkActions())
                    "fonts" -> androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                        listOf(400, 500, 600, 700).forEach { w ->
                            androidx.compose.material3.Text("Figtree $w — Is it getting better?", fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight(w), fontSize = 22.sp)
                        }
                        androidx.compose.material3.Text("A style SemiBold — Is it getting", style = androidx.compose.ui.text.TextStyle(fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 22.sp))
                        androidx.compose.material3.Text("B style W600 — Is it getting", style = androidx.compose.ui.text.TextStyle(fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight(600), fontSize = 22.sp))
                        androidx.compose.material3.Text("C params SemiBold — Is it getting", fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 22.sp)
                        androidx.compose.material3.Text("THEME title — Is it getting better?", style = com.drkkr.strategos.ui.calm.theme.Calm.type.title)
                        androidx.compose.material3.Text("THEME display — What's on your mind?", style = com.drkkr.strategos.ui.calm.theme.Calm.type.display)
                        androidx.compose.material3.Text("THEME subtitle — Loan for my brother", style = com.drkkr.strategos.ui.calm.theme.Calm.type.subtitle)
                        androidx.compose.material3.Text("Serif 600 — Mutual trust", fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight(600), fontSize = 22.sp)
                        androidx.compose.material3.Text("Mono 500 — 18/18 outcomes", fontFamily = com.drkkr.strategos.ui.calm.theme.CalmFonts.Mono, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 22.sp)
                    }
                    else -> ProjectsScreen(ProjectsUiState(projects = SAMPLE, archivedCount = 1, hasKey = true, loading = false), ProjectsActions())
                }
            }
        }
    }

    internal companion object {
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
            subtitle = "Active today",
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun ProjectFlowPreview(name: String, power: Boolean) {
    val remember = @androidx.compose.runtime.Composable { androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) } }
    val overview = remember(); val answered = remember()
    // The real file picker and the real on-device reader, so attaching a file can be tried here without an AI key.
    val picked = com.drkkr.strategos.ui.calm.talk.rememberFilePick("preview")
    val sent = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val turns = com.drkkr.strategos.ui.calm.talk.TalkUi.turns(listOf(
        com.drkkr.strategos.agent.ChatItem.UserText("u1", "My brother wants me to co-sign a large loan and my wife is against it."),
        com.drkkr.strategos.agent.ChatItem.AssistantText("a1",
            "What's happening underneath: He hears a no as you not believing in him.\n" +
            "Clear-win move: Offer a fixed amount you can afford to lose, and say your limit once.\n" +
            "What to say: \"I can't co-sign, but I can give you a fixed amount towards it.\"\n" +
            "Do this:\n• Say your limit once, kindly.\n" +
            "Don't do this:\n• Don't co-sign just to keep the peace.\n" +
            "Your options are to offer a fixed amount, or to wait a week before answering.\n\n" +
            "One thing would change my advice: when you push on something like this, does Brother usually engage or dig in?")
    ) + previewCards(answered = sent.isNotEmpty()) + sent.flatMapIndexed { i, t ->
        // A shared conversation shows its timeline under the message, as it does in the real chat.
        listOf<com.drkkr.strategos.agent.ChatItem>(com.drkkr.strategos.agent.ChatItem.UserText("s$i", t)) +
            listOfNotNull(com.drkkr.strategos.domain.files.FileBlock.files(t).firstOrNull()?.let(com.drkkr.strategos.domain.a2ui.TurnCards::timeline)?.let { previewCard("tl$i", it, false) })
    })
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(com.drkkr.strategos.ui.calm.theme.Calm.colors.ground))
    com.drkkr.strategos.ui.calm.talk.TalkScreen(
        com.drkkr.strategos.ui.calm.talk.TalkUiState(
            projectName = name, turns = turns, loading = false, statusLine = "Getting better", statusTone = 1, canXRay = true,
            powerAvailable = power, checkIn = "How did the last talk with Brother go?".takeIf { !answered.value && sent.isEmpty() },
            attachment = picked.ui
        ),
        com.drkkr.strategos.ui.calm.talk.TalkActions(onOpenOverview = { overview.value = true }, onSaveCheckIn = { _, _ -> answered.value = true },
            onAttach = picked.open, onRemoveAttachment = picked.clear,
            onCardAction = { a -> if (a.isReply) sent += a.text else if (a.name == com.drkkr.strategos.domain.a2ui.A2ui.OPEN_OVERVIEW) overview.value = true },
            onSend = { text -> sent += picked.take()?.let { com.drkkr.strategos.domain.files.FileBlock.compose(text, it) } ?: text })
    )
    if (overview.value) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { overview.value = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = com.drkkr.strategos.ui.calm.theme.Calm.colors.ground
    ) { OverviewScreen(CalmPreviewActivity.SAMPLE_OVERVIEW, OverviewActions(), modifier = androidx.compose.ui.Modifier.fillMaxHeight(0.92f), embedded = true) }
}

/** The cards the app builds on its own, with sample data, so they can be seen and tapped without an AI key. */
private fun previewCard(id: String, card: com.drkkr.strategos.domain.a2ui.TurnCard, answered: Boolean): com.drkkr.strategos.agent.ChatItem? =
    com.drkkr.strategos.domain.a2ui.SurfaceCodec.decode(com.drkkr.strategos.domain.a2ui.SurfaceCodec.encode(card.messages))
        ?.let { com.drkkr.strategos.agent.ChatItem.Surface(id, it, answered) }

private fun previewCards(answered: Boolean): List<com.drkkr.strategos.agent.ChatItem> {
    val cards = com.drkkr.strategos.domain.a2ui.TurnCards
    val ask = com.drkkr.strategos.domain.intent.NextQuestion(com.drkkr.strategos.domain.intent.IdentifyGaps.THEIR_REPLY, "")
    val top = listOf(
        com.drkkr.strategos.domain.intent.TopOption(com.drkkr.strategos.domain.intent.TopOptions.BEST, "Offer a fixed amount you can afford to lose", ""),
        com.drkkr.strategos.domain.intent.TopOption(com.drkkr.strategos.domain.intent.TopOptions.INSURANCE, "Wait a week before answering", "")
    )
    return listOfNotNull(
        cards.quickAnswers(ask, "Brother")?.let { previewCard("c1", it, answered) },
        cards.options(top)?.let { previewCard("c2", it, answered) },
        cards.pickedUp(listOf("Brother: makes a choice that matters here", "Wife: involved", "Your options: Co-sign, Offer a fixed amount, Wait a week"))?.let { previewCard("c3", it, answered) }
    )
}
