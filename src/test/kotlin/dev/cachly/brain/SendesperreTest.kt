package dev.cachly.brain

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GROW-035: Kein Datenversand ohne beantwortete Frage. Jeder Netzweg von
 * CachlyApiClient wird gegen einen lokalen Server geprueft; gezaehlt wird, was
 * dort wirklich ankommt.
 */
class SendesperreTest {

    private val id = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"

    private lateinit var server: HttpServer
    private lateinit var basis: String
    private val pfade = CopyOnWriteArrayList<String>()
    private val rumpfe = CopyOnWriteArrayList<String>()

    private lateinit var einstellungen: CachlySettings

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            pfade += ex.requestURI.path
            rumpfe += ex.requestBody.bufferedReader().readText()
            val antwort = when {
                ex.requestURI.path.endsWith("/auth/instant-trial") ->
                    201 to """{"api_key":"cky_trial_abcdefgh12345678","instance_id":"$id","trial":true}"""
                ex.requestURI.path.endsWith("/instances/auto") -> 201 to """{"instance_id":"$id","created":true}"""
                ex.requestURI.path.endsWith("/auth/device") ->
                    200 to """{"device_code":"dc","user_code":"ABCD-EFGH"}"""
                ex.requestURI.path.endsWith("/briefing") -> 200 to """{"risk_level":"low","warnings":[]}"""
                else -> 200 to """{"ok":true}"""
            }
            val bytes = antwort.second.toByteArray()
            ex.sendResponseHeaders(antwort.first, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        basis = "http://127.0.0.1:${server.address.port}"

        einstellungen = CachlySettings().also {
            it.ablage = SpeicherAblage()
            it.loadState(CachlySettings.State(apiUrl = basis, apiKey = "cky_live_abcdefgh12345678", instanceId = id))
            it.initializeComponent()
        }
        CachlySettings.testInstanz = einstellungen
    }

    @After
    fun stop() {
        CachlySettings.testInstanz = null
        server.stop(0)
    }

    private fun antworten(funktion: Boolean, statistik: Boolean) =
        einwilligungBeantworten(einstellungen.state, bestaetigt = true, funktion = funktion, statistik = statistik)

    // ── Ohne Antwort geht nichts raus ──────────────────────────────────────────

    @Test
    fun `unanswered - not a single request leaves, on any path`() {
        val key = einstellungen.state.apiKey
        assertEquals("not_configured", CachlyApiClient.fetchHealth().status)   // sonst "unreachable"
        einstellungen.state.instanceId = "" // damit ensureInstanceId einen Aufruf machen WUERDE

        assertNull(CachlyApiClient.fetchBriefing("src/geheim/Datei.kt"))     // httpPost: Pfad der Datei
        assertNull(CachlyApiClient.fetchInsights(basis, key))                // httpGet
        assertNull(CachlyApiClient.requestInstantTrial())                    // httpPostAnon: Test-Schluessel holen
        assertNull(CachlyApiClient.requestDeviceCode())                      // httpPostAnon
        assertNull(CachlyApiClient.pollDeviceToken("dc"))                    // httpPostAnon
        assertEquals(false, CachlyApiClient.ensureInstanceId())              // httpPostAccepting
        CachlyApiClient.trackEvent("session_start")                          // Statistik

        assertEquals("these requests arrived: $pfade", emptyList<String>(), pfade.toList())
    }

    @Test
    fun `declined - nothing leaves, statistics included`() {
        einwilligungBeantworten(einstellungen.state, bestaetigt = false, funktion = true, statistik = true)

        assertNull(CachlyApiClient.fetchBriefing("a.kt"))
        assertNull(CachlyApiClient.fetchInsights(basis, "k"))
        assertNull(CachlyApiClient.requestInstantTrial())
        CachlyApiClient.trackEvent("session_start")

        assertTrue(pfade.toString(), pfade.isEmpty())
    }

    // ── Funktion ja, Statistik nein ────────────────────────────────────────────

    @Test
    fun `function yes statistics no - briefing goes out, the event does not`() {
        antworten(funktion = true, statistik = false)

        val briefing = CachlyApiClient.fetchBriefing("src/app/main.ts")
        assertNotNull(briefing)
        assertEquals(listOf("/api/v1/instances/$id/briefing"), pfade.toList())
        assertTrue(rumpfe.single().contains("src/app/main.ts"))

        CachlyApiClient.trackEvent("session_start")
        assertEquals("the event must stay home", 1, pfade.size)
    }

    @Test
    fun `function yes - trial key and instance id may be fetched`() {
        antworten(funktion = true, statistik = false)

        assertNotNull(CachlyApiClient.requestInstantTrial())
        einstellungen.state.instanceId = ""
        assertTrue(CachlyApiClient.ensureInstanceId())
        assertEquals(id, einstellungen.state.instanceId)
        assertNotNull(CachlyApiClient.fetchInsights(basis, "k"))
        assertEquals(3, pfade.size)
    }

    // ── Statistik ja ───────────────────────────────────────────────────────────

    @Test
    fun `statistics yes - the event goes out with its name and no file name`() {
        antworten(funktion = true, statistik = true)

        CachlyApiClient.trackEvent("session_start")

        assertEquals(listOf("/api/v1/telemetry/mcp"), pfade.toList())
        val rumpf = rumpfe.single()
        assertTrue(rumpf, rumpf.contains("\"event\":\"session_start\""))
        assertTrue(rumpf, rumpf.contains("\"editor\":\"intellij\""))
    }

    @Test
    fun `statistics are their own question - yes there and no to the function sends the event but no briefing`() {
        antworten(funktion = false, statistik = true)

        assertNull(CachlyApiClient.fetchBriefing("a.kt"))
        assertTrue(pfade.isEmpty())

        CachlyApiClient.trackEvent("session_start")
        assertEquals(listOf("/api/v1/telemetry/mcp"), pfade.toList())
    }

    @Test
    fun `revoking in the settings stops the next request`() {
        antworten(funktion = true, statistik = true)
        assertNotNull(CachlyApiClient.fetchBriefing("a.kt"))

        antworten(funktion = false, statistik = false)
        assertNull(CachlyApiClient.fetchBriefing("b.kt"))
        CachlyApiClient.trackEvent("session_start")

        assertEquals(1, pfade.size)
    }
}
