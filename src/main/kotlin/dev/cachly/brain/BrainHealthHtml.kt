package dev.cachly.brain

/**
 * Der Text der Kopfzeile im Fenster "Brain Health" — als eigene Funktion, damit
 * ein Test ihn ohne IDE pruefen kann. Siehe [htmlText] zur Falle mit trimIndent.
 * Inhalt und Zahlen sind aus ShowBrainHealthAction uebernommen. Neu: Status,
 * Tier, letzte Sitzung und die Namen im Team gehen durch [escHtml], damit ein
 * `<` in einem Namen das Fenster nicht zerlegt.
 */
internal fun brainSummaryHtml(health: BrainHealth): String {
    // Limited tiers report a MONTHLY recall counter, unlimited tiers all-time.
    val monthly = health.recallLimit > 0
    val recallScope = if (monthly) "this month" else "all-time"
    val overLimit = monthly && health.totalRecalls >= health.recallLimit
    val tokensSaved = formatTokens(health.estimatedTokensSaved)
    val costSaved = "%.2f".format(health.estimatedTokensSaved * BrainHealth.COST_PER_TOKEN)
    val usedMB = "%.2f".format(health.memoryUsedBytes / (1024.0 * 1024.0))
    val limitMB = health.memoryLimitBytes / (1024 * 1024)
    val pct = "%.1f".format(health.memoryUsedPct)
    val barLen = 20
    val filled = (health.memoryUsedPct / 100.0 * barLen).toInt().coerceIn(0, barLen)
    val storageBar = "█".repeat(filled) + "░".repeat(barLen - filled)

    val recallRow = if (monthly) {
        val note = if (overLimit) " <b>— monthly limit reached</b>" else ""
        "<tr><td><b>Recalls $recallScope:</b></td><td><b>${health.totalRecalls}</b> / ${"%,d".format(health.recallLimit)}$note</td></tr>"
    } else {
        "<tr><td><b>Recalls ($recallScope):</b></td><td><b>${health.totalRecalls}</b> · unlimited plan</td></tr>"
    }

    // ── Value estimate — labeled heuristics, never presented as fact ──
    val insightsHtml = health.insights?.let { ins ->
        val rows = StringBuilder()
        if (ins.minutesSaved > 0) {
            rows.append("<tr><td><b>Developer time saved:</b></td><td><b>${"%.0f".format(ins.minutesSaved)} min</b> <i>(heuristic: 30–240 min per reused lesson)</i></td></tr>")
        }
        if (ins.dollarsSaved > 0) {
            rows.append("<tr><td><b>Cost saved:</b></td><td><b>${formatMoney(ins.dollarsSaved, ins.currency)}</b> <i>(at ${formatMoney(ins.hourlyRate, ins.currency)}/h)</i></td></tr>")
        }
        if (ins.ttfrP50Sec > 0) {
            rows.append("<tr><td><b>Time to first payoff:</b></td><td>${formatDuration(ins.ttfrP50Sec)} <i>(Brain creation → first reused lesson)</i></td></tr>")
        }
        if (rows.isEmpty()) "" else """
            <h2>💰 Value estimate</h2>
            <p style='color:gray;'>Estimates derived from recall activity — not measured billing data.</p>
            <table cellpadding="4">$rows</table>
        """
    } ?: ""

    // ── Team Brain — a solo Brain cannot have cross-author reuse, so
    // showing "0.0%" would read as failure. Explain instead. ──────────
    val teamHtml = if (health.teamAuthors.size >= 2) {
        val reusePct = health.insights?.reusePct ?: 0.0
        val reuseLine = if (reusePct > 0)
            "<p><b>Knowledge reuse:</b> ${"%.1f".format(reusePct)}% of recalls reused a teammate's lesson</p>"
        else
            "<p style='color:gray;'>No cross-author recalls yet — fills up once teammates recall each other's lessons.</p>"
        """
            <h2>👥 Team Brain (${health.teamAuthors.size} contributors)</h2>
            $reuseLine
            <p>${health.teamAuthors.joinToString(" · ") { "<code>${escHtml(it)}</code>" }}</p>
        """
    } else {
        """
            <h2>👥 Team Brain</h2>
            <p style='color:gray;'>Solo Brain — cross-author metrics appear once a teammate uses this instance.
            Set your name in Settings → Tools → Cachly Brain so lessons are attributed to you.</p>
        """
    }

    return htmlText(
        """
            <h2>Brain Overview</h2>
            <table cellpadding="4">
              <tr><td><b>Status:</b></td><td>${statusIcon(health.status)} ${escHtml(health.status)}</td></tr>
              <tr><td><b>Tier:</b></td><td>${escHtml(health.tier)}</td></tr>
              <tr><td><b>Lessons Learned:</b></td><td><b>${health.lessons}</b></td></tr>
              <tr><td><b>Context Entries:</b></td><td>${health.contexts}</td></tr>
              $recallRow
              <tr><td><b>Est. Tokens Saved ($recallScope):</b></td><td>$tokensSaved <i>(~${BrainHealth.TOKENS_PER_RECALL} per reused lesson)</i></td></tr>
              <tr><td><b>Est. Cost Saved:</b></td><td>~$$costSaved</td></tr>
              <tr><td><b>Last Session:</b></td><td>${escHtml(health.lastSession ?: "n/a")}</td></tr>
              <tr><td><b>Storage:</b></td><td><code>$storageBar</code> <b>$usedMB MB</b> / $limitMB MB ($pct%)</td></tr>
            </table>
            $insightsHtml
            $teamHtml
        """,
    )
}

private fun statusIcon(status: String) = when (status) {
    "healthy" -> "✅"
    "degraded" -> "⚠️"
    "setup_needed" -> "🔧"
    else -> "❌"
}

private fun formatMoney(amount: Double, currency: String): String {
    val symbol = if (currency.equals("EUR", ignoreCase = true)) "€" else "\$"
    return "$symbol${"%,.2f".format(amount)}"
}

private fun formatTokens(n: Int): String {
    if (n >= 1_000_000) return "~${"%.1f".format(n / 1_000_000.0)}M tokens"
    if (n >= 1_000) return "~${n / 1_000}k tokens"
    return "~$n tokens"
}

private fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "—"
    if (seconds < 60.0) return "${seconds.toInt()}s"
    if (seconds < 3600.0) return "${(seconds / 60.0).toInt()}m"
    if (seconds < 86400.0) return "${(seconds / 3600.0).toInt()}h"
    val days = (seconds / 86400.0).toInt()
    val hours = ((seconds % 86400.0) / 3600.0).toInt()
    return if (hours > 0) "${days}d ${hours}h" else "${days}d"
}
