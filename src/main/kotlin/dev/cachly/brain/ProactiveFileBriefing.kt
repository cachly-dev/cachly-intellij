package dev.cachly.brain

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Per-file proactive briefing — IntelliJ parity for the VS Code flow:
 * on file open, ask the Brain (POST /briefing, event_type=file_open) whether it
 * holds a medium/high-confidence failure pattern for that path, and if so show
 * a warning notification with severity + confidence and three actions:
 * Show fix (full lesson card with provenance and the "why it fired" tokens),
 * Copy fix (clipboard), Not helpful (suppresses that lesson for that file,
 * persisted across restarts in CachlySettings).
 *
 * Debounced 1200 ms and deduped once per file per session, mirroring the VS
 * Code extension. All network I/O runs off the EDT; every failure is a silent
 * no-op — a briefing must never block or annoy.
 */
object ProactiveFileBriefing {

    private const val DEBOUNCE_MS = 1200L
    private const val SUPPRESSED_MAX = 300

    private val briefedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val debounce = Timer("cachly-briefing-debounce", true)
    private var pending: TimerTask? = null

    fun install(project: Project) {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (!CachlySettings.getInstance().state.proactiveBriefing) return
                    if (!file.isInLocalFileSystem) return
                    schedule(project, file)
                }
            },
        )
    }

    @Synchronized
    private fun schedule(project: Project, file: VirtualFile) {
        pending?.cancel()
        val task = object : TimerTask() {
            override fun run() = brief(project, file)
        }
        pending = task
        debounce.schedule(task, DEBOUNCE_MS)
    }

    /** Runs on the debounce timer thread — network is fine here, EDT is not touched. */
    private fun brief(project: Project, file: VirtualFile) {
        val key = file.url
        if (!briefedFiles.add(key)) return

        val basePath = project.basePath
        val relPath = if (basePath != null && file.path.startsWith(basePath)) {
            file.path.removePrefix(basePath).trimStart('/')
        } else {
            file.name
        }

        val res = CachlyApiClient.fetchBriefing(relPath)
        if (res == null) {
            // Unreachable / not configured — allow a retry on a later open.
            briefedFiles.remove(key)
            return
        }
        if (project.isDisposed) return

        val risk = res.riskLevel.lowercase()
        val suppressed = CachlySettings.getInstance().state.briefingSuppressed
        val warnings = res.warnings.filter { !suppressed.containsKey("$relPath::${it.topic}") }
        if ((risk != "medium" && risk != "high") || warnings.isEmpty()) return

        val top = warnings.first()
        val pct = (top.confidence * 100).toInt()
        val meta = listOfNotNull(
            top.severity.takeIf { it.isNotBlank() },
            if (pct > 0) "$pct%" else null,
        ).joinToString(" · ")
        val more = if (warnings.size > 1) " (+${warnings.size - 1} more)" else ""
        val title = if (risk == "high") "Cachly Brain — high-risk file" else "Cachly Brain — known failure pattern"
        val body = "${if (meta.isNotBlank()) "[$meta] " else ""}${top.message.ifBlank { top.topic }}$more"

        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("Cachly Brain Ambient") ?: return

        if (!ProactiveBudget.claimInterrupt()) return

        CachlyApiClient.trackEvent("intellij_briefing_shown")

        group.createNotification(title, body, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Show fix") {
                track("intellij_briefing_fix_opened")
                BriefingDialog(project, relPath, warnings, res.matchedLessons).show()
            })
            .addAction(NotificationAction.createSimpleExpiring("Copy fix") {
                track("intellij_briefing_fix_copied")
                CopyPasteManager.getInstance()
                    .setContents(StringSelection(top.fix.ifBlank { top.message }))
            })
            .addAction(NotificationAction.createSimpleExpiring("Not helpful") {
                track("intellij_briefing_not_helpful")
                suppress(relPath, top.topic)
            })
            .notify(project)
    }

    /** Telemetry from a notification action (EDT) — hop to a pooled thread. */
    private fun track(event: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            CachlyApiClient.trackEvent(event)
        }
    }

    private fun suppress(relPath: String, topic: String) {
        val map = CachlySettings.getInstance().state.briefingSuppressed
        map["$relPath::$topic"] = System.currentTimeMillis()
        if (map.size > SUPPRESSED_MAX) {
            map.entries.sortedBy { it.value }
                .take(map.size - SUPPRESSED_MAX)
                .forEach { map.remove(it.key) }
        }
    }
}

/**
 * The "Show fix" lesson card — same field set the VS Code briefing panel
 * renders: real outcome, severity, confidence, why-it-fired tokens, the
 * problem/fix split, and learned-date/author provenance.
 */
private class BriefingDialog(
    project: Project,
    private val relPath: String,
    private val warnings: List<BriefingWarning>,
    private val matchedLessons: Int,
) : DialogWrapper(project, false) {

    init {
        title = "Cachly Brain — warning for $relPath"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))
        panel.preferredSize = Dimension(760, 480)

        val matchedNote = if (matchedLessons > warnings.size) {
            " &nbsp;·&nbsp; top <b>${warnings.size}</b> of <b>$matchedLessons</b> matched lessons"
        } else ""
        val headerHtml = """
            <html>
            <h2>Brain warnings for <code>${escHtml(relPath)}</code></h2>
            <p><b>${warnings.size}</b> warning${if (warnings.size == 1) "" else "s"}$matchedNote</p>
            </html>
        """.trimIndent()

        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        for (w in warnings) {
            val outcomeIcon = when (w.outcome) {
                "success" -> "✅"
                "partial" -> "⚠️"
                else -> "❌"
            }
            val sevIcon = when (w.severity) {
                "critical" -> "🔴"
                "major" -> "🟠"
                else -> "🟡"
            }
            val pct = (w.confidence * 100).toInt()
            val why = if (w.matchedOn.isNotEmpty()) {
                "<br/><span style=\"color:gray;font-size:11px;\">Triggered because this file path matches: " +
                    w.matchedOn.joinToString(", ") { "<code>${escHtml(it)}</code>" } + "</span>"
            } else ""
            val problem = if (w.message.isNotBlank()) "<br/><b>Problem</b> &nbsp;${escHtml(w.message)}" else ""
            val fix = if (w.fix.isNotBlank()) "<br/><b>Fix</b> &nbsp;${escHtml(w.fix)}" else ""
            val prov = listOfNotNull(
                formatDate(w.learnedAt)?.let { "learned $it" },
                w.author?.takeIf { it.isNotBlank() }?.let { "by ${escHtml(it)}" },
            ).joinToString(" · ")
            val provHtml = if (prov.isNotBlank()) {
                "<br/><span style=\"color:gray;font-size:11px;\">$prov</span>"
            } else ""

            val cardHtml = """
                <html>
                <div style="padding:6px 0">
                <b>$outcomeIcon ${escHtml(w.topic)}</b> &nbsp; $sevIcon ${escHtml(w.severity)}${if (pct > 0) " &nbsp;·&nbsp; $pct% confidence" else ""}
                $why$problem$fix$provHtml
                </div><hr/>
                </html>
            """.trimIndent()
            contentPanel.add(JLabel(cardHtml))
        }

        val scroll = JScrollPane(contentPanel)
        scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED

        panel.add(JLabel(headerHtml), BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }


    private fun formatDate(ts: String?): String? {
        if (ts.isNullOrBlank()) return null
        return try {
            val instant = Instant.parse(ts)
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(instant)
        } catch (_: Exception) { null }
    }
}
