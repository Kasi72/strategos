package com.drkkr.strategos

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drkkr.strategos.domain.framework.ConfidenceProfile
import com.drkkr.strategos.domain.framework.RecommendedAction
import com.drkkr.strategos.domain.model.EvidenceSource
import com.drkkr.strategos.domain.recommendation.DecisionPathway
import com.drkkr.strategos.domain.recommendation.LensRecommendation
import com.drkkr.strategos.domain.recommendation.RiskRegisterItem
import com.drkkr.strategos.domain.recommendation.VoiItem
import com.drkkr.strategos.ui.tabs.ConfidenceProfileCard
import com.drkkr.strategos.ui.tabs.EvidenceSourceCard
import com.drkkr.strategos.ui.tabs.LensCard
import com.drkkr.strategos.ui.tabs.PathwayCard
import com.drkkr.strategos.ui.tabs.RiskRegisterCard
import com.drkkr.strategos.ui.tabs.VoiCard
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM Compose UI tests (Robolectric) for the Report tab's presentational cards —
 * the layer most prone to silent visual regressions. Runs without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportCardsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun drainAnimations() {
        runCatching {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test
    fun recommendedPathway_autoExpands_andShowsRationale() {
        val pathway = DecisionPathway(
            label = "Recommended", actionId = "a1",
            action = "Pilot the change", rationale = "Best expected value across tested scenarios.",
            strength = 0.82
        )
        compose.setContent { MaterialTheme { PathwayCard(pathway) } }
        compose.onNodeWithText("Pilot the change").assertIsDisplayed()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        // label == "Recommended" auto-expands, so the full rationale is visible
        compose.onNodeWithText("Best expected value across tested scenarios.").assertIsDisplayed()
    }

    @Test
    fun excludedPathway_showsExcludedChip() {
        val pathway = DecisionPathway(
            label = "Excluded", actionId = "a2",
            action = "Breach the contract", rationale = "Violates a legal constraint.",
            strength = 0.0, excluded = true
        )
        compose.setContent { MaterialTheme { PathwayCard(pathway) } }
        compose.onNodeWithText("Breach the contract").assertIsDisplayed()
        compose.onNodeWithText("Excluded").assertIsDisplayed()
    }

    @Test
    fun riskCard_expandsToShowMitigation() {
        val risk = RiskRegisterItem(
            failureMode = "Vendor misses deadline", actor = "Supplier",
            evidenceType = "Assumption-dependent", likelihood = "High", impact = "High",
            earlySignal = "Slipping milestones", mitigation = "Add penalty clause",
            owner = "Procurement", stopCondition = "Two consecutive slips"
        )
        compose.setContent { MaterialTheme { RiskRegisterCard(risk) } }
        // Collapsed: title + likelihood chip visible, mitigation hidden
        compose.onNodeWithText("Vendor misses deadline").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsDisplayed()
        // Expand and assert detail appears
        compose.onNodeWithText("Vendor misses deadline").performClick()
        compose.onNodeWithText("Add penalty clause").assertIsDisplayed()
    }

    @Test
    fun voiCard_showsMissingFactAndRecommendation() {
        val voi = VoiItem(
            missingFact = "Regulator stance unknown",
            whyItMatters = "Determines whether the fast path is legal.",
            percentageScenariosChanged = 40,
            costToObtain = "One legal consult",
            recommendation = "Obtain a written opinion before committing."
        )
        compose.setContent { MaterialTheme { VoiCard(voi) } }
        compose.onNodeWithText("Regulator stance unknown").assertIsDisplayed()
        compose.onNodeWithText("Obtain a written opinion before committing.").assertIsDisplayed()
    }

    @Test
    fun confidenceProfileCard_showsHeaderAndSignals() {
        val profile = ConfidenceProfile(
            evidenceQuality = 0.8, modelCompleteness = 0.8,
            analyticalRobustness = 0.8, implementationReadiness = 0.8
        )
        compose.setContent { MaterialTheme { ConfidenceProfileCard(profile) } }
        compose.onNodeWithText("Decision Confidence Profile").assertIsDisplayed()
        compose.onNodeWithText("Evidence quality").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsDisplayed() // overall 0.8 -> "High"
    }

    @Test
    fun lensCard_showsLensLabelAndAction() {
        val rec = LensRecommendation(
            lens = RecommendedAction.Lens.RISK_AVERSE, lensLabel = "Risk-averse",
            stakeholderId = null, action = "Choose the reversible option",
            rationale = "Minimises worst-case loss.", supportingModules = listOf("minimax")
        )
        compose.setContent { MaterialTheme { LensCard(rec) } }
        compose.onNodeWithText("RISK-AVERSE").assertIsDisplayed() // label is uppercased
        compose.onNodeWithText("Choose the reversible option").assertIsDisplayed()
    }

    // Guards the evidence-sources feature added earlier this session.
    @Test
    fun evidenceSourceCard_showsLabelKindAndQuality() {
        val evidence = EvidenceSource(
            label = "RCT on remote work", kind = EvidenceSource.Kind.RCT,
            url = "https://example.org/study", quality = 5
        )
        var deleted = false
        compose.setContent { MaterialTheme { EvidenceSourceCard(evidence) { deleted = true } } }
        compose.onNodeWithText("RCT on remote work").assertIsDisplayed()
        compose.onNodeWithText("RCT").assertIsDisplayed()
        compose.onNodeWithText("Quality: 5/5").assertIsDisplayed()
        assertTrue(!deleted) // nothing deleted without interaction
    }
}
