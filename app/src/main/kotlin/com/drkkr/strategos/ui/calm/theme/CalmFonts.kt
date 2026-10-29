package com.drkkr.strategos.ui.calm.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.drkkr.strategos.R

/**
 * The three typefaces from the design (DESIGN.md §2), bundled as open-licence (OFL) font files in res/font:
 * Figtree for the UI, Source Serif 4 for the coach, IBM Plex Mono for numerals on the X-ray only.
 * Figtree and Source Serif 4 are variable fonts, so each weight is one variation of the same file (minSdk 26).
 */
@OptIn(ExperimentalTextApi::class)
object CalmFonts {
    private fun variable(res: Int, vararg weights: Int) = FontFamily(
        weights.map { w ->
            Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        }
    )

    val Sans: FontFamily = variable(R.font.figtree, 400, 500, 600, 700)
    val Serif: FontFamily = variable(R.font.sourceserif4, 400, 600)
    val Mono: FontFamily = FontFamily(
        Font(R.font.plexmono_regular, FontWeight.Normal),
        Font(R.font.plexmono_medium, FontWeight.Medium)
    )
}
