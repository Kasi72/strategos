package com.drkkr.strategos.framework.strategic

import com.drkkr.strategos.domain.framework.*
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.StakeholderType
import com.drkkr.strategos.framework.MatrixSupport

class AuctionModule : FrameworkModule {
    override val id = "auction"
    override val name = "Auction Theory (Vickrey / VCG)"
    override val version = "1.0"
    override val category = "Mechanism Design"

    override fun canAnalyze(model: Scenario): Applicability {
        val text = "${model.title} ${model.description}".lowercase()
        val kwHits = listOf(
            "auction", "bid", "bidder", "vickrey", "highest bid", "sealed",
            "winner", "procurement", "tender", "price discovery", "reserve price",
            "second price", "first price", "common value", "private value",
            "winner's curse", "winners curse", "spectrum", "oil field", "mineral",
            "alloc", "mechanism", "clarke", "vcg", "groves"
        ).count { text.contains(it) }
        return when {
            kwHits >= 3 -> Applicability(0.88, listOf("Strong auction / mechanism language ($kwHits keyword hits)."))
            kwHits == 2 -> Applicability(0.72, listOf("Auction context ($kwHits keyword hits)."))
            kwHits == 1 -> Applicability(0.40, listOf("Possible auction context — weak signal."))
            else -> Applicability(0.0, listOf("No auction language detected."))
        }
    }

    override fun analyze(model: Scenario): AnalysisResult {
        val n = model.stakeholders.size
        val bidder0 = model.stakeholders.getOrNull(0)
        val bidder1 = model.stakeholders.getOrNull(1)
        val text = "${model.title} ${model.description}".lowercase()
        val findings = mutableListOf<Finding>()
        val recs = mutableListOf<RecommendedAction>()

        val isSecondPrice = text.contains("second price") || text.contains("vickrey") || text.contains("2nd price")
        val isFirstPrice = text.contains("first price") || text.contains("1st price") ||
                (!isSecondPrice && (text.contains("sealed") || text.contains("fpa")))
        val isCommonValue = text.contains("common value") || text.contains("mineral") ||
                text.contains("oil") || text.contains("spectrum") || text.contains("winner's curse") ||
                text.contains("winners curse")
        val isProcurement = text.contains("procurement") || text.contains("tender") || text.contains("contractor")
        val isVCG = text.contains("vcg") || text.contains("vickrey-clarke") || text.contains("groves") ||
                text.contains("clarke") || text.contains("mechanism design") || text.contains("pivotal")

        // --- Second-price / Vickrey ---
        findings += Finding(
            "Second-Price Auction (Vickrey 1961): truthful bidding is weakly dominant",
            "In a sealed-bid second-price auction, every bidder's weakly dominant strategy is to bid exactly their true valuation — regardless of how many bidders there are and regardless of their strategies.\n• Win case: you pay the second-highest bid (not yours) → bidding higher adds only downside risk; bidding lower forfeits profitable wins.\n• Loss case: bidding differently cannot help you win at a better price.",
            Finding.Severity.INFO
        )
        recs += RecommendedAction(
            null, "Bid your true valuation (SPA)",
            "Weakly dominant strategy in any second-price sealed-bid auction (Vickrey 1961). No strategic calculation required — truthful bidding is incentive-compatible for every player regardless of opponents.",
            lens = RecommendedAction.Lens.EXPECTED
        )

        // --- First-price BNE ---
        if (isFirstPrice) {
            val shadingFactor = if (n >= 2) (n - 1.0) / n else 0.5
            val shadingPct = "%.0f%%".format(shadingFactor * 100)
            findings += Finding(
                "First-Price Auction: symmetric BNE involves bid shading",
                "Symmetric Bayesian Nash Equilibrium (private values uniform on [0,1], $n risk-neutral bidders):\n• Equilibrium bid: b(v) = v × (n−1)/n — shade your valuation by ${100 - (shadingFactor * 100).toInt()}%.\n• Economic logic: balance probability of winning (↑ with higher bid) against profit margin (↓ with higher bid).\n• With $n bidders: optimal shade = $shadingPct of true value.",
                Finding.Severity.WARNING
            )
            recs += RecommendedAction(
                null, "Shade bid: b(v) = v × ${n - 1}/$n",
                "Symmetric BNE for first-price sealed-bid auction with $n bidders and uniform[0,1] private values. Bidding your true value is strictly dominated — shade by factor (n−1)/n. As n grows, shading disappears.",
                lens = RecommendedAction.Lens.EXPECTED
            )
        }

        // --- Revenue Equivalence ---
        findings += Finding(
            "Revenue Equivalence Theorem (Vickrey 1961, Myerson 1981)",
            "Under standard conditions (risk-neutral bidders, independent private values, symmetric beliefs, no reserve), ALL standard auction formats — first-price, second-price, English (ascending), Dutch (descending) — yield the same expected revenue to the seller. The format choice affects strategy but not outcome.",
            Finding.Severity.NOTE
        )

        // --- Winner's Curse (common value) ---
        if (isCommonValue) {
            findings += Finding(
                "Winner's Curse — common-value auction risk",
                "When the item's value is common to all bidders (oil field, spectrum licence, artwork), winning is bad news: it reveals that all others estimated value lower than you. Rational bidders must shade aggressively to account for this adverse selection. Naively bidding your raw estimate yields negative expected profit.",
                Finding.Severity.CRITICAL
            )
            recs += RecommendedAction(
                null, "Correct for Winner's Curse: bid E[value | you win]",
                "In common-value settings, winning is informative — it selects the highest private signal. Correct by computing the expected value conditional on winning (all other estimates were lower) before bidding. This is strictly lower than your raw estimate.",
                lens = RecommendedAction.Lens.RISK_AVERSE
            )
        }

        // --- Procurement reverse ---
        if (isProcurement) {
            findings += Finding(
                "Procurement (reverse) auction: same theory, mirrored",
                "In procurement, the buyer is the auctioneer; sellers compete by quoting costs downward. Second-price procurement: truthful cost revelation is dominant. First-price procurement: symmetric BNE is b(c) = c + (valuation_range)/(n−1) — sellers shade quotes above true cost.",
                Finding.Severity.INFO
            )
        }

        // --- Many bidders ---
        if (n > 5) {
            findings += Finding(
                "Many bidders ($n): bids converge to true values",
                "As competition intensifies, first-price BNE bids approach true valuations: b(v)=v(n−1)/n → v as n→∞. With $n bidders, shade factor = ${(n - 1.0) / n * 100}% — already close to truthful bidding.",
                Finding.Severity.INFO
            )
        }

        // --- VCG / Pivotal Mechanism ---
        if (isVCG || text.contains("mechanism") || text.contains("public good")) {
            findings += Finding(
                "Vickrey-Clarke-Groves (VCG) / Pivotal Mechanism",
                "VCG generalises second-price logic to multi-item allocation and public goods: each agent pays a Clarke tax equal to the externality they impose on others (the welfare reduction their participation causes to everyone else). This makes truthful reporting a dominant strategy in ANY allocation problem where side payments are possible — the gold standard of mechanism design.",
                Finding.Severity.NOTE
            )
            recs += RecommendedAction(
                null, "Implement VCG if side payments are feasible",
                "VCG achieves allocative efficiency + dominant-strategy incentive compatibility simultaneously. Tradeoff: it may not be budget-balanced (the auctioneer may need to subsidise). For public goods with binary participation (build / don't build), the Pivotal Mechanism (Clarke 1971) yields the same dominant-strategy truthfulness.",
                lens = RecommendedAction.Lens.ETHICAL_LEGAL
            )
        }

        // --- Matrix-level cross-check ---
        val sf = MatrixSupport.build(model)
        if (sf != null && sf.players.size == 2) {
            findings += Finding(
                "Two-bidder matrix analysis possible",
                "With 2 bidders, each possible bid pair can be enumerated. In a second-price auction both have dominant strategies → unique Nash equilibrium at (true value₀, true value₁). Any deviation strictly reduces expected payoff.",
                Finding.Severity.INFO
            )
        }

        // --- Bidder-personalised strategic profile ---
        if (bidder0 != null && bidder1 != null) {
            val isProcurementBuyer = bidder0.type == StakeholderType.INSTITUTION || bidder0.type == StakeholderType.REGULATOR
            val isVendorSeller = bidder1.type == StakeholderType.VENDOR || bidder1.type == StakeholderType.INSTITUTION
            val authorityAsymmetry = kotlin.math.abs(bidder0.authority - bidder1.authority)
            val credibilityAsymmetry = kotlin.math.abs(bidder0.credibility - bidder1.credibility)

            findings += Finding(
                "Bidder profile: ${bidder0.name} vs ${bidder1.name}",
                buildString {
                    append("${bidder0.name} [authority ${bidder0.authority}/10, credibility ${bidder0.credibility}/10, type: ${bidder0.type}]\n")
                    append("${bidder1.name} [authority ${bidder1.authority}/10, credibility ${bidder1.credibility}/10, type: ${bidder1.type}]\n\n")
                    if (authorityAsymmetry >= 4) {
                        val dominant = if (bidder0.authority >= bidder1.authority) bidder0 else bidder1
                        val weaker = if (bidder0.authority < bidder1.authority) bidder0 else bidder1
                        append("Authority asymmetry (gap = $authorityAsymmetry): ${dominant.name} holds structural advantage. ")
                        append("In procurement settings, authority differentials can influence reserve price decisions and post-award relationship terms.\n")
                        append("${weaker.name} may compensate with aggressive price shading or value-added differentiation.\n\n")
                    }
                    if (credibilityAsymmetry >= 4) {
                        val highCred = if (bidder0.credibility >= bidder1.credibility) bidder0 else bidder1
                        val lowCred = if (bidder0.credibility < bidder1.credibility) bidder0 else bidder1
                        append("Credibility asymmetry (gap = $credibilityAsymmetry): ${highCred.name} has stronger track record. ")
                        append("${lowCred.name} faces a signalling challenge — bid price alone may not win if credibility is the buyer's implicit weighting criterion.\n\n")
                    }
                    if (isProcurementBuyer && isVendorSeller) {
                        append("${bidder0.name} appears to be the procurement authority (buyer); ${bidder1.name} appears to be the vendor (seller). ")
                        append("Procurement reverse-auction logic applies: ${bidder1.name}'s dominant strategy is truthful cost revelation in second-price format, or cost + (range/n−1) shading in first-price format.")
                    }
                    if (bidder0.canVeto || bidder1.canVeto) {
                        val vetoHolder = if (bidder0.canVeto) bidder0 else bidder1
                        append("${vetoHolder.name} holds veto rights — they can walk away from any outcome, strengthening their outside option and shifting the effective reservation price.")
                    }
                },
                Finding.Severity.NOTE
            )
        }

        return AnalysisResult(
            moduleId = id, moduleName = name,
            applicability = canAnalyze(model),
            findings = findings, recommendedActions = recs,
            assumptions = listOf(
                Assumption("Second-price dominant strategy: risk-neutral bidders, independent private values."),
                Assumption("First-price BNE formula b(v)=v(n-1)/n: values drawn independently from uniform[0,1]; symmetric bidders."),
                Assumption("Revenue Equivalence: risk neutrality, independent private values, symmetric beliefs, no reserve price, no budget constraints."),
                Assumption("VCG: quasi-linear preferences, transferable utility, no budget constraints on the mechanism designer.")
            ),
            calculations = mapOf(
                "numBidders" to n,
                "auctionType" to when {
                    isSecondPrice -> "second-price (Vickrey)"
                    isFirstPrice -> "first-price"
                    else -> "format-unspecified"
                },
                "firstPriceShadingFactor" to if (n >= 2) (n - 1.0) / n else 0.5,
                "isCommonValue" to isCommonValue,
                "isVCG" to isVCG
            ),
            confidence = 0.80,
            visualizations = emptyList(),
            explanation = "Auction theory (Bonanno Ch 2.3–2.4 / Vickrey 1961 / Myerson 1981): ${when {
                isSecondPrice -> "second-price — truthful bidding is weakly dominant."
                isFirstPrice -> "first-price — symmetric BNE: b(v)=v×(${n-1}/$n), shade below true value."
                else -> "auction structure — dominant strategy analysis."
            }} Revenue Equivalence Theorem: standard formats yield equal expected revenue. ${if (isVCG) "VCG mechanism extends truth-telling to multi-item allocation." else ""}"
        )
    }
}
