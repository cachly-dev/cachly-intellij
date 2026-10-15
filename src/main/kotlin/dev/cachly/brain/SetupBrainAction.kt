package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import dev.cachly.brain.CachlyInstructionWriter.HeimAblage
import java.io.File

/**
 * "Set Up AI Files" — writes .mcp.json, .junie/mcp/mcp.json, CLAUDE.md, AGENTS.md,
 * .github/copilot-instructions.md and the CLS git hook into the project root,
 * so every AI tool the user has gets the full Brain protocol. Mirrors the VS
 * Code extension's setup flow.
 *
 * Der API-Schluessel kommt dabei NIE ins Projekt, sondern nach
 * ~/.cachly/credentials.json (Warum: CachlyInstructionWriter.legeSchluesselImHeimAb).
 * Liegt dort schon ein anderer Schluessel, fragt diese Aktion, welcher gelten soll.
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
        if (settings.apiKey.isBlank()) {
            Messages.showWarningDialog(
                project,
                "Set your API key and instance ID first:\nSettings → Tools → Cachly Brain.",
                "Cachly Brain — Not Configured",
            )
            return
        }

        // Die Dateien entstehen lokal. Nur das Nachholen der Instanz-ID geht ans Netz,
        // und das nur mit Einwilligung (GROW-035).
        if (settings.instanceId.isBlank() && !EinwilligungFrage.fuerAktion(project)) return

        object : Task.Backgroundable(project, "Setting up Cachly Brain files", false) {
            override fun run(indicator: ProgressIndicator) {
                // A key without an instance id: fetch it instead of refusing —
                // see CachlyApiClient.ensureInstanceId.
                if (!CachlyApiClient.ensureInstanceId()) {
                    javax.swing.SwingUtilities.invokeLater {
                        Messages.showWarningDialog(
                            project,
                            "Your key is saved, but no Brain instance could be assigned to it.\n" +
                                "Run Tools → Run Brain Doctor, or set the instance ID in Settings → Tools → Cachly Brain.",
                            "Cachly Brain — No Instance",
                        )
                    }
                    return
                }
                val apiUrl = settings.apiUrl.trimEnd('/')
                var result = CachlyInstructionWriter.writeAll(basePath, apiUrl, settings.apiKey, settings.instanceId)

                // Ein anderer Schluessel liegt im Heimordner: der Mensch entscheidet, welcher gelten soll.
                // Wie auch immer er sich entscheidet — in die Projektdateien kommt kein Schluessel.
                var ersetzt = false
                if (result.heim == HeimAblage.KONFLIKT) {
                    var wahl = Messages.NO
                    ApplicationManager.getApplication().invokeAndWait {
                        wahl = Messages.showYesNoDialog(
                            project,
                            SchluesselWahl.FRAGE,
                            SchluesselWahl.TITEL,
                            SchluesselWahl.ERSETZEN,
                            SchluesselWahl.BEHALTEN,
                            Messages.getWarningIcon(),
                        )
                    }
                    if (wahl == Messages.YES) {
                        val neu = CachlyInstructionWriter.legeSchluesselImHeimAb(
                            settings.apiKey, settings.instanceId, File(System.getProperty("user.home")), ersetzen = true,
                        )
                        if (neu == HeimAblage.GESCHRIEBEN || neu == HeimAblage.UNVERAENDERT) {
                            ersetzt = true
                            // Zweiter Lauf: jetzt liegt der Schluessel im Heimordner, Altbestand im Projekt darf raus.
                            result = CachlyInstructionWriter.writeAll(basePath, apiUrl, settings.apiKey, settings.instanceId)
                        }
                    }
                }
                // Refresh VFS so the new files show up in the Project view immediately.
                LocalFileSystem.getInstance().refresh(true)
                val schluesselText = SchluesselWahl.bericht(result, ersetzt)
                javax.swing.SwingUtilities.invokeLater {
                    val written = if (result.written.isEmpty()) "(none)" else result.written.joinToString("\n• ", "• ")
                    val skipped = if (result.skipped.isEmpty()) "" else
                        "\n\nAlready present / skipped:\n• " + result.skipped.joinToString("\n• ")
                    Messages.showInfoMessage(
                        project,
                        "Your AI tools now have persistent memory.\n\nWritten:\n$written$skipped\n\n" +
                            schluesselText +
                            "Restart your AI tool (Claude Code / Junie / Copilot / Cursor) to activate.\n" +
                            "JetBrains AI Assistant does not read these files — use Tools → Connect JetBrains AI Assistant.",
                        "Cachly Brain — Setup Complete",
                    )
                }
            }
        }.queue()
    }
}

/**
 * Die Texte zum Schluessel in "Set Up AI Files": die Frage bei einem anderen gespeicherten
 * Schluessel und der Bericht danach. Eigenes Objekt, damit sie ohne IDE testbar sind.
 */
object SchluesselWahl {
    const val TITEL = "Cachly Brain — Which API key should be used?"
    const val ERSETZEN = "Use this key for all projects (replaces the stored one)"
    const val BEHALTEN = "Keep the stored key"
    val FRAGE = """
        ~/.cachly/credentials.json already holds a different API key (maybe from another account).

        The key from this IDE is NOT written into your project files either way, because files like .mcp.json end up in git.

        "$ERSETZEN": the cachly MCP server uses this IDE's key in every project.
        "$BEHALTEN": the server keeps using the stored key. If that belongs to another account, your Brain stays out of reach for it.
    """.trimIndent()

    /** Der Absatz zum Schluessel im Abschlussdialog; leer, wenn es nichts zu sagen gibt. */
    fun bericht(result: CachlyInstructionWriter.Result, ersetzt: Boolean): String {
        val sb = StringBuilder()
        if (ersetzt) {
            sb.append("Your API key replaced the one stored in ~/.cachly/credentials.json. The cachly MCP server uses it in every project; your project files hold no key.\n")
        } else when (result.heim) {
            HeimAblage.GESCHRIEBEN ->
                sb.append("Your API key is stored in ~/.cachly/credentials.json, not in your project.\n")
            HeimAblage.KONFLIKT ->
                sb.append("You kept the key stored in ~/.cachly/credentials.json. Your project files hold no key, so the cachly MCP server uses that one.\n")
            HeimAblage.FEHLER ->
                sb.append("Could not store your API key in ~/.cachly/credentials.json. Nothing was written to your project; set CACHLY_JWT in your environment instead.\n")
            HeimAblage.UNVERAENDERT -> Unit
        }
        if (result.bereinigt.isNotEmpty()) {
            sb.append("Removed the API key an older version had written into:\n• " + result.bereinigt.joinToString("\n• ") + "\n")
        }
        if (result.nochImProjekt.isNotEmpty()) {
            sb.append("These files still hold an API key from an older version and were left untouched:\n• " +
                result.nochImProjekt.joinToString("\n• ") +
                "\nRemove the key by hand, or run \"Set Up AI Files\" again and replace the stored key.\n")
        }
        if (sb.isNotEmpty()) sb.append("\n")
        return sb.toString()
    }
}
