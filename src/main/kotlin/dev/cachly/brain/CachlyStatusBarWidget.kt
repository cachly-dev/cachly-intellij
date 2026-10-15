package dev.cachly.brain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.util.Disposer
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class CachlyStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "CachlyBrainWidget"
    override fun getDisplayName(): String = "Cachly Brain"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = CachlyStatusBarWidget(project)
}

class CachlyStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var currentText = "🧠 Brain: ..."
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var scheduledTask: ScheduledFuture<*>? = null

    override fun ID(): String = "CachlyBrainWidget"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getTooltipText(): String = "Cachly Brain Health — click for details"
    override fun getText(): String = currentText
    override fun getAlignment(): Float = java.awt.Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowBrainHealthAction().showPanel(project)
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        live.add(this)
        // Note: this widget must never POST /recall itself. Heartbeat pings from
        // the IDE inflate the recall counter that every ROI metric is derived
        // from — recalls are only counted when an AI actually reuses a lesson.
        val interval = CachlySettings.getInstance().state.refreshIntervalSec.toLong()
        scheduledTask = scheduler.scheduleAtFixedRate({ refresh() }, 2, interval, TimeUnit.SECONDS)
    }

    private fun refresh() {
        // Nichts senden ohne Einwilligung (GROW-035). Das Widget bleibt sichtbar
        // und sagt, warum es nichts zeigt; ein Klick darauf stellt die Frage bzw.
        // zeigt, wo man es einschaltet.
        if (!funktionJetzt()) {
            zeigeAus()
            return
        }
        // Der 5-Minuten-Takt fragt nur, solange jemand die IDE benutzt: ein
        // Fenster im Hintergrund ist kein Grund, 12 Mal in der Stunde den
        // Server zu fragen (GROW-035, Befund 4). Beim naechsten Takt nach der
        // Rueckkehr in die IDE geht es weiter.
        if (!ApplicationManager.getApplication().isActive) return
        try {
            render(CachlyApiClient.fetchHealth())
        } catch (_: Exception) {
            SwingUtilities.invokeLater {
                currentText = "🧠 Brain: error"
                statusBar?.updateWidget(ID())
            }
        }
    }

    private fun zeigeAus() {
        val text = "🧠 Cachly: off"
        SwingUtilities.invokeLater {
            if (currentText != text) {
                currentText = text
                statusBar?.updateWidget(ID())
            }
        }
    }

    private fun render(health: BrainHealth) {
        try {
            // Brain is reachable — attempt to flush any offline-queued lessons
            if (health.status != "unreachable" && health.status != "not_configured") {
                CachlyApiClient.drainOfflineQueue()
            }
            val settings = CachlySettings.getInstance().state
            val text = when (health.status) {
                "not_configured" -> "🧠 Cachly: not configured"
                "unreachable" -> "🧠 Brain: offline"
                else -> buildStatusText(health, settings)
            }
            SwingUtilities.invokeLater {
                currentText = text
                statusBar?.updateWidget(ID())
                if (!settings.quietMode && !settings.firstHitShown && health.lessons > 0) {
                    settings.firstHitShown = true
                    showFirstHitNotification(health.lessons)
                }
            }
        } catch (_: Exception) {
            SwingUtilities.invokeLater {
                currentText = "🧠 Brain: error"
                statusBar?.updateWidget(ID())
            }
        }
    }

    private fun buildStatusText(health: BrainHealth, settings: CachlySettings.State): String {
        val base = "🧠 Brain: ${health.lessons}"
        // ⟳ = showing the last known counts while reconnecting (cold-start guard).
        val snap = if (health.showingSnapshot) " ⟳" else ""
        val pendingSuffix = (if (health.pendingLessons > 0) " (⏳+${health.pendingLessons})" else "") + snap
        if (!settings.showCostSaved || health.totalRecalls == 0) return "$base lessons$pendingSuffix"
        val costSaved = health.totalRecalls * BrainHealth.TOKENS_PER_RECALL * BrainHealth.COST_PER_TOKEN
        return "$base · ~\$${"%.2f".format(costSaved)} est. saved$pendingSuffix"
    }

    private fun showFirstHitNotification(lessons: Int) {
        try {
            val group = com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Cachly Brain Ambient")
                ?: return
            group.createNotification(
                "Cachly Brain is working!",
                "Your Brain has $lessons lessons loaded. AI assistants connected to this Brain will have instant context.",
                com.intellij.notification.NotificationType.INFORMATION
            ).notify(project)
        } catch (_: Exception) {}
    }

    override fun dispose() {
        live.remove(this)
        scheduledTask?.cancel(false)
        scheduler.shutdown()
    }

    companion object {
        /** Every installed widget — one per open project window. */
        private val live: MutableSet<CachlyStatusBarWidget> = CopyOnWriteArraySet()

        /**
         * Shows an already fetched health result in every status bar NOW,
         * instead of waiting for the next tick.
         *
         * WHY: The widget asks for the first time 2 s after the window opens —
         * before the onboarding has a key — and then only every
         * refreshIntervalSec (default 300 s). A fresh trial therefore sat on
         * "not configured" for up to five minutes, and whoever closed the IDE
         * before that tick never sent a single call with the new key.
         *
         * Takes the result instead of fetching again, so the caller's one
         * request is the only one. Runs on each widget's own scheduler thread,
         * never on the EDT.
         */
        /**
         * Fragt in jeder Statusleiste sofort neu, statt auf den naechsten Takt zu
         * warten — nach einem Ja zur Einwilligung, im Dialog oder in den Einstellungen.
         */
        fun jetztAuffrischen() {
            for (w in live) {
                try {
                    w.scheduler.execute { w.refresh() }
                } catch (_: RejectedExecutionException) {
                    // Widget is being disposed — nothing to show it on.
                }
            }
        }

        fun showEverywhere(health: BrainHealth) {
            for (w in live) {
                try {
                    w.scheduler.execute { w.render(health) }
                } catch (_: RejectedExecutionException) {
                    // Widget is being disposed — nothing to show it on.
                }
            }
        }
    }
}
