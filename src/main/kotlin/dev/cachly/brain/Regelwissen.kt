package dev.cachly.brain

import java.io.File

/**
 * Projektwissen in die Regeldatei von JetBrains AI Assistant schreiben (Abhakliste 5.2, 23.10.2026).
 *
 * Warum: 5 Konten hatten cachly beim AI Assistant eingetragen, 0 Abrufe. Der Chat ruft
 * smart_recall nie von sich aus auf. Eine Projektregel in `.aiassistant/rules/` laedt er
 * dagegen in jeden Chat (Regeltyp "Always" ist die Vorgabe). Der Server liefert den
 * fertigen, gerahmten Block (POST /instances/:id/regelwissen); dieses Modul legt ihn ab.
 *
 * Gleiche Regeln wie in VS Code (sdk/vscode/src/lib/regelwissen.ts):
 *  - Eine Datei ohne unsere Kennung ([MARKE]) ist fremd und bleibt unberuehrt.
 *  - Leerer Block = unsere Datei entfernen (kein veraltetes Wissen stehen lassen).
 *  - Der Pfad kommt in `.git/info/exclude`: lokal, ohne die `.gitignore` des Projekts
 *    anzufassen, damit niemand den Block versehentlich eincheckt.
 *
 * Nur reine Funktionen mit Dateisystem, darum ohne IDE testbar. Den Anstoss gibt
 * [RegelwissenDienst].
 */
object Regelwissen {

    /** Die Kennung, die der Server in die erste Zeile schreibt (regelwissen_handler.go). */
    const val MARKE = "Written by cachly"

    /** Neben `.aiassistant/rules/cachly.md` (Anleitung, 0.9.0) — eigene Datei, eigener Zweck. */
    const val PFAD = ".aiassistant/rules/cachly-wissen.md"

    enum class Aktion { GESCHRIEBEN, UNVERAENDERT, ENTFERNT, FREMD, FEHLER }

    fun istUnsere(inhalt: String): Boolean = inhalt.contains(MARKE)

    /** Legt den Block in [wurzel]/[PFAD] ab oder entfernt unsere Datei bei leerem Block. Wirft nie. */
    fun schreibe(wurzel: File, markdown: String): Aktion = runCatching {
        val datei = File(wurzel, PFAD)
        val block = markdown.trim()
        val vorhanden = if (datei.exists()) datei.readText() else null
        if (vorhanden != null && !istUnsere(vorhanden)) return@runCatching Aktion.FREMD
        if (block.isEmpty()) {
            if (vorhanden == null) return@runCatching Aktion.UNVERAENDERT
            datei.delete()
            return@runCatching Aktion.ENTFERNT
        }
        val neu = "$block\n"
        if (vorhanden != null && ohneZeitstempel(vorhanden) == ohneZeitstempel(neu)) {
            return@runCatching Aktion.UNVERAENDERT
        }
        datei.parentFile?.mkdirs()
        datei.writeText(neu)
        Aktion.GESCHRIEBEN
    }.getOrDefault(Aktion.FEHLER)

    /** Nur der Zeitstempel in der ersten Zeile aendert sich bei jedem Abruf — der zaehlt nicht als Aenderung. */
    private fun ohneZeitstempel(s: String): String =
        s.replace(Regex("""<!-- Written by cachly [^ ]+\."""), "<!-- Written by cachly.")

    /**
     * Ordner, in dem `info/exclude` gilt. Bei `.git` als Ordner ist es dieser. Bei einer
     * `.git`-Datei (Arbeitsbaum, Submodul) steht darin `gitdir: <Pfad>`. Git liest die
     * Ausnahmen aus dem gemeinsamen Ordner (`commondir`), nicht aus dem des Arbeitsbaums.
     * 03.11.2026, jetbrains-regelwissen-silent-project-file: vorher passierte dort nichts,
     * und die erzeugte Datei tauchte als ungetrackt auf. Null, wenn nichts davon passt.
     */
    internal fun gitOrdnerFuerExclude(wurzel: File): File? {
        val git = File(wurzel, ".git")
        if (git.isDirectory) return git
        if (!git.isFile) return null
        val zeile = git.readLines().firstOrNull { it.startsWith("gitdir:") } ?: return null
        val roh = File(zeile.removePrefix("gitdir:").trim())
        val gitdir = (if (roh.isAbsolute) roh else File(wurzel, roh.path)).canonicalFile
        if (!gitdir.isDirectory) return null
        val common = File(gitdir, "commondir")
        if (common.isFile) {
            val c = File(common.readText().trim())
            val ziel = (if (c.isAbsolute) c else File(gitdir, c.path)).canonicalFile
            if (ziel.isDirectory) return ziel
        }
        return gitdir
    }

    /**
     * Traegt [pfade] (relativ zu [wurzel], mit `/`) in `.git/info/exclude` ein, nur fehlende,
     * am Ende. Ohne Git passiert nichts; `.git`-Dateien werden aufgeloest. Gibt die neu eingetragenen Zeilen zurueck. Wirft nie.
     */
    fun ergaenzeGitExclude(wurzel: File, pfade: List<String> = listOf(PFAD)): List<String> = runCatching {
        val gitOrdner = gitOrdnerFuerExclude(wurzel) ?: return@runCatching emptyList<String>()
        val datei = File(gitOrdner, "info/exclude")
        val alt = if (datei.exists()) datei.readText() else ""
        val zeilen = alt.split(Regex("""\r?\n""")).map { it.trim() }.toSet()
        val fehlend = pfade.map { "/$it" }.filter { it !in zeilen }
        if (fehlend.isEmpty()) return@runCatching emptyList<String>()
        datei.parentFile?.mkdirs()
        val trenner = if (alt.isEmpty() || alt.endsWith("\n")) "" else "\n"
        datei.writeText("$alt$trenner# cachly: automatisch erzeugtes Projektwissen\n${fehlend.joinToString("\n")}\n")
        fehlend
    }.getOrDefault(emptyList())

    /**
     * Pfade der offenen Dateien relativ zum Projekt, mit `/`, hoechstens [max].
     * Dateien ausserhalb des Projekts fallen weg — ihr Pfad sagt dem Brain nichts.
     */
    fun relativePfade(projektPfad: String, dateiPfade: List<String>, max: Int = 10): List<String> {
        val basis = projektPfad.replace('\\', '/').trimEnd('/') + "/"
        return dateiPfade.asSequence()
            .map { it.replace('\\', '/') }
            .filter { it.startsWith(basis) }
            .map { it.removePrefix(basis) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(max)
            .toList()
    }
}
