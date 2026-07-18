package dev.cachly.brain

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.util.Disposer
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
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
        // Note: this widget must never POST /recall itself. Heartbeat pings from
        // the IDE inflate the recall counter that every ROI metric is derived
        // from — recalls are only counted when an AI actually reuses a lesson.
        val interval = CachlySettings.getInstance().state.refreshIntervalSec.toLong()
        scheduledTask = scheduler.scheduleAtFixedRate({ refresh() }, 2, interval, TimeUnit.SECONDS)
    }

    private fun refresh() {
        try {
            val health = CachlyApiClient.fetchHealth()
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
        scheduledTask?.cancel(false)
        scheduler.shutdown()
    }
}
