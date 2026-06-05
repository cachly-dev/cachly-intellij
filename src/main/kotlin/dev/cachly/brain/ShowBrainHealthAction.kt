package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
        val health = CachlyApiClient.fetchHealth()
        val dialog = BrainHealthDialog(project, health)
        dialog.show()
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
        panel.preferredSize = Dimension(700, 520)

        // ── Offline-pending banner (only shown when lessons are queued locally) ─
        val pendingBanner: JComponent? = if (health.pendingLessons > 0) {
            val msg = "⏳ ${health.pendingLessons} lesson${if (health.pendingLessons == 1) "" else "s"} saved offline — not yet synced to Brain. Will upload automatically on next refresh."
            JLabel("<html><body style='background:#7a6000;color:#ffe58f;padding:6px;'>$msg</body></html>").also {
                it.border = javax.swing.BorderFactory.createLineBorder(java.awt.Color(0xcc, 0xa0, 0x00))
                it.isOpaque = true
                it.background = java.awt.Color(0x3d, 0x30, 0x00)
            }
        } else null

        // ── Summary table ─────────────────────────────────────────────
        val tokensSaved = fmtTokens(health.estimatedTokensSaved)
        val costSaved = "%.4f".format(health.estimatedTokensSaved * 0.000003)
        val usedMB = "%.2f".format(health.memoryUsedBytes / (1024.0 * 1024.0))
        val limitMB = health.memoryLimitBytes / (1024 * 1024)
        val pct = "%.1f".format(health.memoryUsedPct)
        val barLen = 20
        val filled = (health.memoryUsedPct / 100.0 * barLen).toInt().coerceIn(0, barLen)
        val storageBar = "█".repeat(filled) + "░".repeat(barLen - filled)
        val summaryHtml = """
            <html>
            <h2>Brain Overview</h2>
            <table cellpadding="4">
              <tr><td><b>Status:</b></td><td>${statusIcon(health.status)} ${health.status}</td></tr>
              <tr><td><b>Tier:</b></td><td>${health.tier}</td></tr>
              <tr><td><b>Lessons Learned:</b></td><td><b>${health.lessons}</b></td></tr>
              <tr><td><b>Context Entries:</b></td><td>${if (health.contexts > 0) health.contexts.toString() else "<i>0</i>"}</td></tr>
              <tr><td><b>Total Recalls:</b></td><td><b>${health.totalRecalls}</b></td></tr>
              <tr><td><b>Est. Tokens Saved:</b></td><td>$tokensSaved</td></tr>
              <tr><td><b>Est. Cost Saved:</b></td><td>~$$costSaved</td></tr>
              <tr><td><b>Last Session:</b></td><td>${health.lastSession ?: "n/a"}</td></tr>
              <tr><td><b>Storage:</b></td><td><code>$storageBar</code> <b>$usedMB MB</b> / $limitMB MB ($pct%)</td></tr>
            </table>
            </html>
        """.trimIndent()
        val summaryLabel = JLabel(summaryHtml)

        // ── Lessons table ─────────────────────────────────────────────
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

        val table = JTable(data, columns).apply {
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            fillsViewportHeight = true
        }
        val scrollPane = JScrollPane(table)

        // ── Help text ─────────────────────────────────────────────────
        val helpHtml = """
            <html><p style="color:gray; font-size:11px;">
            💡 Lessons are created when an AI assistant calls <code>learn_from_attempts()</code> via the Cachly MCP server.
            Recalls happen via <code>recall_best_solution()</code> or <code>session_start()</code>.
            Each recall saves ~1,200 tokens.</p></html>
        """.trimIndent()
        val helpLabel = JLabel(helpHtml)

        // ── Layout ────────────────────────────────────────────────────
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

    private fun fmtTokens(n: Long): String = when {
        n >= 1_000_000 -> "~${"%.1f".format(n / 1_000_000.0)}M tokens"
        n >= 1_000     -> "~${n / 1_000}k tokens"
        else           -> "~$n tokens"
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
}
