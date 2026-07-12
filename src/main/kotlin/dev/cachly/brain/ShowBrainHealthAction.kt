package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ShowBrainHealthAction : AnAction("Show Brain Health") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        showPanel(project)
    }

    fun showPanel(project: Project) {
        // FIX: fetch off the EDT — network call must not block the UI thread.
        object : Task.Backgroundable(project, "Fetching Brain Health…", false) {
            private var health: BrainHealth? = null
            override fun run(indicator: ProgressIndicator) {
                health = CachlyApiClient.fetchHealth()
            }
            override fun onSuccess() {
                val dialog = BrainHealthDialog(project, health ?: BrainHealth())
                dialog.show()
            }
        }.queue()
    }
}

private class BrainHealthDialog(
    project: Project,
    private val health: BrainHealth,
) : DialogWrapper(project, false) {

    init {
        title = "🧠 Cachly Brain Health"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.preferredSize = Dimension(700, 580)

        // ── Offline-pending banner (theme-aware via JBColor) ────────────
        val pendingBanner: JComponent? = if (health.pendingLessons > 0) {
            val msg = "⏳ ${health.pendingLessons} lesson${if (health.pendingLessons == 1) "" else "s"} saved offline — not yet synced to Brain. Will upload automatically on next refresh."
            JLabel("<html><body style='padding:6px;'>$msg</body></html>").also {
                it.border = javax.swing.BorderFactory.createLineBorder(
                    com.intellij.ui.JBColor(java.awt.Color(0xcc, 0xa0, 0x00), java.awt.Color(0x7a, 0x60, 0x00)))
                it.isOpaque = true
                it.background = com.intellij.ui.JBColor(java.awt.Color(0xff, 0xf4, 0xcc), java.awt.Color(0x3d, 0x30, 0x00))
            }
        } else null

        // ── Summary table ───────────────────────────────────────────────
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
            "<tr><td><b>Recalls $recallScope:</b></td><td><b>${health.totalRecalls}</b> / ${health.recallLimit.toLocaleString()}$note</td></tr>"
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
            <p>${health.teamAuthors.joinToString(" · ") { "<code>$it</code>" }}</p>
            """.trimIndent()
        } else {
            """
            <h2>👥 Team Brain</h2>
            <p style='color:gray;'>Solo Brain — cross-author metrics appear once a teammate uses this instance.
            Set your name in Settings → Tools → Cachly Brain so lessons are attributed to you.</p>
            """.trimIndent()
        }

        val summaryHtml = """
            <html>
            <h2>Brain Overview</h2>
            <table cellpadding="4">
              <tr><td><b>Status:</b></td><td>${statusIcon(health.status)} ${health.status}</td></tr>
              <tr><td><b>Tier:</b></td><td>${health.tier}</td></tr>
              <tr><td><b>Lessons Learned:</b></td><td><b>${health.lessons}</b></td></tr>
              <tr><td><b>Context Entries:</b></td><td>${health.contexts}</td></tr>
              $recallRow
              <tr><td><b>Est. Tokens Saved ($recallScope):</b></td><td>$tokensSaved <i>(~${BrainHealth.TOKENS_PER_RECALL} per reused lesson)</i></td></tr>
              <tr><td><b>Est. Cost Saved:</b></td><td>~$$costSaved</td></tr>
              <tr><td><b>Last Session:</b></td><td>${health.lastSession ?: "n/a"}</td></tr>
              <tr><td><b>Storage:</b></td><td><code>$storageBar</code> <b>$usedMB MB</b> / $limitMB MB ($pct%)</td></tr>
            </table>
            $insightsHtml
            $teamHtml
            </html>
        """.trimIndent()
        val summaryLabel = JLabel(summaryHtml)

        // ── Lessons table ───────────────────────────────────────────────
        val columns = arrayOf("Topic", "Outcome", "Recalls", "Severity", "What Worked", "Date")
        val data = health.topLessons.map { l ->
            arrayOf(
                l.topic,
                if (l.outcome == "success") "✅" else if (l.outcome == "failure") "❌" else "⚠️",
                l.recallCount.toString(),
                l.severity ?: "minor",
                if (l.whatWorked.length > 80) l.whatWorked.take(80) + "…" else l.whatWorked,
                formatDate(l.ts),
            )
        }.toTypedArray()

        // Read-only model — the default JTable model makes every cell editable,
        // which lets users "edit" display data that is never persisted.
        val model = object : javax.swing.table.DefaultTableModel(data, columns) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        val table = JTable(model).apply {
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            fillsViewportHeight = true
        }
        val scrollPane = JScrollPane(table)

        // ── Help text ───────────────────────────────────────────────────
        val helpHtml = """
            <html><p style="color:gray; font-size:11px;">
            💡 Lessons are created when an AI assistant calls <code>learn_from_attempts()</code> via the Cachly MCP server.
            Recalls are counted only when a saved lesson is actually reused
            (<code>recall_best_solution()</code>, <code>smart_recall()</code>) — passive IDE activity never counts.
            Token savings are an estimate (~1,200 per reused lesson).</p></html>
        """.trimIndent()
        val helpLabel = JLabel(helpHtml)

        // ── Layout ──────────────────────────────────────────────────────
        if (pendingBanner != null) {
            val north = JPanel(BorderLayout(0, 8))
            north.add(pendingBanner, BorderLayout.NORTH)
            north.add(summaryLabel, BorderLayout.CENTER)
            panel.add(north, BorderLayout.NORTH)
        } else {
            panel.add(summaryLabel, BorderLayout.NORTH)
        }
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(helpLabel, BorderLayout.SOUTH)
        return panel
    }

    private fun statusIcon(status: String) = when (status) {
        "healthy" -> "✅"
        "degraded" -> "⚠️"
        "setup_needed" -> "🔧"
        else -> "❌"
    }

    private fun formatDate(ts: String?): String {
        if (ts.isNullOrBlank()) return "—"
        return try {
            val instant = Instant.parse(ts)
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) { ts }
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
}

private fun Int.toLocaleString(): String = String.format("%,d", this)
