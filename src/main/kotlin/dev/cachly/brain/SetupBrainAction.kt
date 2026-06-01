package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * "Set Up AI Files" — writes .mcp.json, CLAUDE.md, AGENTS.md,
 * .github/copilot-instructions.md and the CLS git hook into the project root,
 * so every AI tool the user has gets the full Brain protocol. Mirrors the VS
 * Code extension's setup flow.
 */
class SetupBrainAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath
        if (basePath == null) {
            Messages.showErrorDialog(project, "No project directory found.", "Cachly Brain")
            return
        }
        val settings = CachlySettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.instanceId.isBlank()) {
            Messages.showWarningDialog(
                project,
                "Set your API key and instance ID first:\nSettings → Tools → Cachly Brain.",
                "Cachly Brain — Not Configured",
            )
            return
        }

        object : Task.Backgroundable(project, "Setting up Cachly Brain files", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = CachlyInstructionWriter.writeAll(
                    basePath,
                    settings.apiUrl.trimEnd('/'),
                    settings.apiKey,
                    settings.instanceId,
                )
                // Refresh VFS so the new files show up in the Project view immediately.
                LocalFileSystem.getInstance().refresh(true)
                javax.swing.SwingUtilities.invokeLater {
                    val written = if (result.written.isEmpty()) "(none)" else result.written.joinToString("\n• ", "• ")
                    val skipped = if (result.skipped.isEmpty()) "" else
                        "\n\nAlready present / skipped:\n• " + result.skipped.joinToString("\n• ")
                    Messages.showInfoMessage(
                        project,
                        "Your AI tools now have persistent memory.\n\nWritten:\n$written$skipped\n\n" +
                            "Restart your AI tool (Claude Code / Copilot / Cursor) to activate.",
                        "Cachly Brain — Setup Complete",
                    )
                }
            }
        }.queue()
    }
}
