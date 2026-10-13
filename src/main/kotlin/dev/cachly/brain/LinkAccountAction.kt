package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools → Link Account. Moves the trial Brain into the user's cachly account
 * and keeps its lessons (CachlyOnboarding.linkAccount). Until 0.8.7 this was
 * only a button in the trial notification, gone once the balloon closed.
 */
class LinkAccountAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CachlyOnboarding.linkAccount(project)
    }
}
