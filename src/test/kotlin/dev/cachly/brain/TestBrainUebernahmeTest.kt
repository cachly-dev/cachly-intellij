package dev.cachly.brain

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * "Link account" moves the trial Brain into the account (checklist 1.9,
 * 2026-10-14). Until 0.8.7 the button ran the API's device flow, which ends in
 * the account's own empty Brain; the trial lessons stayed behind.
 *
 * The network calls run against a local server that answers like Keycloak
 * and like POST /auth/trial/claim (api/internal/handler/trial_claim_handler.go).
 */
class TestBrainUebernahmeTest {

    private val testKey = "cky_trial_abcdefgh12345678"
    private val id = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"

    private class Anfrage(val methode: String, val pfad: String, val koepfe: Map<String, String>, val rumpf: String)

    private lateinit var server: HttpServer
    private lateinit var basis: String
    private val anfragen = mutableListOf<Anfrage>()
    /** Pfad -> (Status, Rumpf) */
    private val antworten = mutableMapOf<String, Pair<Int, String>>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val koepfe = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            anfragen += Anfrage(ex.requestMethod, ex.requestURI.path, koepfe, ex.requestBody.bufferedReader().readText())
            val (status, rumpf) = antworten[ex.requestURI.path] ?: (404 to "{}")
            val bytes = rumpf.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        basis = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    // ── reading ────────────────────────────────────────────────────────────────

    @Test
    fun `only cky_trial_ keys count as trial keys`() {
        assertTrue(TestBrainUebernahme.istTestSchluessel(testKey))
        assertTrue(TestBrainUebernahme.istTestSchluessel(" $testKey "))
        assertFalse(TestBrainUebernahme.istTestSchluessel("cky_live_abcdefgh12345678"))
        assertFalse(TestBrainUebernahme.istTestSchluessel("cky_trial_kurz"))
        assertFalse(TestBrainUebernahme.istTestSchluessel(""))
        assertFalse(TestBrainUebernahme.istTestSchluessel(null))
    }

    @Test
    fun `device code - complete address, sane interval and lifetime`() {
        val c = TestBrainUebernahme.leseGeraetecode(
            """{"device_code":"dc","user_code":"ABCD-EFGH","verification_uri":"https://a/device",""" +
                """"verification_uri_complete":"https://a/device?user_code=ABCD-EFGH","interval":0,"expires_in":5}""",
            "https://a/realms/cachly/protocol/openid-connect", 1_000,
        )!!
        assertEquals("https://a/device?user_code=ABCD-EFGH", c.adresse)
        assertEquals(1_000L, c.intervallMs) // at least 1 s
        assertEquals(1_000L + 60_000L, c.frist) // at least 60 s
        assertNull(TestBrainUebernahme.leseGeraetecode("""{"user_code":"x"}""", "b", 0))
        assertEquals(
            "https://a/realms/cachly/device",
            TestBrainUebernahme.leseGeraetecode("""{"device_code":"d","user_code":"u"}""", "https://a/realms/cachly/protocol/openid-connect", 0)!!.adresse,
        )
    }

    @Test
    fun `token poll - Keycloak answers pending with 400, the body decides`() {
        assertEquals(TestBrainUebernahme.Abfrage.Warten, TestBrainUebernahme.leseAbfrage(400, """{"error":"authorization_pending"}"""))
        assertEquals(TestBrainUebernahme.Abfrage.Langsamer, TestBrainUebernahme.leseAbfrage(400, """{"error":"slow_down"}"""))
        assertEquals(TestBrainUebernahme.Abfrage.Token("t"), TestBrainUebernahme.leseAbfrage(200, """{"access_token":"t"}"""))
        assertEquals(TestBrainUebernahme.Abfrage.Fehler("access_denied"), TestBrainUebernahme.leseAbfrage(400, """{"error":"access_denied"}"""))
        assertEquals(TestBrainUebernahme.Abfrage.Warten, TestBrainUebernahme.leseAbfrage(503, null))
        assertEquals(TestBrainUebernahme.Abfrage.Warten, TestBrainUebernahme.leseAbfrage(-1, null))
    }

    @Test
    fun `claim answer - success and the server's reason`() {
        val ok = TestBrainUebernahme.leseUebernahme(
            200, """{"api_key":"cky_live_neu","instance_id":"$id","account_created":true,"revoked_keys":2,"brain_restarting":true}""",
        ) as TestBrainUebernahme.Ausgang.Erfolg
        assertEquals(TestBrainUebernahme.Ergebnis("cky_live_neu", id, true, 2, true), ok.ergebnis)

        val nein = TestBrainUebernahme.leseUebernahme(409, """{"error":"You already have a free Brain.","reason":"instance_limit"}""")
            as TestBrainUebernahme.Ausgang.Fehlschlag
        assertEquals("instance_limit", nein.reason)
        assertEquals("You already have a free Brain. Nothing was changed.", TestBrainUebernahme.fehlerText(nein))

        val leer = TestBrainUebernahme.leseUebernahme(200, """{"ok":true}""") as TestBrainUebernahme.Ausgang.Fehlschlag
        assertEquals("unknown", leer.reason)
    }

    @Test
    fun `texts - known reasons in plain words, success names the revoked key`() {
        val f = { r: String -> TestBrainUebernahme.fehlerText(TestBrainUebernahme.Ausgang.Fehlschlag(400, r, "x")) }
        assertTrue(f("trial_key_invalid").contains("already claimed"))
        assertTrue(f("sign_in_required").contains("sign-in was not accepted"))
        val text = TestBrainUebernahme.erfolgsText(TestBrainUebernahme.Ergebnis("k", id, false, 1, true), true)
        assertTrue(text.startsWith("Your test Brain is now part of your account. Lessons kept."))
        assertTrue(text.contains("(1 key revoked)"))
        assertTrue(text.contains("about 20 seconds"))
        assertFalse(TestBrainUebernahme.erfolgsText(TestBrainUebernahme.Ergebnis("k", id, false, 1, false), false).contains("Lessons kept"))
    }

    // ── network ────────────────────────────────────────────────────────────────

    @Test
    fun `sign-in starts at Keycloak with client cachly-cli, not at the API`() {
        antworten["/realm/auth/device"] = 200 to """{"device_code":"dc","user_code":"ABCD","verification_uri_complete":"$basis/device?c=ABCD","interval":5,"expires_in":600}"""
        val code = TestBrainUebernahme.starteAnmeldung("$basis/realm", 0)!!
        assertEquals("dc", code.deviceCode)
        val a = anfragen.single()
        assertEquals("POST", a.methode)
        assertTrue(a.rumpf.contains("client_id=cachly-cli"))
        assertTrue(a.koepfe["content-type"]!!.startsWith("application/x-www-form-urlencoded"))

        antworten["/realm/token"] = 400 to """{"error":"authorization_pending"}"""
        assertEquals(TestBrainUebernahme.Abfrage.Warten, TestBrainUebernahme.frageAb(code, "$basis/realm"))
        assertTrue(anfragen.last().rumpf.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        antworten["/realm/token"] = 200 to """{"access_token":"kc-token"}"""
        assertEquals(TestBrainUebernahme.Abfrage.Token("kc-token"), TestBrainUebernahme.frageAb(code, "$basis/realm"))
    }

    @Test
    fun `claim sends sign-in AND trial key, then the new key reaches the Brain`() {
        antworten["/auth/trial/claim"] = 200 to """{"api_key":"cky_live_neu","instance_id":"$id","revoked_keys":1,"brain_restarting":true}"""
        antworten["/api/v1/instances/$id"] = 200 to """{"id":"$id","status":"running"}"""

        val aus = TestBrainUebernahme.uebernimm(basis, "kc-token", testKey) as TestBrainUebernahme.Ausgang.Erfolg
        val a = anfragen.single()
        assertEquals("Bearer kc-token", a.koepfe["authorization"])
        assertEquals(testKey, a.koepfe["x-trial-key"])
        assertEquals("""{"client":"jetbrains"}""", a.rumpf)

        assertTrue(TestBrainUebernahme.brainImKonto(basis, aus.ergebnis.apiKey, id))
        assertEquals("Bearer cky_live_neu", anfragen.last().koepfe["authorization"])
        assertFalse(TestBrainUebernahme.brainImKonto(basis, "cky_live_neu", "andere-id"))
    }

    @Test
    fun `claim refused - the reason comes through, nothing else is called`() {
        antworten["/auth/trial/claim"] = 409 to """{"error":"Your account already has a free Brain.","reason":"instance_limit"}"""
        val aus = TestBrainUebernahme.uebernimm(basis, "kc-token", testKey) as TestBrainUebernahme.Ausgang.Fehlschlag
        assertEquals(409, aus.status)
        assertEquals("instance_limit", aus.reason)
    }

    @Test
    fun `an API key is not a sign-in - no request at all`() {
        val aus = TestBrainUebernahme.uebernimm(basis, "cky_live_xyz", testKey) as TestBrainUebernahme.Ausgang.Fehlschlag
        assertEquals("sign_in_required", aus.reason)
        assertTrue(anfragen.isEmpty())
    }

    @Test
    fun `server down - a failure with a reason, never an exception`() {
        // Port 1: nobody listens there, the connection is refused.
        val aus = TestBrainUebernahme.uebernimm("http://127.0.0.1:1", "kc-token", testKey) as TestBrainUebernahme.Ausgang.Fehlschlag
        assertEquals(-1, aus.status)
        assertTrue(TestBrainUebernahme.fehlerText(aus).endsWith("Nothing was changed."))
    }

    // ── storing the new key ────────────────────────────────────────────────────

    @Test
    fun `new key replaces the trial key in settings and in credentials_json`() {
        val heim = Files.createTempDirectory("cachly-uebernahme").toFile()
        try {
            CachlyInstructionWriter.legeSchluesselImHeimAb(testKey, "trial-instanz", heim)
            val state = CachlySettings.State(apiKey = testKey, instanceId = "trial-instanz", trialEndsAt = "2026-10-28")
            val e = TestBrainUebernahme.Ergebnis("cky_live_neu", id, false, 1, true)

            TestBrainUebernahme.speichereNeuenSchluessel(state, testKey, e, heim)

            assertEquals("cky_live_neu", state.apiKey)
            assertEquals(id, state.instanceId)
            assertEquals("", state.trialEndsAt)
            assertEquals("cky_live_neu", CachlyInstructionWriter.leseHeimSchluessel(heim))
            assertTrue(File(heim, ".cachly/credentials.json").readText().contains(id))
        } finally {
            heim.deleteRecursively()
        }
    }

    @Test
    fun `a different key in credentials_json stays - it belongs to another setup`() {
        val heim = Files.createTempDirectory("cachly-uebernahme").toFile()
        try {
            CachlyInstructionWriter.legeSchluesselImHeimAb("cky_live_anderer", "x", heim)
            val state = CachlySettings.State(apiKey = testKey)
            TestBrainUebernahme.speichereNeuenSchluessel(state, testKey, TestBrainUebernahme.Ergebnis("cky_live_neu", id, false, 1, true), heim)
            assertEquals("cky_live_neu", state.apiKey)
            assertEquals("cky_live_anderer", CachlyInstructionWriter.leseHeimSchluessel(heim))
        } finally {
            heim.deleteRecursively()
        }
    }
}
