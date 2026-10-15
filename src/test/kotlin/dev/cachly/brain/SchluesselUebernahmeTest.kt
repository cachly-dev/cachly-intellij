package dev.cachly.brain

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GROW-035: Der API-Schluessel verlaesst cachly-brain.xml und liegt im
 * Passwortspeicher der IDE. Bestandsschutz: wer bisher mit einem Schluessel in
 * der Datei arbeitete, verliert ihn nie.
 */
class SchluesselUebernahmeTest {

    private val alt = "cky_live_alterklartextwert1234"

    /** Wie die IDE beim Start: erst loadState (Datei), dann initializeComponent. */
    private fun start(ablage: SpeicherAblage, state: CachlySettings.State): CachlySettings =
        CachlySettings().also {
            it.ablage = ablage
            it.loadState(state)
            it.initializeComponent()
        }

    // ── Uebernahme des Altwerts ────────────────────────────────────────────────

    @Test
    fun `old plain-text value lands in the store, the old field is emptied, the key works`() {
        val ablage = SpeicherAblage()
        val einstellungen = start(ablage, CachlySettings.State(apiKeyAlt = alt, instanceId = "i1"))

        val s = einstellungen.state
        assertEquals(alt, ablage.wert)
        assertEquals("", s.apiKeyAlt)
        assertEquals(alt, s.apiKey)
        assertEquals("i1", s.instanceId) // das uebrige bleibt
    }

    @Test
    fun `read-back fails - the old field stays, the key still works this session`() {
        val ablage = SpeicherAblage().apply { lesenGibtNull = true }
        val einstellungen = start(ablage, CachlySettings.State(apiKeyAlt = alt))

        val s = einstellungen.state
        assertEquals("the old value must survive for the next start", alt, s.apiKeyAlt)
        assertEquals(alt, s.apiKey)
    }

    @Test
    fun `store cannot be written - the old field stays`() {
        val ablage = SpeicherAblage().apply { schreibenWirft = true }
        val s = start(ablage, CachlySettings.State(apiKeyAlt = alt)).state
        assertEquals(alt, s.apiKeyAlt)
        assertEquals(alt, s.apiKey)
    }

    @Test
    fun `store holds a different value after the write - the old field stays`() {
        // Der Speicher ist vorher leer, gibt nach dem Schreiben aber etwas anderes zurueck.
        val ablage = object : Ablage {
            var geschrieben = false
            override fun lesen(): String? = if (geschrieben) "etwas anderes" else null
            override fun schreiben(wert: String?) { geschrieben = true }
        }
        val einstellungen = CachlySettings().also {
            it.ablage = ablage
            it.loadState(CachlySettings.State(apiKeyAlt = alt))
            it.initializeComponent()
        }
        assertEquals(alt, einstellungen.state.apiKeyAlt)
    }

    @Test
    fun `store forgets after restart - the old field is not emptied`() {
        val ablage = SpeicherAblage().apply { dauerhaftEin = false }
        val s = start(ablage, CachlySettings.State(apiKeyAlt = alt)).state
        assertEquals("a memory-only store would lose the key at restart", alt, s.apiKeyAlt)
        assertEquals(alt, s.apiKey)
    }

    @Test
    fun `the move is repeated at the next start after it failed`() {
        val ablage = SpeicherAblage().apply { lesenGibtNull = true }
        val ersterStart = start(ablage, CachlySettings.State(apiKeyAlt = alt))
        assertEquals(alt, ersterStart.state.apiKeyAlt)

        // Naechster Start: der Speicher geht wieder.
        ablage.lesenGibtNull = false
        val zweiterStart = start(ablage, CachlySettings.State(apiKeyAlt = ersterStart.state.apiKeyAlt))
        assertEquals("", zweiterStart.state.apiKeyAlt)
        assertEquals(alt, ablage.wert)
    }

    // ── Rueckfall in die Datei: kein Schluessel geht verloren ──────────────────
    // Gegenprobe: in schluesselSichern die Zeilen mit `apiKeyAlt = schluessel`
    // entfernen -> die ersten drei Faelle rot. In schluesselLaden das Lesen vor dem
    // Schreiben entfernen -> der vierte rot (der echte Schluessel wuerde ueberschrieben).

    @Test
    fun `a new key the store cannot take stays in the settings file`() {
        val ablage = SpeicherAblage()
        val einstellungen = start(ablage, CachlySettings.State())
        ablage.schreibenWirft = true
        einstellungen.state.apiKey = "cky_trial_neu12345678"
        assertEquals("cky_trial_neu12345678", einstellungen.state.apiKeyAlt)
    }

    @Test
    fun `memory-only store - a new key also stays in the settings file`() {
        val ablage = SpeicherAblage().apply { dauerhaftEin = false }
        val einstellungen = start(ablage, CachlySettings.State())
        einstellungen.state.apiKey = "cky_trial_neu12345678"
        val s = einstellungen.state
        assertEquals("cky_trial_neu12345678", ablage.wert)
        assertEquals("cky_trial_neu12345678", s.apiKeyAlt)
    }

    @Test
    fun `locked store - a key fetched later stays in the settings file`() {
        val ablage = SpeicherAblage("cky_live_echter").apply { lesenWirft = true }
        val einstellungen = start(ablage, CachlySettings.State())
        einstellungen.state.apiKey = "cky_trial_neu12345678"
        assertEquals("cky_trial_neu12345678", einstellungen.state.apiKeyAlt)
        assertEquals("cky_live_echter", ablage.wert)
    }

    @Test
    fun `next start after a locked store - the real key in the store wins over the file`() {
        val ablage = SpeicherAblage("cky_live_echter")
        val s = start(ablage, CachlySettings.State(apiKeyAlt = "cky_trial_neu12345678")).state
        assertEquals("cky_live_echter", s.apiKey)
        assertEquals("cky_live_echter", ablage.wert)
        assertTrue("the real key must never be overwritten", ablage.schreibungen.isEmpty())
        assertEquals("", s.apiKeyAlt)
    }

    // ── Normalbetrieb ──────────────────────────────────────────────────────────

    @Test
    fun `no old value - the key comes from the store`() {
        val ablage = SpeicherAblage("cky_live_ausdemspeicher")
        val s = start(ablage, CachlySettings.State()).state
        assertEquals("cky_live_ausdemspeicher", s.apiKey)
        assertTrue("nothing written when nothing changed", ablage.schreibungen.isEmpty())
    }

    @Test
    fun `new key is written once, not on every getState`() {
        val ablage = SpeicherAblage()
        val einstellungen = start(ablage, CachlySettings.State())

        einstellungen.state.apiKey = "cky_trial_neuer12345678"
        einstellungen.state
        einstellungen.state
        einstellungen.schluesselSichern()

        assertEquals(listOf<String?>("cky_trial_neuer12345678"), ablage.schreibungen)
    }

    @Test
    fun `clearing the key in the settings clears the store`() {
        val ablage = SpeicherAblage("cky_live_x")
        val einstellungen = start(ablage, CachlySettings.State())
        einstellungen.uebernehmen(CachlySettings.State(apiKey = ""))
        assertNull(ablage.wert)
        assertEquals("", einstellungen.state.apiKey)
    }

    @Test
    fun `reloading the file does not wipe the key`() {
        val ablage = SpeicherAblage("cky_live_x")
        val einstellungen = start(ablage, CachlySettings.State())
        // Die Datei kennt den Schluessel nicht mehr; ein erneutes Einlesen bringt ihn leer mit.
        einstellungen.loadState(CachlySettings.State(instanceId = "neu"))
        assertEquals("cky_live_x", einstellungen.state.apiKey)
        assertEquals("cky_live_x", ablage.wert)
    }

    // ── Speicher gesperrt: nie als "leer" lesen ────────────────────────────────

    @Test
    fun `locked store - the stored key is never overwritten by a key fetched later`() {
        val ablage = SpeicherAblage("cky_live_echter").apply { lesenWirft = true }
        val einstellungen = start(ablage, CachlySettings.State())
        assertEquals("", einstellungen.state.apiKey)

        // Das Onboarding sieht "kein Schluessel" und holt einen Test-Schluessel.
        einstellungen.state.apiKey = "cky_trial_neu12345678"
        einstellungen.schluesselSichern()
        einstellungen.state

        assertTrue("nothing may be written to a store we could not read", ablage.schreibungen.isEmpty())
        assertEquals("cky_live_echter", ablage.wert)
    }

    @Test
    fun `locked store - an empty settings field does not delete the stored key`() {
        val ablage = SpeicherAblage("cky_live_echter").apply { lesenWirft = true }
        val einstellungen = start(ablage, CachlySettings.State())
        einstellungen.uebernehmen(CachlySettings.State(apiKey = ""))
        assertTrue(ablage.schreibungen.isEmpty())
        assertEquals("cky_live_echter", ablage.wert)
    }

    @Test
    fun `locked store - a key typed into the settings is written`() {
        val ablage = SpeicherAblage().apply { lesenWirft = true }
        val einstellungen = start(ablage, CachlySettings.State())
        ablage.lesenWirft = false
        einstellungen.uebernehmen(CachlySettings.State(apiKey = "cky_live_eingetippt"))
        assertEquals("cky_live_eingetippt", ablage.wert)
    }

    @Test
    fun `a failing write does not throw into the caller`() {
        val ablage = SpeicherAblage()
        val einstellungen = start(ablage, CachlySettings.State())
        ablage.schreibenWirft = true
        einstellungen.state.apiKey = "cky_live_x"
        einstellungen.schluesselSichern() // darf nicht werfen
        assertEquals("cky_live_x", einstellungen.state.apiKey)
    }

    // ── Die Datei: der Schluessel steht nicht mehr darin ───────────────────────

    /** Wie die IDE speichert: Werte, die dem Standard entsprechen, werden nicht geschrieben. */
    private fun alsXml(state: CachlySettings.State): String =
        JDOMUtil.write(XmlSerializer.serialize(state, SkipDefaultValuesSerializationFilters()))

    private fun ausXml(xml: String): CachlySettings.State =
        XmlSerializer.deserialize(JDOMUtil.load(xml), CachlySettings.State::class.java)

    @Test
    fun `the settings file no longer contains the key`() {
        val xml = alsXml(CachlySettings.State(apiKey = "cky_live_geheim9999", instanceId = "i1"))
        assertFalse(xml, xml.contains("cky_live_geheim9999"))
        assertTrue(xml, xml.contains("i1")) // das uebrige wird weiter gespeichert
    }

    @Test
    fun `an old settings file is read into the old field, not into the key`() {
        val s = ausXml("""<CachlySettings><option name="apiKey" value="$alt" /><option name="instanceId" value="i1" /></CachlySettings>""")
        assertEquals(alt, s.apiKeyAlt)
        assertEquals("", s.apiKey)
        assertEquals("i1", s.instanceId)
    }

    @Test
    fun `after the move the next save writes no key at all`() {
        val ablage = SpeicherAblage()
        val s = start(
            ablage,
            ausXml("""<CachlySettings><option name="apiKey" value="$alt" /></CachlySettings>"""),
        ).state
        val xml = alsXml(s)
        assertFalse(xml, xml.contains(alt))
        assertFalse(xml, xml.contains("apiKey"))
    }

    @Test
    fun `the answers survive the file`() {
        val s = CachlySettings.State()
        einwilligungBeantworten(s, true, true, false)
        val zurueck = ausXml(alsXml(s))
        assertTrue(zurueck.einwilligungBeantwortet)
        assertTrue(darfFunktion(zurueck))
        assertFalse(darfStatistik(zurueck))
    }
}
