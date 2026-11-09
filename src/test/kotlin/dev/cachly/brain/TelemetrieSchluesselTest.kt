package dev.cachly.brain

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Der Schluessel reist bei der Statistik nur im Kopf `Authorization`,
 * nie im Rumpf. Ohne Schluessel wird kein Kopf geschickt.
 */
class TelemetrieSchluesselTest {

    private val id = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"
    private val schluessel = "cky_live_abcdefgh12345678"

    private lateinit var server: HttpServer
    private val rumpfe = CopyOnWriteArrayList<String>()
    private val koepfe = CopyOnWriteArrayList<String?>()

    private fun einrichten(key: String) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            rumpfe += ex.requestBody.bufferedReader().readText()
            koepfe += ex.requestHeaders.getFirst("Authorization")
            val bytes = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val basis = "http://127.0.0.1:${server.address.port}"
        val einstellungen = CachlySettings().also {
            it.ablage = SpeicherAblage()
            it.loadState(CachlySettings.State(apiUrl = basis, apiKey = key, instanceId = id))
            it.initializeComponent()
        }
        einwilligungBeantworten(einstellungen.state, bestaetigt = true, funktion = true, statistik = true)
        CachlySettings.testInstanz = einstellungen
    }

    @Before
    fun vorher() {
        rumpfe.clear()
        koepfe.clear()
    }

    @After
    fun stop() {
        CachlySettings.testInstanz = null
        server.stop(0)
    }

    @Test
    fun `with key - header carries it, body has no api_key and no key`() {
        einrichten(schluessel)

        CachlyApiClient.trackEvent("session_start")

        assertEquals("Bearer $schluessel", koepfe.single())
        val rumpf = rumpfe.single()
        assertFalse(rumpf, rumpf.contains("api_key"))
        assertFalse(rumpf, rumpf.contains(schluessel))
        assertTrue(rumpf, rumpf.contains("\"event\":\"session_start\""))
    }

    @Test
    fun `without key - no Authorization header is sent`() {
        einrichten("")

        CachlyApiClient.trackEvent("session_start")

        assertNull(koepfe.single())
        assertFalse(rumpfe.single().contains("api_key"))
    }
}
