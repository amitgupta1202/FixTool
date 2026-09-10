package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.RunSetStats

/** The burst from the design note, as a finished report, for every test that needs one in hand. */
object LoadFixtures {
    const val T0 = 1_788_616_931_204L

    fun burstReport(
        unmatched: Int = 4,
        strictRate: Boolean = false,
        status: LoadStatus = LoadStatus.DONE,
        // 52 and not 51: the fifty lanes plus the two sessions DROPCOPY opens. The count is the run's
        // own, so a reader of it cannot go back to lanes + listen.size.
        tool: LoadReport.Tool = LoadReport.Tool(discarded = 0, discardedOn = 52, neverLeftSocket = 0, issueFailures = 0, pendingPeak = 3_410),
        rate: LoadReport.RateReport? = null,
    ): LoadReport {
        val replies = LoadReport.Replies(matched = 4_000L - unmatched, unmatched = unmatched.toLong(), duplicates = 12, late = 0, strays = 0, lastMatchedAt = T0 + 2_701)
        return LoadReport(
            id = "20260905-140211-nos-eur-usd-1m",
            label = "NOS EUR/USD 1M ×4,000 on LOADGEN",
            status = status,
            stage = LoadStage.DONE,
            template = LoadReport.TemplateInfo("NOS EUR/USD 1M", "D", perMessageTags = listOf(11, 60), fixedTags = listOf(35, 55, 54, 38, 40, 44, 59), onceTags = emptyList()),
            profileName = "LOADGEN",
            lanes = 50,
            listen = listOf("DROPCOPY"),
            shape = if (rate == null) LoadShape.Burst(4_000) else LoadShape.Rate(rate.requestedPerSecond, 600_000),
            match = LoadMatch(11, 11, "8"),
            settleMs = 60_000,
            seed = mapOf("run" to "b7f2"),
            storeAndLog = StoreAndLogOverride(FixConnectionConfig.MessageStoreKind.MEMORY, FixConnectionConfig.MessageLogKind.NONE),
            strictRate = strictRate,
            startedAt = T0 - 1_000,
            finishedAt = T0 + 60_813,
            settleLeftMs = null,
            issue = LoadReport.Issue(requested = 4_000, handedToEngine = 4_000, leftSocket = 4_000, firstSendAt = T0, lastSendAt = T0 + 813, prepareMs = 96),
            rate = rate,
            replies = replies,
            timing = LoadReport.Timing(elapsedMs = 2_701, drainMs = 1_888),
            roundTrip = RunSetStats.Distribution(p50 = 14_020, p95 = 212_300, max = 1_880_400, samples = 3_996, min = 912, p99 = 640_100, mean = 41_200),
            roundTripHistogram =
                RoundTripHistogram.empty().toMutableList().also {
                    it[RoundTripHistogram.indexOf(14_020)] = 2_100
                    it[RoundTripHistogram.indexOf(212_300)] = 1_700
                    it[RoundTripHistogram.indexOf(1_880_400)] = 196
                },
            perLane =
                listOf(
                    LoadReport.LaneCounts(1, matched = 2_000, unanswered = 1, duplicates = 6, p50Us = 12_589, p95Us = 199_526),
                    LoadReport.LaneCounts(2, matched = 1_996, unanswered = 3, duplicates = 6, p50Us = 12_589, p95Us = 1_584_893),
                ),
            perSecond = listOf(LoadReport.Second(0, 4_000, 2_210, 188_000), LoadReport.Second(1, 0, 1_740, 402_000), LoadReport.Second(2, 0, 46, null)),
            tool = tool,
            unmatched =
                listOf(
                    LoadReport.UnmatchedRequest("ORD-b7f2-1187", 37, T0 + 239),
                    LoadReport.UnmatchedRequest("ORD-b7f2-2410", 10, T0 + 490),
                    LoadReport.UnmatchedRequest("ORD-b7f2-2411", 11, T0 + 490),
                    LoadReport.UnmatchedRequest("ORD-b7f2-3902", 2, T0 + 794),
                ).take(unmatched),
            unmatchedTotal = unmatched,
            evidence = LoadReport.Evidence.forPhase(1),
            verdict = LoadReport.verdict(status, replies, rate, tool, strictRate),
        )
    }

    val shortfall = LoadReport.RateReport(requestedPerSecond = 500, heldForMs = 581_000, shortfalls = listOf(LoadReport.Shortfall(fromSecond = 341, toSecond = 359, minPerSecond = 412, behind = 1_672)), maxLagMs = 2_300, tolerance = Pacer.TOLERANCE)
}
