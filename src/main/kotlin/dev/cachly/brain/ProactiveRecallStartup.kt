package dev.cachly.brain

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

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
class ProactiveRecallStartup : ProjectActivity {

    // ProjectActivity statt StartupActivity (0.8.6): Ab IDE 253 meldet die
    // Altform "Migrate to ProjectActivity" und das Onboarding wartete hinter
    // offenen Dialogen. Ablauf unveraendert; die Arbeit mit Netz laeuft wie
    // bisher auf einem eigenen Thread.
    override suspend fun execute(project: Project) {
        // Install the per-file briefing listener unconditionally — it re-checks
        // the proactiveBriefing toggle (and the consent) on every event, so
        // flipping a setting takes effect without a restart.
        ProactiveFileBriefing.install(project)
        verbinden(project)
    }

    companion object {
        /**
         * Onboarding, Reparatur und Begruessung beim Oeffnen eines Projekts.
         *
         * Eigener Einstieg, weil es nach einem Ja in der Frage (EinwilligungDialog,
         * GROW-035) fuer das aktuelle Projekt noch einmal aufgerufen wird: Beim
         * Start lief es, bevor die Antwort da war, und hat ohne Einwilligung
         * nichts getan. Ohne Einwilligung tut es auch jetzt nichts.
         */
        fun verbinden(project: Project) {
            if (!funktionJetzt()) return

            // No key means nothing below can work: fetchHealth would answer
            // "not healthy" and every branch after it returns in silence. Until
            // 2026-08-17 that silence WAS the first-run experience — 18 downloads,
            // zero registrations. Onboarding therefore runs before the briefing and
            // independently of the proactiveBriefing toggle, which is about popups
            // for an already-connected Brain, not about getting connected at all.
            if (CachlyOnboarding.needsKey()) {
                CachlyOnboarding.maybeOnboard(project)
                return
            }

            // A key without an instance id makes every call below return before
            // the network — see CachlyApiClient.ensureInstanceId. Installs that
            // got such a trial before 0.8.3 are repaired here, on the next start.
            if (CachlySettings.getInstance().state.instanceId.isBlank()) {
                CachlyOnboarding.repairMissingInstance(project)
                return
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                val health = try {
                    CachlyApiClient.fetchHealth()
                } catch (_: Exception) {
                    return@executeOnPooledThread
                }
                // Nach einem Ja in der Frage zeigt die Statusleiste sonst bis zum
                // naechsten Takt "off".
                CachlyStatusBarWidget.showEverywhere(health)
                // 11.10.2026: Das Plugin meldete bis 0.8.3 nur "Briefing gezeigt" —
                // 12 neue JetBrains-Konten an einem Tag, 0 Ereignisse, Nutzung
                // unsichtbar. Gleiche Namen wie MCP-Server und VS Code, damit
                // Kohorte und Trichter sie mitzaehlen. first_call_success wird
                // serverseitig nur beim ersten Mal als Aktivierung gemeldet.
                // (trackEvent sendet nur mit der Statistik-Einwilligung.)
                if (health.status == "healthy") {
                    CachlyApiClient.trackEvent("first_call_success")
                    CachlyApiClient.trackEvent("session_start")
                    // 12.10.2026: 11 JetBrains-Konten, 0 Recalls — AI Assistant
                    // kannte den MCP-Server nie. Fragt nur, wenn er dort fehlt.
                    AiAssistantAnbindung.anbieten(project)
                }
                // showProjectBriefing prueft proactiveBriefing selbst.
                showProjectBriefing(project, health)
            }
        }

        /**
         * The "welcome back" briefing with the top lessons. Silent when there is
         * nothing to brief (not healthy, no lessons). Must run off the EDT.
         *
         * Shared with CachlyOnboarding: someone who links an existing account
         * gets the briefing right after connecting, not only on the next
         * project open.
         */
        fun showProjectBriefing(project: Project, health: BrainHealth) {
            if (project.isDisposed) return
            if (!CachlySettings.getInstance().state.proactiveBriefing) return
            if (health.status != "healthy" || health.lessons <= 0 || health.topLessons.isEmpty()) {
                return
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
            if (top.isEmpty()) return

            val recallLabel = if (health.recallLimit > 0) {
                "${health.totalRecalls}/${health.recallLimit} recalls this month"
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
                .getNotificationGroup("Cachly Brain Ambient") ?: return

            if (!ProactiveBudget.claimInterrupt()) return

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
