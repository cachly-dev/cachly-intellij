package dev.cachly.brain

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Gets the user a working Brain key without asking them to find one.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Measured on 2026-08-17: the JetBrains plugin had 18 marketplace downloads and
 * produced zero registrations. The reason was not a broken flow — there was no
 * flow. The plugin offered exactly one way in, an empty text field in
 * Settings -> Tools -> Cachly Brain, and on first start without a key
 * ProactiveRecallStartup fetched health, saw "not healthy", and returned in
 * silence. Nothing asked the user to sign up and nothing showed them where.
 *
 * The VS Code extension has had instant-trial and the device flow wired for
 * weeks. This is the same chain, in the same order, for JetBrains:
 *
 *   1. instant-trial  — a key with zero clicks and no account (the normal case)
 *   2. device flow    — browser approval for someone who already has an account
 *   3. dashboard link — visible last resort, never a silent dead end
 *
 * Every step may fail. A failed step must never throw into IDE startup and must
 * never leave the user with nothing on screen — that silent third outcome is
 * exactly what produced the zero.
 */
object CachlyOnboarding {

    private const val GROUP = "Cachly Brain Ambient"
    private const val DASHBOARD = "https://cachly.dev/dashboard"

    /**
     * Number of self-serve attempts before the plugin stops trying by itself and
     * only offers the manual path. Three is enough to survive an API restart
     * across a few project opens without minting trials forever.
     */
    private const val MAX_ATTEMPTS = 3

    /** True when the plugin currently has no usable credential. */
    fun needsKey(): Boolean = CachlySettings.getInstance().state.apiKey.isBlank()

    /**
     * Automatic path, called from project open. Does nothing at all when a key
     * is already stored, when the user has declined, or when the self-serve
     * attempts are used up.
     *
     * Runs its network I/O on a pooled thread; safe to call from the EDT.
     */
    fun maybeOnboard(project: Project) {
        val state = CachlySettings.getInstance().state
        if (!needsKey()) return
        if (state.onboardingDeclined) return
        if (state.onboardingAttempts >= MAX_ATTEMPTS) {
            offerManualSetup(project, "Cachly Brain is not connected yet.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            state.onboardingAttempts += 1
            if (tryInstantTrial(project)) return@executeOnPooledThread
            // No trial and no key: offer the account path instead of going quiet.
            offerDeviceFlow(project)
        }
    }

    /**
     * Manual path, called from the "Connect Brain" action. Ignores the declined
     * flag and the attempt cap — the user asked for it explicitly this time.
     */
    fun connectNow(project: Project) {
        val state = CachlySettings.getInstance().state
        state.onboardingDeclined = false
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!needsKey()) {
                notify(project, "Cachly Brain is already connected.", NotificationType.INFORMATION)
                return@executeOnPooledThread
            }
            if (tryInstantTrial(project)) return@executeOnPooledThread
            startDeviceFlow(project)
        }
    }

    /**
     * Asks the API for a trial key and stores it. Returns true on success.
     *
     * Must run off the EDT.
     */
    private fun tryInstantTrial(project: Project): Boolean {
        val resp = CachlyApiClient.requestInstantTrial() ?: return false
        val state = CachlySettings.getInstance().state
        state.apiKey = resp.apiKey
        if (resp.instanceId.isNotBlank()) state.instanceId = resp.instanceId
        state.trialEndsAt = resp.trialEndsAt ?: ""
        state.onboardingAttempts = 0

        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return true
        group.createNotification(
            "Cachly Brain — trial started",
            "Your assistant now has persistent memory. Link an account to keep the data beyond the trial.",
            NotificationType.INFORMATION,
        ).addAction(
            NotificationAction.createSimple("Link account") { startDeviceFlow(project) },
        ).addAction(
            NotificationAction.createSimple("Set up AI files") {
                BrowserUtil.browse("https://cachly.dev/docs/jetbrains")
            },
        ).notify(project)
        return true
    }

    /**
     * Trial unavailable — ask before opening a browser, since the device flow
     * needs the user to actually be at the keyboard.
     */
    private fun offerDeviceFlow(project: Project) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        group.createNotification(
            "Cachly Brain — not connected",
            "Connect your Brain so your assistant remembers this project between sessions.",
            NotificationType.INFORMATION,
        ).addAction(
            NotificationAction.createSimple("Connect") { startDeviceFlow(project) },
        ).addAction(
            NotificationAction.createSimple("Not now") {
                CachlySettings.getInstance().state.onboardingDeclined = true
            },
        ).notify(project)
    }

    /**
     * Device authorization flow (RFC 8628): open the approval page with the code
     * pre-filled, then wait for approval in a cancellable background task.
     *
     * The waiting loop deliberately reads the parsed body and not the HTTP
     * status: while approval is pending the API answers 200 with
     * {"error":"authorization_pending"}. Treating a non-200 as "keep waiting"
     * would poll for ten minutes against a dead endpoint, and treating any 200
     * as success would store an empty key.
     */
    fun startDeviceFlow(project: Project) {
        object : Task.Backgroundable(project, "Connecting Cachly Brain", true) {
            override fun run(indicator: ProgressIndicator) {
                val device = CachlyApiClient.requestDeviceCode()
                if (device == null) {
                    offerManualSetup(project, "Automatic setup is unavailable right now.")
                    return
                }
                BrowserUtil.browse("${device.verificationUri}?code=${device.userCode}")
                indicator.text = "Enter code ${device.userCode} at ${device.verificationUri}"
                indicator.isIndeterminate = true

                val stepMs = (if (device.interval > 0) device.interval else 5) * 1000L
                val deadline = System.currentTimeMillis() +
                    (if (device.expiresIn > 0) device.expiresIn else 600) * 1000L

                while (System.currentTimeMillis() < deadline) {
                    if (indicator.isCanceled || project.isDisposed) return
                    try {
                        Thread.sleep(stepMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                    if (indicator.isCanceled) return
                    val poll = CachlyApiClient.pollDeviceToken(device.deviceCode)
                    val token = poll?.accessToken
                    if (!token.isNullOrBlank()) {
                        val state = CachlySettings.getInstance().state
                        state.apiKey = token
                        if (!poll.instanceId.isNullOrBlank()) state.instanceId = poll.instanceId
                        state.trialEndsAt = ""
                        state.onboardingAttempts = 0
                        notify(
                            project,
                            "Cachly Brain connected. Your assistant keeps its memory from here on.",
                            NotificationType.INFORMATION,
                        )
                        return
                    }
                    // Terminal answers: stop instead of waiting out the deadline.
                    if (poll?.error == "expired_token" || poll?.error == "access_denied") break
                    // "authorization_pending" and an unreadable answer both mean: keep waiting.
                }
                offerManualSetup(project, "The setup code expired before it was approved.")
            }
        }.queue()
    }

    /**
     * The visible dead end. Reached only when the automatic paths are down, and
     * it still leaves two clickable ways forward — a silent return here is the
     * bug this whole file fixes.
     */
    private fun offerManualSetup(project: Project, reason: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        group.createNotification(
            "Cachly Brain — setup needed",
            "$reason Get a key from the dashboard and paste it into the settings.",
            NotificationType.WARNING,
        ).addAction(
            NotificationAction.createSimple("Open dashboard") { BrowserUtil.browse(DASHBOARD) },
        ).addAction(
            NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CachlySettingsConfigurable::class.java)
            },
        ).notify(project)
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        group.createNotification("Cachly Brain", text, type).notify(project)
    }
}
