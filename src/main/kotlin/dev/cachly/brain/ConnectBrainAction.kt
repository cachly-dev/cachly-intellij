package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * "Connect Brain" — the menu entry for getting a key.
 *
 * Exists so the onboarding is reachable on purpose and not only as a
 * once-per-project-open side effect: someone who dismissed the prompt, changed
 * machines, or cleared their settings needs a way back in that does not involve
 * finding the dashboard by hand.
 */
class ConnectBrainAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CachlyOnboarding.connectNow(project)
    }
}
