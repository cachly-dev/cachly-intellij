package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Tools → Connect JetBrains AI Assistant. Derselbe Weg wie der Hinweis nach
 * dem Verbinden (AiAssistantAnbindung), fuer alle, die ihn weggeklickt haben.
 */
class ConnectAiAssistantAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val s = CachlySettings.getInstance().state
        if (s.apiKey.isBlank() || s.instanceId.isBlank()) {
            Messages.showWarningDialog(
                project,
                "Connect your Brain first: Tools → Connect Brain.",
                "Cachly Brain — Not Connected",
            )
            return
        }
        AiAssistantAnbindung.verbinden(project)
    }
}
