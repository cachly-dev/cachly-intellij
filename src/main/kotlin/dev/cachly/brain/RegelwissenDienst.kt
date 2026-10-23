package dev.cachly.brain

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Haelt `.aiassistant/rules/cachly-wissen.md` aktuell (siehe [Regelwissen]): 5 s nach dem
 * Oeffnen des Projekts und 10 s nach einem Wechsel der Datei, je Projekt hoechstens alle
 * 3 Minuten. Gleicher Takt wie in VS Code.
 *
 * Laeuft auf einem eigenen Timer, nie auf dem EDT. Jeder Fehler ist still — die Regeldatei
 * darf den Editor nie aufhalten. Abschalten: Settings → Tools → Cachly Brain.
 */
object RegelwissenDienst {

    private const val START_MS = 5_000L
    private const val NACH_WECHSEL_MS = 10_000L
    private const val ABSTAND_MS = 3 * 60_000L

    private val takt = Timer("cachly-regelwissen", true)
    private val wartend = ConcurrentHashMap<Project, TimerTask>()
    private val zuletzt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var gemeldet = false

    /** Lauscht auf Dateiwechsel. Der erste Anstoss kommt aus [ProactiveRecallStartup.verbinden]. */
    fun install(project: Project) {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (event.newFile?.isInLocalFileSystem == true) planen(project, NACH_WECHSEL_MS)
                }
            },
        )
    }

    /** Beim Oeffnen des Projekts und nach einem Ja in der Einwilligungsfrage. */
    fun anstossen(project: Project) = planen(project, START_MS)

    private fun planen(project: Project, verzoegerung: Long) {
        // Ohne Einwilligung geht kein Dateipfad ans Netz (GROW-035).
        if (!CachlySettings.getInstance().state.regelwissen || !funktionJetzt()) return
        val aufgabe = object : TimerTask() {
            override fun run() {
                wartend.remove(project, this)
                aktualisieren(project)
            }
        }
        wartend.put(project, aufgabe)?.cancel()
        takt.schedule(aufgabe, verzoegerung)
    }

    private fun aktualisieren(project: Project) {
        if (project.isDisposed) return
        val basis = project.basePath ?: return
        val jetzt = System.currentTimeMillis()
        val vorher = zuletzt[basis]
        if (vorher != null && jetzt - vorher < ABSTAND_MS) return
        zuletzt[basis] = jetzt

        val offen = runCatching {
            val fem = FileEditorManager.getInstance(project)
            (fem.selectedFiles.toList() + fem.openFiles.toList())
                .filter { it.isInLocalFileSystem }
                .map { it.path }
        }.getOrDefault(emptyList())

        val antwort = CachlyApiClient.fetchRegelwissen(Regelwissen.relativePfade(basis, offen)) ?: return
        if (project.isDisposed) return

        val wurzel = File(basis)
        val aktion = Regelwissen.schreibe(wurzel, antwort.markdown)
        if (aktion != Regelwissen.Aktion.GESCHRIEBEN && aktion != Regelwissen.Aktion.ENTFERNT) return

        // Der AI Assistant liest ueber das VFS — ohne Auffrischen saehe er die Datei erst spaeter.
        runCatching { VfsUtil.markDirtyAndRefresh(true, false, false, File(wurzel, Regelwissen.PFAD)) }

        if (aktion == Regelwissen.Aktion.GESCHRIEBEN) {
            CachlyInstructionWriter.findGitRoot(wurzel)?.let { git ->
                val rel = File(wurzel, Regelwissen.PFAD).relativeTo(git).path.replace('\\', '/')
                Regelwissen.ergaenzeGitExclude(git, listOf(rel))
            }
            if (!gemeldet) {
                gemeldet = true
                CachlyApiClient.trackEvent("intellij_regelwissen_geschrieben")
            }
        }
    }
}
