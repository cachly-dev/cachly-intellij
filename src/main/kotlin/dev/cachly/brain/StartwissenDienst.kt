package dev.cachly.brain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Startet den Start-Import (siehe [Startwissen]) beim Oeffnen eines Projekts und nach der
 * ersten Verbindung. Hoechstens drei Versuche je Instanz, nur mit Einwilligung, nie auf
 * dem EDT. Ohne Node/npx scheitert der Start still. Danach wird die Regeldatei gleich
 * aufgefrischt.
 */
object StartwissenDienst {

    private val laeuft = AtomicBoolean(false)

    fun anstossen(project: Project) {
        val s = CachlySettings.getInstance().state
        // Ohne Einwilligung geht keine Projektgeschichte ans Netz (GROW-035).
        if (!funktionJetzt() || s.apiKey.isBlank() || s.instanceId.isBlank()) return
        if (!Startwissen.faellig(s.startwissenVersuche[s.instanceId])) return
        if (!laeuft.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                lauf(project)
            } catch (_: Exception) {
                // still — der naechste Projektstart versucht es wieder
            } finally {
                laeuft.set(false)
            }
        }
    }

    private fun lauf(project: Project) {
        if (project.isDisposed) return
        val basis = project.basePath ?: return
        val s = CachlySettings.getInstance().state
        val instanz = s.instanceId

        val health = CachlyApiClient.fetchHealth()
        when (Startwissen.entscheide(health.status, health.lessons, health.showingSnapshot)) {
            Startwissen.Entscheidung.FERTIG -> {
                s.startwissenVersuche[instanz] = Startwissen.VERSUCHE
                return
            }
            Startwissen.Entscheidung.SPAETER -> return
            Startwissen.Entscheidung.STARTEN -> Unit
        }

        val befehl = Startwissen.befehl(basis, SystemInfo.isWindows) ?: return
        val pb = ProcessBuilder(befehl)
            .directory(File(basis))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.environment().putAll(Startwissen.umgebung(s.apiUrl.trimEnd('/'), s.apiKey, instanz))
        val prozess = try {
            pb.start()
        } catch (_: Exception) {
            return // kein npx auf diesem Rechner
        }
        s.startwissenVersuche[instanz] = (s.startwissenVersuche[instanz] ?: 0) + 1
        prozess.outputStream.close()
        if (!prozess.waitFor(Startwissen.FRIST_MINUTEN, TimeUnit.MINUTES)) prozess.destroyForcibly()
        if (!project.isDisposed) RegelwissenDienst.sofort(project)
    }
}
