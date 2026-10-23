package dev.cachly.brain

import dev.cachly.brain.Regelwissen.Aktion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regelwissen (23.10.2026): 5 Konten mit cachly beim AI Assistant, 0 Abrufe. Die Regeldatei
 * bringt das Wissen in jeden Chat, ohne dass der Assistent ein Werkzeug aufrufen muss.
 * Gleiche Regeln wie sdk/vscode/src/lib/regelwissen.test.ts.
 */
class RegelwissenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun block(zeit: String) =
        "<!-- Written by cachly $zeit. Regenerated automatically; edits are overwritten. -->\n" +
            "# Project experience (cachly)\n\n<cachly-memory>\n- deploy:x — Erst bauen.\n</cachly-memory>\n"

    private fun datei(wurzel: File) = File(wurzel, Regelwissen.PFAD)

    @Test
    fun `schreibt den Block und laesst ihn bei neuem Zeitstempel stehen`() {
        val w = tmp.newFolder("p")
        assertEquals(Aktion.GESCHRIEBEN, Regelwissen.schreibe(w, block("2026-10-23T10:00:00Z")))
        val erst = datei(w).readText()
        assertTrue(erst.startsWith("<!-- Written by cachly 2026-10-23T10:00:00Z."))
        assertTrue(erst.endsWith("</cachly-memory>\n"))

        assertEquals(Aktion.UNVERAENDERT, Regelwissen.schreibe(w, block("2026-10-23T10:03:00Z")))
        assertEquals("nur der Zeitstempel geaendert: Datei bleibt, wie sie war", erst, datei(w).readText())
    }

    @Test
    fun `neuer Inhalt wird geschrieben`() {
        val w = tmp.newFolder("p")
        Regelwissen.schreibe(w, block("2026-10-23T10:00:00Z"))
        val neu = block("2026-10-23T10:03:00Z").replace("Erst bauen.", "Erst testen.")
        assertEquals(Aktion.GESCHRIEBEN, Regelwissen.schreibe(w, neu))
        assertTrue(datei(w).readText().contains("Erst testen."))
    }

    @Test
    fun `fremde Datei bleibt unberuehrt, auch bei leerem Block`() {
        val w = tmp.newFolder("p")
        datei(w).parentFile.mkdirs()
        datei(w).writeText("Meine eigene Regel\n")
        assertEquals(Aktion.FREMD, Regelwissen.schreibe(w, block("2026-10-23T10:00:00Z")))
        assertEquals(Aktion.FREMD, Regelwissen.schreibe(w, ""))
        assertEquals("Meine eigene Regel\n", datei(w).readText())
    }

    @Test
    fun `leerer Block entfernt nur unsere Datei`() {
        val w = tmp.newFolder("p")
        assertEquals(Aktion.UNVERAENDERT, Regelwissen.schreibe(w, "  \n"))
        assertFalse(datei(w).exists())
        Regelwissen.schreibe(w, block("2026-10-23T10:00:00Z"))
        assertEquals(Aktion.ENTFERNT, Regelwissen.schreibe(w, ""))
        assertFalse("veraltetes Wissen darf nicht stehen bleiben", datei(w).exists())
        assertTrue("die Anleitung daneben (cachly.md) ist nicht betroffen", datei(w).parentFile.exists())
    }

    @Test
    fun `git exclude bekommt den Pfad genau einmal`() {
        val w = tmp.newFolder("p")
        val exclude = File(w, ".git/info/exclude")
        exclude.parentFile.mkdirs()
        exclude.writeText("# eigene Zeile ohne Zeilenende")
        assertEquals(listOf("/${Regelwissen.PFAD}"), Regelwissen.ergaenzeGitExclude(w))
        assertEquals(emptyList<String>(), Regelwissen.ergaenzeGitExclude(w))
        val inhalt = exclude.readText()
        assertTrue(inhalt.startsWith("# eigene Zeile ohne Zeilenende\n"))
        assertEquals(1, inhalt.lines().count { it == "/${Regelwissen.PFAD}" })
    }

    @Test
    fun `ohne git-Ordner kein exclude`() {
        val w = tmp.newFolder("p")
        File(w, ".git").writeText("gitdir: ../anderswo\n")
        assertEquals(emptyList<String>(), Regelwissen.ergaenzeGitExclude(w))
        assertEquals(emptyList<String>(), Regelwissen.ergaenzeGitExclude(tmp.newFolder("ohne")))
    }

    @Test
    fun `relative Pfade nur aus dem Projekt, hoechstens zehn`() {
        val offen = listOf(
            "C:\\work\\shop\\web\\lib\\stripe.ts",
            "C:/work/shop/web/lib/stripe.ts",
            "C:/anderswo/notiz.md",
            "C:/work/shop",
        ) + (1..20).map { "C:/work/shop/src/f$it.kt" }
        val rel = Regelwissen.relativePfade("C:\\work\\shop\\", offen)
        assertEquals("web/lib/stripe.ts", rel.first())
        assertEquals(10, rel.size)
        assertTrue(rel.none { it.contains("anderswo") || it.contains('\\') })
    }

    /** Die Kennung entsteht im Server. Weicht sie ab, haelt das Plugin die eigene Datei fuer fremd. */
    @Test
    fun `Kennung gleich der des Servers`() {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "api/internal/handler/regelwissen_handler.go").exists()) d = d.parentFile
        val quelle = d?.let { File(it, "api/internal/handler/regelwissen_handler.go") }
            ?: throw AssertionError("api/internal/handler/regelwissen_handler.go nicht gefunden (Start: ${File("").absolutePath}).")
        assertTrue(quelle.readText().contains("<!-- ${Regelwissen.MARKE} %s."))
    }
}
