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
     * What the plugin does and how an AI assistant gets connected. Until 0.8.3
     * the trial notification linked here while the page did not exist (HTTP
     * 404 on 2026-10-11) — the first click after a successful signup ended on
     * an error page.
     */
    private const val GUIDE = "https://cachly.dev/docs/jetbrains"

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
     *
     * Ohne Einwilligung (GROW-035) laeuft hier NICHTS und es wird auch kein
     * Versuch gezaehlt: `onboardingAttempts` ist gedeckelt, und ein Versuch, der
     * nur an der fehlenden Antwort scheitert, wuerde spaeter fehlen.
     */
    fun maybeOnboard(project: Project) {
        val state = CachlySettings.getInstance().state
        if (!darfFunktion(state)) return
        if (!needsKey()) return
        if (state.onboardingDeclined) return
        if (state.onboardingAttempts >= MAX_ATTEMPTS) {
            offerManualSetup(project, "Cachly Brain is not connected yet.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            // Zwischen Start und Thread kann die Einwilligung widerrufen worden sein.
            if (!darfFunktion(state)) return@executeOnPooledThread
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
        // Ausdrueckliche Aktion: fragt, falls noch nicht beantwortet (GROW-035).
        if (!EinwilligungFrage.fuerAktion(project)) return
        val state = CachlySettings.getInstance().state
        state.onboardingDeclined = false
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!needsKey()) {
                if (state.instanceId.isBlank()) {
                    // Key without instance: "already connected" would be a lie —
                    // nothing works until the instance id is there.
                    firstContact(project, trial = state.trialEndsAt.isNotBlank())
                    return@executeOnPooledThread
                }
                notify(project, "Cachly Brain is already connected.", NotificationType.INFORMATION)
                return@executeOnPooledThread
            }
            if (tryInstantTrial(project)) return@executeOnPooledThread
            startDeviceFlow(project)
        }
    }

    /**
     * Startup repair for a stored key without an instance id. Silent when the
     * instance still cannot be fetched — the next project open tries again,
     * and the manual "Connect Brain" action reports the failure visibly.
     */
    fun repairMissingInstance(project: Project) {
        if (!funktionJetzt()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            if (!CachlyApiClient.ensureInstanceId()) return@executeOnPooledThread
            firstContact(project, trial = CachlySettings.getInstance().state.trialEndsAt.isNotBlank())
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
        CachlySettings.getInstance().schluesselSichern()
        firstContact(project, trial = true)
        return true
    }

    /**
     * The first real request with a freshly stored key — immediately, not at
     * the next status-bar tick.
     *
     * WHY (measured 2026-10-11, production): 10 accounts were created by
     * cachly-intellij/0.8.x in 60 days, and 6 of those keys were never used
     * once (api_keys.last_used_at empty). The routes and the header were fine:
     * every request of this plugin goes to /api/v1/... with
     * "Authorization: Bearer cky_...", and that middleware stamps
     * last_used_at on every valid key. So the plugin sent nothing with the new
     * key. The code shows how: the status bar had asked 2 s after the window
     * opened — before the key existed — and asked again only after
     * refreshIntervalSec (300 s). The file briefing skipped every file opened
     * before the key. An IDE session shorter than that sent no request with
     * the key at all and showed "not configured" the whole time. Which of the
     * six ended exactly that way the server cannot tell; that this path
     * existed, the code can.
     *
     * Now, in this order:
     *   1. fetch the instance id if the trial answer carried none,
     *   2. ONE health request (instance, memory, insights) — the call that
     *      stamps last_used_at and proves the key works,
     *   3. show its result in every status bar right away,
     *   4. tell the user what happened — success or the reason it failed,
     *      never silence,
     *   5. show the project briefing when the Brain already holds lessons
     *      (a linked account; a fresh trial has none).
     *
     * Must run off the EDT. Never throws.
     */
    private fun firstContact(project: Project, trial: Boolean) {
        try {
            val hasInstance = CachlyApiClient.ensureInstanceId()
            val health = if (hasInstance) CachlyApiClient.fetchHealth() else null
            if (health != null) CachlyStatusBarWidget.showEverywhere(health)
            if (project.isDisposed) return

            val reached = health != null &&
                health.status != "unreachable" && health.status != "not_configured"
            if (reached) {
                announceConnected(project, health!!, trial)
                AiAssistantAnbindung.anbieten(project)
                ProactiveRecallStartup.showProjectBriefing(project, health)
                // Ein frischer Test ist leer: Git-Geschichte einlesen, danach die Regeldatei fuellen.
                StartwissenDienst.anstossen(project)
            } else {
                announceNotReached(project, if (hasInstance) "the Brain did not answer" else "no Brain instance was assigned")
            }
        } catch (_: Exception) {
            // Onboarding must never throw into a pooled thread's caller.
        }
    }

    private fun announceConnected(project: Project, health: BrainHealth, trial: Boolean) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        val lessons = if (health.lessons == 1) "1 lesson" else "${health.lessons} lessons"
        val notification = if (trial) {
            group.createNotification(
                "Cachly Brain — trial brain connected",
                "Your Brain answers: $lessons so far. Connect your AI assistant so it can read and add lessons. " +
                    "Link an account to keep the data beyond the trial.",
                NotificationType.INFORMATION,
            ).addAction(
                NotificationAction.createSimple("How to connect your AI") { BrowserUtil.browse(GUIDE) },
            ).addAction(
                NotificationAction.createSimple("Link account") { linkAccount(project) },
            )
        } else {
            group.createNotification(
                "Cachly Brain — connected",
                "Your Brain answers: $lessons. Your AI assistant uses it once it is connected over MCP.",
                NotificationType.INFORMATION,
            ).addAction(
                NotificationAction.createSimple("How to connect your AI") { BrowserUtil.browse(GUIDE) },
            )
        }
        notification.notify(project)
    }

    /**
     * The key is stored, but the first request failed. Said out loud, with the
     * way to the reason — a stored key that silently does nothing is the state
     * this whole chain exists to end.
     */
    private fun announceNotReached(project: Project, reason: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        val every = CachlySettings.getInstance().state.refreshIntervalSec
        group.createNotification(
            "Cachly Brain — key saved, Brain not reached",
            "The key is saved, but $reason. The status bar tries again every $every s. " +
                "Tools → Run Brain Doctor shows the reason.",
            NotificationType.WARNING,
        ).addAction(
            NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CachlySettingsConfigurable::class.java)
            },
        ).notify(project)
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
        if (!EinwilligungFrage.fuerAktion(project)) return
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
                        // /auth/device/token answers {access_token, token_type}
                        // and NO instance id (api/internal/handler/
                        // device_auth_handler.go). Keeping the old id would
                        // point the new account's key at the trial's instance;
                        // clearing it lets firstContact fetch the right one.
                        state.instanceId = poll.instanceId?.takeIf { it.isNotBlank() } ?: ""
                        state.trialEndsAt = ""
                        state.onboardingAttempts = 0
                        CachlySettings.getInstance().schluesselSichern()
                        firstContact(project, trial = false)
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
     * "Link account". With a trial key the trial Brain moves into the account
     * and keeps its lessons (TestBrainUebernahme). Until 0.8.7 this button ran
     * [startDeviceFlow], which ends in the account's own, empty Brain — the
     * trial lessons stayed behind. Without a trial key there is nothing to
     * move, and the account sign-in runs as before.
     */
    fun linkAccount(project: Project) {
        // TestBrainUebernahme selbst fragt nicht: die Netzfunktionen dort sind rein
        // und laufen in den Tests gegen einen lokalen Server. Die Sperre steht hier.
        if (!EinwilligungFrage.fuerAktion(project)) return
        val testKey = CachlySettings.getInstance().state.apiKey.trim()
        if (!TestBrainUebernahme.istTestSchluessel(testKey)) {
            startDeviceFlow(project)
            return
        }
        object : Task.Backgroundable(project, "Linking Cachly Brain to your account", true) {
            override fun run(indicator: ProgressIndicator) {
                val code = TestBrainUebernahme.starteAnmeldung()
                if (code == null) {
                    notify(project, "Linking could not start: the sign-in service did not answer. Nothing was changed.", NotificationType.WARNING)
                    return
                }
                BrowserUtil.browse(code.adresse)
                indicator.text = "Sign in and confirm code ${code.userCode} in the browser"
                indicator.isIndeterminate = true

                var schrittMs = code.intervallMs
                var token: String? = null
                while (token == null && System.currentTimeMillis() < code.frist) {
                    if (indicator.isCanceled || project.isDisposed) return
                    try {
                        Thread.sleep(schrittMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                    if (indicator.isCanceled) return
                    when (val a = TestBrainUebernahme.frageAb(code)) {
                        is TestBrainUebernahme.Abfrage.Token -> token = a.token
                        TestBrainUebernahme.Abfrage.Warten -> Unit
                        TestBrainUebernahme.Abfrage.Langsamer -> schrittMs += 5_000
                        is TestBrainUebernahme.Abfrage.Fehler -> {
                            notify(project, "The sign-in was not completed (${a.grund}). Nothing was changed.", NotificationType.WARNING)
                            return
                        }
                    }
                }
                if (token == null) {
                    notify(project, "The sign-in code expired before it was approved. Nothing was changed.", NotificationType.WARNING)
                    return
                }

                indicator.text = "Moving your test Brain into your account"
                val state = CachlySettings.getInstance().state
                val apiBase = state.apiUrl.trimEnd('/').ifBlank { "https://api.cachly.dev" }
                when (val ausgang = TestBrainUebernahme.uebernimm(apiBase, token, testKey)) {
                    is TestBrainUebernahme.Ausgang.Fehlschlag ->
                        notify(project, TestBrainUebernahme.fehlerText(ausgang), NotificationType.WARNING)
                    is TestBrainUebernahme.Ausgang.Erfolg -> {
                        val e = ausgang.ergebnis
                        TestBrainUebernahme.speichereNeuenSchluessel(state, testKey, e)
                        // Der alte Test-Schluessel ist widerrufen: der neue muss sofort
                        // im Passwortspeicher liegen, nicht erst beim naechsten Speicherlauf.
                        CachlySettings.getInstance().schluesselSichern()
                        val bestaetigt = TestBrainUebernahme.brainImKonto(apiBase, e.apiKey, e.instanceId)
                        notify(
                            project,
                            TestBrainUebernahme.erfolgsText(e, bestaetigt),
                            if (bestaetigt) NotificationType.INFORMATION else NotificationType.WARNING,
                        )
                        // The Brain restarts with a new password (~20 s). The status
                        // bar asks again after that, not at its next 300 s tick.
                        if (e.brainRestarting) {
                            indicator.text = "Waiting for the Brain to restart"
                            try {
                                Thread.sleep(25_000)
                            } catch (_: InterruptedException) {
                                return
                            }
                        }
                        CachlyApiClient.fetchHealth()?.let { CachlyStatusBarWidget.showEverywhere(it) }
                    }
                }
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
