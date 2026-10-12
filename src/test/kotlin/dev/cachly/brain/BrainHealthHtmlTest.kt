package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.plaf.basic.BasicHTML

/**
 * Das Fenster "Brain Health" zeigte am 12.10.2026 (IntelliJ 2025.3) den
 * Quelltext statt der Uebersicht. Ursache: `"""<html>...""".trimIndent()` mit
 * eingesetzten mehrzeiligen Bausteinen. Die gemeinsame Einrueckung war 0, die
 * 12 Leerzeichen vor `<html>` blieben, und Swing erkennt HTML nur, wenn der
 * Text mit `<html>` beginnt. Das gilt in jeder IDE-Fassung, nicht nur in 253.
 *
 * Geprueft wird der Text, den das JLabel bekommt, mit derselben Regel, die
 * Swing selbst anwendet (BasicHTML.isHTMLString).
 */
class BrainHealthHtmlTest {

    private fun assertWirdAlsHtmlGerendert(text: String) {
        assertTrue("Text beginnt nicht mit <html>: ${text.take(40).replace("\n", "\\n")}", BasicHTML.isHTMLString(text))
        assertTrue("Text endet nicht mit </html>", text.endsWith("</html>"))
    }

    private val gesund = BrainHealth(status = "healthy", tier = "dev", lessons = 7, contexts = 3)

    @Test
    fun `solo brain renders as html`() {
        assertWirdAlsHtmlGerendert(brainSummaryHtml(gesund))
    }

    @Test
    fun `team brain with two authors renders as html`() {
        assertWirdAlsHtmlGerendert(brainSummaryHtml(gesund.copy(teamAuthors = listOf("anna", "ben"))))
    }

    @Test
    fun `value estimate block renders as html`() {
        val ins = InsightsResponse(minutesSaved = 120.0, dollarsSaved = 150.0, ttfrP50Sec = 3600.0, reusePct = 12.5)
        assertWirdAlsHtmlGerendert(brainSummaryHtml(gesund.copy(insights = ins)))
        assertWirdAlsHtmlGerendert(
            brainSummaryHtml(gesund.copy(insights = ins, teamAuthors = listOf("anna", "ben", "cem"))),
        )
    }

    @Test
    fun `limited and unlimited tiers render as html`() {
        assertWirdAlsHtmlGerendert(brainSummaryHtml(gesund.copy(recallLimit = 100, totalRecalls = 100)))
        assertWirdAlsHtmlGerendert(brainSummaryHtml(gesund.copy(recallLimit = -1, totalRecalls = 5)))
    }

    @Test
    fun `an unreachable brain with defaults renders as html`() {
        assertWirdAlsHtmlGerendert(brainSummaryHtml(BrainHealth()))
    }

    @Test
    fun `summary keeps its content`() {
        val text = brainSummaryHtml(gesund.copy(teamAuthors = listOf("anna", "ben")))
        assertTrue(text.contains("<h2>Brain Overview</h2>"))
        assertTrue(text.contains("Lessons Learned:"))
        assertTrue(text.contains("<h2>👥 Team Brain (2 contributors)</h2>"))
    }

    @Test
    fun `a tag character in a team name is shown, not interpreted`() {
        val text = brainSummaryHtml(gesund.copy(teamAuthors = listOf("a<b", "c&d")))
        assertTrue(text.contains("<code>a&lt;b</code>"))
        assertTrue(text.contains("<code>c&amp;d</code>"))
    }

    @Test
    fun `htmlText starts with html even when a part has no indent`() {
        // Das ist der Fehler von vorher im Kleinen: ein Baustein mit einer
        // Zeile ohne Einrueckung. Bei trimIndent bleibt dann die ganze
        // Einrueckung stehen; htmlText trimmt den Rumpf erst und umhuellt ihn danach.
        val mehrzeilig = "erste Zeile\nzweite Zeile ohne Einrueckung"
        val text = htmlText(
            """
                <p>vorn</p>
                $mehrzeilig
            """,
        )
        assertWirdAlsHtmlGerendert(text)
        assertTrue(text.contains("zweite Zeile ohne Einrueckung"))
    }

    @Test
    fun `the old construction fails the same check`() {
        // Belegt, dass die Pruefung den Fehler faengt: so wurde der Text vor 0.8.6 gebaut.
        val mehrzeilig = "erste Zeile\nzweite Zeile ohne Einrueckung"
        val alt = """
            <html>
            <h2>Brain Overview</h2>
            $mehrzeilig
            </html>
        """.trimIndent()
        assertFalse(BasicHTML.isHTMLString(alt))
        assertEquals(' ', alt[0])
    }
}
