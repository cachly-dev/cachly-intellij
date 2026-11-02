package dev.cachly.brain

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Bietet "Set Up AI Files" beim Oeffnen eines Projekts an, wenn dort keine
 * Datei der KI sagt, zuerst das Brain zu fragen.
 *
 * Warum (02.11.2026): 0.9.5 zeigt den Knopf im Hinweis nach dem Verbinden. Wer
 * vorher verbunden war, sieht diesen Hinweis nie wieder — das sind genau die
 * Konten, die haengen blieben (19.10.: 35 von 49 neuen Konten ueber JetBrains,
 * keines rief je smart_recall). Hoechstens einmal je Projekt und IDE-Sitzung;
 * "Not for this project" schaltet es fuer das Projekt ab, der leise Modus
 * ueberall.
 */
object KiDateienAngebot {

    private const val GROUP = "Cachly Brain Ambient"
    private const val AUS_SCHLUESSEL = "cachly.kiDateienAngebotAus"

    /**
     * Dieselben Dateien, die [CachlyInstructionWriter.writeAll] schreibt. Traegt
     * eine davon den cachly-Block, ist das Projekt eingerichtet — auch wenn es
     * die MCP-Einrichtung (npx) war und nicht dieses Plugin.
     */
    internal val REGEL_DATEIEN = listOf(
        "CLAUDE.md",
        "AGENTS.md",
        ".github/copilot-instructions.md",
        ".windsurfrules",
        ".clinerules",
        ".aiassistant/rules/cachly.md",
        ".junie/guidelines.md",
    )

    private val angeboten = mutableSetOf<String>()

    /** Wahr, wenn eine Regeldatei im Projekt den cachly-Block traegt. */
    internal fun hatKiDateien(wurzel: File): Boolean = REGEL_DATEIEN.any { pfad ->
        runCatching {
            val f = File(wurzel, pfad)
            f.isFile && f.readText().contains(CachlyInstructionWriter.BRAIN_START)
        }.getOrDefault(false)
    }

    /** Darf nicht auf dem EDT laufen (liest Dateien). */
    fun anbieten(project: Project) {
        if (project.isDisposed) return
        val s = CachlySettings.getInstance().state
        if (s.apiKey.isBlank() || s.instanceId.isBlank() || s.quietMode) return
        val basis = project.basePath ?: return
        if (PropertiesComponent.getInstance(project).getBoolean(AUS_SCHLUESSEL, false)) return
        if (hatKiDateien(File(basis))) return
        synchronized(angeboten) { if (!angeboten.add(basis)) return }

        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        group.createNotification(
            "Cachly Brain — your AI doesn't know about your Brain here",
            "No file in this project tells Junie or Claude Code to check your Brain first. " +
                "One click writes them.",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimpleExpiring(CachlyOnboarding.SET_UP_AI_FILES) {
            SetupBrainAction.einrichten(project)
        }).addAction(NotificationAction.createSimpleExpiring("Not for this project") {
            PropertiesComponent.getInstance(project).setValue(AUS_SCHLUESSEL, true)
        }).notify(project)
    }
}
