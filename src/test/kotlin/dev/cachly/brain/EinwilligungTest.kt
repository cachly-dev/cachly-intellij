package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GROW-035: ZWEI Fragen in EINEM Dialog (Entscheidung Heinrich 15.10.2026).
 * Funktion ist vorausgewaehlt, Statistik nicht; ohne Antwort geht nichts raus;
 * Schliessen oder Abbrechen heisst "nein" auf beide.
 */
class EinwilligungTest {

    @Test
    fun `unanswered - nothing is allowed`() {
        val s = CachlySettings.State()
        assertFalse(s.einwilligungBeantwortet)
        assertFalse(darfFunktion(s))
        assertFalse(darfStatistik(s))
    }

    @Test
    fun `unanswered stays unanswered even if a flag is set by hand`() {
        // Ein Haekchen allein reicht nicht: erst die Antwort macht es gueltig.
        val s = CachlySettings.State(funktionErlaubt = true, statistikErlaubt = true)
        assertFalse(darfFunktion(s))
        assertFalse(darfStatistik(s))
    }

    @Test
    fun `function yes and statistics no - only the function is allowed`() {
        val s = CachlySettings.State()
        einwilligungBeantworten(s, bestaetigt = true, funktion = true, statistik = false)
        assertTrue(s.einwilligungBeantwortet)
        assertTrue(darfFunktion(s))
        assertFalse(darfStatistik(s))
    }

    @Test
    fun `both yes - both allowed`() {
        val s = CachlySettings.State()
        einwilligungBeantworten(s, bestaetigt = true, funktion = true, statistik = true)
        assertTrue(darfFunktion(s))
        assertTrue(darfStatistik(s))
    }

    @Test
    fun `statistics yes and function no - only statistics`() {
        val s = CachlySettings.State()
        einwilligungBeantworten(s, bestaetigt = true, funktion = false, statistik = true)
        assertFalse(darfFunktion(s))
        assertTrue(darfStatistik(s))
    }

    @Test
    fun `cancel or close - answered with no to both, whatever the boxes showed`() {
        val s = CachlySettings.State()
        // Der Dialog zeigte die Funktion vorausgewaehlt; Abbrechen darf das nicht uebernehmen.
        einwilligungBeantworten(s, bestaetigt = false, funktion = true, statistik = true)
        assertTrue(s.einwilligungBeantwortet) // die Frage kommt nicht wieder
        assertFalse(darfFunktion(s))
        assertFalse(darfStatistik(s))
    }

    // ── Sperre fuer ausdrueckliche Aktionen ────────────────────────────────────

    @Test
    fun `action - unanswered asks, a yes lets it send`() {
        val s = CachlySettings.State()
        var gefragt = 0
        val erlaubt = aktionErlaubt(
            s,
            fragen = { gefragt++; einwilligungBeantworten(s, true, true, false) },
            hinweis = { throw AssertionError("no hint after a yes") },
        )
        assertTrue(erlaubt)
        assertEquals(1, gefragt)
    }

    @Test
    fun `action - unanswered asks, cancel blocks and says where to switch it on`() {
        val s = CachlySettings.State()
        var hinweise = 0
        val erlaubt = aktionErlaubt(
            s,
            fragen = { einwilligungBeantworten(s, false, true, true) },
            hinweis = { hinweise++ },
        )
        assertFalse(erlaubt)
        assertEquals(1, hinweise)
    }

    @Test
    fun `action - a declined answer stays declined, the question is not asked again`() {
        val s = CachlySettings.State()
        einwilligungBeantworten(s, bestaetigt = false, funktion = true, statistik = false)
        var hinweise = 0
        val erlaubt = aktionErlaubt(
            s,
            fragen = { throw AssertionError("must not ask again") },
            hinweis = { hinweise++ },
        )
        assertFalse(erlaubt)
        assertEquals(1, hinweise)
    }

    @Test
    fun `action - another dialog is open and nobody answered yet, nothing is sent and no hint`() {
        val s = CachlySettings.State()
        val erlaubt = aktionErlaubt(
            s,
            fragen = { /* anderer Dialog offen: keine Antwort */ },
            hinweis = { throw AssertionError("no hint while the question is still open") },
        )
        assertFalse(erlaubt)
    }

    @Test
    fun `action - answered yes goes straight through`() {
        val s = CachlySettings.State()
        einwilligungBeantworten(s, true, true, false)
        assertTrue(aktionErlaubt(s, { throw AssertionError() }, { throw AssertionError() }))
    }
}
