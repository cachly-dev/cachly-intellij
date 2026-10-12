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

        // ── Hinweis vom Server ──────────────────────────────────────────
        //
        // Bis zum 17.08.2026 zeigte diesen Satz NUR der MCP-Server. Wer cachly
        // ueber dieses Fenster benutzt und nicht ueber einen Agenten, sah ihn
        // nie — auch nicht die Warnung, dass sein Schluessel nur in dieser IDE
        // liegt und ein neuer Rechner bei null anfaengt.
        //
        // Er steht UEBER dem Offline-Banner: Ein paar ungesendete Lektionen
        // holen sich von selbst nach, ein verlorener Schluessel nicht.
        val noticeBanner: JComponent? = health.notice?.takeIf { it.isNotBlank() }?.let { text ->
            JLabel("<html><body style='padding:6px;'>" + escHtml(text) + "</body></html>").also {
                it.border = javax.swing.BorderFactory.createLineBorder(
                    com.intellij.ui.JBColor(java.awt.Color(0x4a, 0x90, 0xd9), java.awt.Color(0x2a, 0x54, 0x7d)))
                it.isOpaque = true
                it.background = com.intellij.ui.JBColor(java.awt.Color(0xe8, 0xf2, 0xfb), java.awt.Color(0x1b, 0x2a, 0x3a))
            }
        }

        // ── Summary table ───────────────────────────────────────────────
        // Der Text entsteht in brainSummaryHtml (BrainHealthHtml.kt). Vor 0.8.6
        // stand er hier; ein mehrzeiliger Baustein machte daraus rohen
        // Quelltext im Fenster — siehe htmlText in HtmlText.kt.
        val summaryLabel = JLabel(brainSummaryHtml(health))

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
        val helpHtml = htmlText(
            """
            <p style="color:gray; font-size:11px;">
            💡 Lessons are created when an AI assistant calls <code>learn_from_attempts()</code> via the Cachly MCP server.
            Recalls are counted only when a saved lesson is actually reused
            (<code>recall_best_solution()</code>, <code>smart_recall()</code>) — passive IDE activity never counts.
            Token savings are an estimate (~1,200 per reused lesson).</p>
            """,
        )
        val helpLabel = JLabel(helpHtml)

        // ── Layout ──────────────────────────────────────────────────────
        val banner = listOfNotNull(noticeBanner, pendingBanner)
        if (banner.isNotEmpty()) {
            // Von aussen nach innen stapeln, damit die Reihenfolge stimmt:
            // Hinweis oben, danach der Offline-Hinweis, dann die Zahlen.
            var inhalt: JComponent = summaryLabel
            for (b in banner.asReversed()) {
                val huelle = JPanel(BorderLayout(0, 8))
                huelle.add(b, BorderLayout.NORTH)
                huelle.add(inhalt, BorderLayout.CENTER)
                inhalt = huelle
            }
            panel.add(inhalt, BorderLayout.NORTH)
        } else {
            panel.add(summaryLabel, BorderLayout.NORTH)
        }
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(helpLabel, BorderLayout.SOUTH)
        return panel
    }

    private fun formatDate(ts: String?): String {
        if (ts.isNullOrBlank()) return "—"
        return try {
            val instant = Instant.parse(ts)
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) { ts }
    }
}
