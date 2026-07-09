package dev.cachly.brain

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Read-side "ambient recall" for JetBrains: on project open, fetch the Brain's
 * top lessons and surface a compact briefing notification — the push-based
 * counterpart to "Show Lessons" (pull). This is IntelliJ's parity for the VS
 * Code startup briefing (Ambient Recall Tier C — docs/make_cachly_great_again.md §6.7).
 *
 * JetBrains AI Assistant exposes no third-party context-injection point, so the
 * briefing surfaces to the developer (and to any agent reading the generated
 * rules files), not into a proprietary chat — see §6.7 for why per-prompt
 * injection stays Claude-Code-only.
 *
 * Fires once per project open, and only when there is something to brief
 * (status healthy, lessons > 0), so it never nags an empty/unconnected Brain.
 * Opt-out: Settings -> Cachly Brain -> "Proactive briefing on project open".
 * All network I/O runs off the EDT; any failure is a silent no-op — a briefing
 * must never block or slow project startup.
 */
class ProactiveRecallStartup : StartupActivity {

    override fun runActivity(project: Project) {
        if (!CachlySettings.getInstance().state.proactiveBriefing) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val health = try {
                CachlyApiClient.fetchHealth()
            } catch (_: Exception) {
                return@executeOnPooledThread
            }
            if (project.isDisposed) return@executeOnPooledThread
            if (health.status != "healthy" || health.lessons <= 0 || health.topLessons.isEmpty()) {
                return@executeOnPooledThread
            }

            // Skip low-signal auto-changelog topics (bare "deploy:", "auto:*") and
            // brief the highest-reuse lessons — same filter as the Claude Code
            // SessionStart hook, so the two harnesses surface the same signal.
            val meaningful = health.topLessons.filter { l ->
                val t = l.topic
                !t.startsWith("auto:") && !Regex("^[a-z]+:?$").matches(t)
            }
            val top = (if (meaningful.isNotEmpty()) meaningful else health.topLessons)
                .sortedByDescending { it.recallCount }
                .take(3)
            if (top.isEmpty()) return@executeOnPooledThread

            val recallLabel = if (health.recallLimit > 0) {
                "${health.totalRecalls}/${health.recallLimit} recalls"
            } else {
                "${health.totalRecalls} recalls"
            }
            val body = buildString {
                append("Welcome back — ${health.lessons} lessons · $recallLabel. Top for this project:")
                for (l in top) {
                    val sev = l.severity?.let { "[$it] " } ?: ""
                    val what = l.whatWorked.replace(Regex("\\s+"), " ").trim()
                        .let { if (it.length > 120) it.take(120) + "…" else it }
                    append("\n• $sev${l.topic}: $what")
                }
            }

            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup("Cachly Brain Ambient") ?: return@executeOnPooledThread

            group.createNotification(
                "Cachly Brain — Ambient Recall",
                body,
                NotificationType.INFORMATION,
            ).addAction(
                NotificationAction.createSimple("Show all lessons") {
                    ShowLessonsAction().showPanel(project)
                },
            ).notify(project)
        }
    }
}
