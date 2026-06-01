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

class ShowLessonsAction : AnAction("Show Lessons") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        showPanel(project)
    }

    fun showPanel(project: Project) {
        val health = CachlyApiClient.fetchHealth()
        if (health.topLessons.isEmpty()) {
            JOptionPane.showMessageDialog(
                null,
                "No lessons yet. AI assistants store lessons via learn_from_attempts() after fixing bugs or completing tasks.",
                "Cachly Brain — Lessons",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        LessonsDialog(project, health).show()
    }
}

private class LessonsDialog(
    project: Project,
    private val health: BrainHealth,
) : DialogWrapper(project, false) {

    init {
        title = "📖 Cachly Brain — All Lessons"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.preferredSize = Dimension(800, 560)

        val tokensSaved = health.estimatedTokensSaved
        val headerHtml = """
            <html>
            <h2>📖 All Lessons</h2>
            <p><b>${health.lessons}</b> lessons &nbsp;·&nbsp; <b>${health.totalRecalls}</b> total recalls
            &nbsp;·&nbsp; ~<b>$tokensSaved</b> tokens saved</p>
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
                ${l.recallCount}× recalled &nbsp; · &nbsp; $date<br/>
                <span style="color:#444">✔ ${escHtml(l.whatWorked)}</span>
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
            Each recall via <code>recall_best_solution()</code> saves ~1,200 tokens.</p></html>
        """.trimIndent()

        panel.add(headerLabel, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(JLabel(helpHtml), BorderLayout.SOUTH)
        return panel
    }

    private fun escHtml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun formatDate(ts: String?): String {
        if (ts.isNullOrBlank()) return "—"
        return try {
            val instant = Instant.parse(ts)
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) { ts }
    }
}
