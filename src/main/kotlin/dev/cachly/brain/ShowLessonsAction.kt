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

class ShowLessonsAction : AnAction("Show Lessons") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        showPanel(project)
    }

    fun showPanel(project: Project) {
        // FIX: fetch off the EDT — network call must not block the UI thread.
        object : Task.Backgroundable(project, "Fetching Brain Lessons…", false) {
            private var health: BrainHealth? = null
            override fun run(indicator: ProgressIndicator) {
                health = CachlyApiClient.fetchHealth()
            }
            override fun onSuccess() {
                val h = health ?: BrainHealth()
                if (h.topLessons.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        null,
                        "No lessons yet. AI assistants store lessons via learn_from_attempts() after fixing bugs or completing tasks.",
                        "Cachly Brain — Lessons",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    return
                }
                LessonsDialog(project, h).show()
            }
        }.queue()
    }
}

private class LessonsDialog(
    project: Project,
    private val health: BrainHealth,
) : DialogWrapper(project, false) {

    init {
        title = "📖 Cachly Brain — Lessons"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.preferredSize = Dimension(800, 560)

        // The /memory endpoint returns the most-recalled subset, not the full
        // archive — say so instead of titling a partial list "All Lessons".
        val recallScope = if (health.recallLimit > 0) "recalls this month" else "recalls"
        val shownNote = if (health.lessons > health.topLessons.size)
            " &nbsp;·&nbsp; showing the <b>${health.topLessons.size}</b> most-recalled"
        else ""
        val headerHtml = """
            <html>
            <h2>📖 Lessons</h2>
            <p><b>${health.lessons}</b> lessons &nbsp;·&nbsp; <b>${health.totalRecalls}</b> $recallScope$shownNote</p>
            </html>
        """.trimIndent()
        val headerLabel = JLabel(headerHtml)

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        for (l in health.topLessons) {
            val icon = when (l.outcome) {
                "success" -> "✅"
                "failure" -> "❌"
                else -> "⚠️"
            }
            val sevIcon = when (l.severity) {
                "critical" -> "🔴"
                "major" -> "🟠"
                else -> "🟡"
            }
            val date = formatDate(l.ts)
            val lessonHtml = """
                <html>
                <div style="padding:6px 0">
                <b>$icon ${escHtml(l.topic)}</b> &nbsp; $sevIcon ${escHtml(l.severity ?: "minor")} &nbsp; · &nbsp;
                ${l.recallCount}× recalled &nbsp; · &nbsp; $date${l.author?.let { " &nbsp; · &nbsp; by ${escHtml(it)}" } ?: ""}<br/>
                ✔ ${escHtml(l.whatWorked)}
                </div><hr/>
                </html>
            """.trimIndent()
            contentPanel.add(JLabel(lessonHtml))
        }

        val scroll = JScrollPane(contentPanel)
        scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED

        val helpHtml = """
            <html><p style="color:gray;font-size:11px;">
            💡 Lessons are created when an AI assistant calls <code>learn_from_attempts()</code> via the Cachly MCP server.
            Recalls are counted only when a saved lesson is actually reused — token savings are an estimate (~1,200 per reused lesson).</p></html>
        """.trimIndent()

        panel.add(headerLabel, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(JLabel(helpHtml), BorderLayout.SOUTH)
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
