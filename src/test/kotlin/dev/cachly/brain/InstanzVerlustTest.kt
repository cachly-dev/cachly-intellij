package dev.cachly.brain

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Die gespeicherte Instanz antwortet 404 (der Sofort-Test-Abraeumer hat das leere Test-Brain
 * geloescht), der Schluessel wird angenommen. Gegen einen lokalen Server: gezaehlt wird, was dort
 * wirklich ankommt.
 */
class InstanzVerlustTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val alteId = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"
    private val neueId = "11111111-2222-3333-4444-555555555555"
    private val testKey = "cky_trial_abcdefgh12345678"
    private val kontoKey = "cky_live_abcdefgh12345678"

    private lateinit var server: HttpServer
    private lateinit var basis: String
    private val aufrufe = CopyOnWriteArrayList<String>()

    @Volatile private var instanzCode = 404
    @Volatile private var autoCode = 201
    @Volatile private var autoBody = """{"instance_id":"$neueId","status":"provisioning","created":true}"""

    private lateinit var einstellungen: CachlySettings
    private lateinit var heim: File

    @Before
    fun start() {
        InstanzVerlust.zuruecksetzen()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            aufrufe += "${ex.requestMethod} ${ex.requestURI.path}"
            val (code, body) = if (ex.requestURI.path.endsWith("/instances/auto")) autoCode to autoBody
            else instanzCode to """{"error":"x"}"""
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        basis = "http://127.0.0.1:${server.address.port}"
        heim = tmp.newFolder("heim")
    }

    @After
    fun stop() {
        CachlySettings.testInstanz = null
        server.stop(0)
    }

    private fun einrichten(key: String) {
        einstellungen = CachlySettings().also {
            it.ablage = SpeicherAblage()
            it.loadState(CachlySettings.State(apiUrl = basis, apiKey = key, instanceId = alteId))
            it.initializeComponent()
        }
        einwilligungBeantworten(einstellungen.state, bestaetigt = true, funktion = true, statistik = false)
        CachlySettings.testInstanz = einstellungen
    }

    private fun credentials(inhalt: String) {
        File(heim, ".cachly").mkdirs()
        File(heim, ".cachly/credentials.json").writeText(inhalt)
    }

    private fun autoAufrufe() = aufrufe.count { it.endsWith("/instances/auto") }

    @Test
    fun `404 with trial key - one auto call, new id saved, message`() {
        einrichten(testKey)
        val a = InstanzVerlust.behandle(heim)

        assertEquals(InstanzVerlust.Ausgang.Neu(alteId, neueId, angelegt = true, testBrain = true), a)
        assertEquals(neueId, einstellungen.state.instanceId)
        assertEquals(1, autoAufrufe())
        assertEquals(
            "Your trial Brain was removed because it stayed empty and unused for 14 days. " +
                "cachly created a new, empty Brain for you. Nothing was lost: the old one had no saved lessons.",
            InstanzVerlust.text(a),
        )
    }

    @Test
    fun `404 with registered key - nothing is created, only a notice`() {
        einrichten(kontoKey)
        val a = InstanzVerlust.behandle(heim)

        assertEquals(InstanzVerlust.Ausgang.KontoBrainFehlt(alteId), a)
        assertEquals("The Brain $alteId no longer exists in your account.", InstanzVerlust.text(a))
        assertEquals(0, autoAufrufe())
        assertEquals(alteId, einstellungen.state.instanceId)
        // dieselbe Sitzung: nicht noch einmal melden
        assertEquals(InstanzVerlust.Ausgang.Nichts, InstanzVerlust.behandle(heim))
    }

    @Test
    fun `registered key - an explicit Reconnect may create or pick a Brain`() {
        einrichten(kontoKey)
        val a = InstanzVerlust.behandle(heim, ausdruecklich = true)

        assertEquals(InstanzVerlust.Ausgang.Neu(alteId, neueId, angelegt = true, testBrain = false), a)
        assertEquals(neueId, einstellungen.state.instanceId)
        assertEquals(1, autoAufrufe())
    }

    @Test
    fun `auto fails - message names the next step, and no second try in the session`() {
        einrichten(testKey)
        autoCode = 403
        autoBody = """{"error":"x","reason":"sign_in_required"}"""

        val a = InstanzVerlust.behandle(heim)
        assertEquals(InstanzVerlust.Ausgang.Scheitert(403, "sign_in_required", testBrain = true), a)
        assertTrue(InstanzVerlust.text(a).contains("sign-in"))
        assertEquals(alteId, einstellungen.state.instanceId)

        assertEquals(InstanzVerlust.Ausgang.Nichts, InstanzVerlust.behandle(heim))
        assertEquals(InstanzVerlust.Ausgang.Nichts, InstanzVerlust.behandle(heim, bekannterStatus = 404))
        assertEquals("exactly one auto call", 1, autoAufrufe())
    }

    @Test
    fun `credentials json - same old id is replaced, key stays`() {
        einrichten(testKey)
        credentials("""{"apiKey":"$testKey","instanceId":"$alteId"}""")

        InstanzVerlust.behandle(heim)

        val text = File(heim, ".cachly/credentials.json").readText()
        assertTrue(text, text.contains(neueId))
        assertFalse(text, text.contains(alteId))
        assertTrue(text, text.contains(testKey))
    }

    @Test
    fun `credentials json - a foreign id stays untouched`() {
        einrichten(testKey)
        val fremd = """{"apiKey":"cky_live_fremderschluessel99","instanceId":"99999999-0000-0000-0000-000000000000"}"""
        credentials(fremd)

        InstanzVerlust.behandle(heim)

        assertEquals(neueId, einstellungen.state.instanceId) // Einstellungen wurden gesetzt
        assertEquals(fremd, File(heim, ".cachly/credentials.json").readText())
    }

    @Test
    fun `401 or 403 on the instance - old behaviour, no auto call`() {
        for (code in listOf(401, 403)) {
            instanzCode = code
            einrichten(testKey)
            assertEquals(InstanzVerlust.Ausgang.Nichts, InstanzVerlust.behandle(heim))
            assertEquals(alteId, einstellungen.state.instanceId)
        }
        assertEquals(0, autoAufrufe())
    }

    @Test
    fun `instance answers 200 - nothing to do`() {
        instanzCode = 200
        einrichten(testKey)
        assertEquals(InstanzVerlust.Ausgang.Nichts, InstanzVerlust.behandle(heim))
        assertEquals(0, autoAufrufe())
    }

    @Test
    fun `without consent nothing is sent`() {
        einrichten(testKey)
        einwilligungBeantworten(einstellungen.state, bestaetigt = false, funktion = true, statistik = false)
        assertEquals(InstanzVerlust.Ausgang.Nichts, InstanzVerlust.behandle(heim))
        assertTrue(aufrufe.toString(), aufrufe.isEmpty())
    }

    @Test
    fun `fetchHealth reports not_found on 404`() {
        einrichten(kontoKey)
        assertEquals("not_found", CachlyApiClient.fetchHealth().status)
    }

    @Test
    fun `doctor line names reason and next step`() {
        assertTrue(InstanzVerlust.doctorGrund(testKey).contains("Connect Brain creates a new one"))
        assertTrue(InstanzVerlust.doctorGrund(kontoKey).contains("no longer exists in your account"))
    }
}
