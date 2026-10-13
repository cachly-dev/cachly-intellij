package dev.cachly.brain

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Moves the trial Brain into the user's own cachly account ("Link account").
 *
 * WHY (checklist item 1.9, 2026-10-14)
 * ------------------------------------
 * Until 0.8.7 "Link account" ran the API's device flow (/auth/device). That
 * flow ends with a key for the account, and the plugin then cleared the
 * instance id, so firstContact fetched the account's own Brain: a NEW, empty
 * one. The trial lessons stayed behind in the trial Brain. The MCP server
 * (`claim`, sdk/mcp/src/uebernahme.ts) and VS Code ("Link Account") already
 * move the Brain; JetBrains was the last client without it.
 *
 * The same chain as the MCP server:
 *   1. Sign-in: Keycloak device flow with client cachly-cli, directly at
 *      auth.cachly.dev. NOT /auth/device of the API: that one ends with an
 *      API key, and an API key is not a sign-in for the claim.
 *   2. Ownership: the trial key, as header X-Trial-Key.
 *   3. POST /auth/trial/claim with both. The server moves the Brain, revokes
 *      every trial key and returns a key from the account
 *      (api/internal/service/test_uebernahme.go).
 *   4. The text says "lessons kept" only after GET /api/v1/instances/{id}
 *      answers with the new key.
 *
 * The trial key comes only from this plugin's settings (application level),
 * never from a project file: a foreign repository could otherwise push its
 * own trial Brain, with its lessons, into the user's account.
 *
 * Network calls take their base addresses as parameters, so the tests run
 * them against a local server (TestBrainUebernahmeTest).
 */
object TestBrainUebernahme {

    const val KEYCLOAK_BASIS = "https://auth.cachly.dev/realms/cachly/protocol/openid-connect"
    const val CLI_CLIENT = "cachly-cli"
    const val TEST_KEY_KOPF = "X-Trial-Key"

    /** The server waits up to 60 s for the Brain to confirm its new password and gives itself 120 s. */
    private const val UEBERNAHME_FRIST_MS = 150_000

    /** A trial key starts with cky_trial_ (POST /auth/instant-trial). */
    fun istTestSchluessel(wert: String?): Boolean =
        wert != null && Regex("^cky_trial_[A-Za-z0-9]{8,}$").matches(wert.trim())

    data class Geraetecode(
        val deviceCode: String,
        val userCode: String,
        /** The approval page with the code filled in. */
        val adresse: String,
        val intervallMs: Long,
        /** Epoch ms after which the code is no longer valid. */
        val frist: Long,
    )

    sealed class Abfrage {
        data class Token(val token: String) : Abfrage()
        object Warten : Abfrage()
        object Langsamer : Abfrage()
        data class Fehler(val grund: String) : Abfrage()
    }

    data class Ergebnis(
        val apiKey: String,
        val instanceId: String,
        val accountCreated: Boolean,
        val revokedKeys: Int,
        val brainRestarting: Boolean,
    )

    sealed class Ausgang {
        data class Erfolg(val ergebnis: Ergebnis) : Ausgang()
        /** Nothing was changed on the server. [reason] is the contract field of /auth/trial/claim. */
        data class Fehlschlag(val status: Int, val reason: String, val message: String) : Ausgang()
    }

    // ── Reading answers (pure, tested without network) ─────────────────────────

    private fun JsonObject.text(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.zahl(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun objekt(json: String?): JsonObject? =
        runCatching { JsonParser.parseString(json ?: return null).asJsonObject }.getOrNull()

    internal fun leseGeraetecode(json: String?, basis: String, jetzt: Long): Geraetecode? {
        val o = objekt(json) ?: return null
        val device = o.text("device_code") ?: return null
        val user = o.text("user_code") ?: return null
        val adresse = o.text("verification_uri_complete") ?: o.text("verification_uri")
            ?: "${basis.removeSuffix("/protocol/openid-connect")}/device"
        val intervall = maxOf(1L, o.zahl("interval") ?: 5L)
        val laeuft = maxOf(60L, o.zahl("expires_in") ?: 600L)
        return Geraetecode(device, user, adresse, intervall * 1000L, jetzt + laeuft * 1000L)
    }

    /**
     * Keycloak answers a pending device code with HTTP 400 and
     * {"error":"authorization_pending"}. So the body decides, not the status;
     * only an unreadable answer or a 5xx means "keep waiting".
     */
    internal fun leseAbfrage(status: Int, json: String?): Abfrage {
        if (status >= 500 || status <= 0) return Abfrage.Warten
        val o = objekt(json) ?: return Abfrage.Fehler("HTTP $status")
        o.text("access_token")?.let { return Abfrage.Token(it) }
        return when (val fehler = o.text("error")) {
            "authorization_pending" -> Abfrage.Warten
            "slow_down" -> Abfrage.Langsamer
            null -> Abfrage.Fehler("HTTP $status")
            else -> Abfrage.Fehler(fehler)
        }
    }

    internal fun leseUebernahme(status: Int, json: String?): Ausgang {
        val o = objekt(json)
        val key = o?.text("api_key")
        val id = o?.text("instance_id")
        if (status !in 200..299 || key == null || id == null) {
            return Ausgang.Fehlschlag(
                status,
                o?.text("reason") ?: "unknown",
                o?.text("error") ?: if (status > 0) "HTTP $status" else "the server could not be reached",
            )
        }
        return Ausgang.Erfolg(
            Ergebnis(
                apiKey = key,
                instanceId = id,
                accountCreated = o.get("account_created")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                revokedKeys = o.zahl("revoked_keys")?.toInt() ?: 0,
                brainRestarting = o.get("brain_restarting")?.takeIf { it.isJsonPrimitive }?.asBoolean != false,
            ),
        )
    }

    /** The text for the user after a successful move — same wording as `claim` in the MCP server. */
    fun erfolgsText(e: Ergebnis, bestaetigt: Boolean): String {
        val zeilen = mutableListOf(
            if (bestaetigt) "Your test Brain is now part of your account. Lessons kept."
            else "Your test Brain was moved to your account, but the new key could not reach it yet. " +
                "Tools → Run Brain Doctor shows the state in a minute.",
        )
        zeilen += "Brain: ${e.instanceId}" +
            if (e.accountCreated) " (a new cachly account was created for your sign-in)" else ""
        zeilen += "The test key no longer works (${e.revokedKeys} key${if (e.revokedKeys == 1) "" else "s"} revoked). " +
            "The new key is stored in the plugin settings."
        if (e.brainRestarting) zeilen += "The Brain restarts once with a new password — about 20 seconds."
        return zeilen.joinToString("\n")
    }

    /** The text when it did not work. Nothing is changed then. */
    fun fehlerText(f: Ausgang.Fehlschlag): String = when (f.reason) {
        "trial_key_invalid" -> "This test key is unknown or was already claimed. Nothing was changed."
        "not_a_test_account" -> "This key belongs to a regular account, not to a test Brain. There is nothing to claim."
        "sign_in_required" -> "The sign-in was not accepted. Try Link Account again and finish the sign-in in the browser."
        "claim_unavailable" -> "Moving the Brain is not possible right now. Nothing was changed. Please try again later."
        else -> f.message + if (Regex("nothing", RegexOption.IGNORE_CASE).containsMatchIn(f.message)) "" else " Nothing was changed."
    }

    // ── Network ────────────────────────────────────────────────────────────────

    private class Antwort(val status: Int, val text: String?)

    /** One request; reads the body for every status (Keycloak and the claim answer errors with a body). */
    private fun sende(
        url: String,
        methode: String,
        koepfe: Map<String, String>,
        rumpf: String?,
        fristMs: Int,
    ): Antwort = try {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = methode
        conn.connectTimeout = 10_000
        conn.readTimeout = fristMs
        conn.setRequestProperty("User-Agent", CachlyApiClient.USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        koepfe.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (rumpf != null) {
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { it.write(rumpf) }
        }
        val status = conn.responseCode
        val strom = if (status in 200..299) conn.inputStream else conn.errorStream
        Antwort(status, strom?.bufferedReader()?.use { it.readText() })
    } catch (_: Exception) {
        Antwort(-1, null)
    }

    private fun formular(werte: Map<String, String>): String =
        werte.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    /** Starts the sign-in at Keycloak. null when it could not start. */
    fun starteAnmeldung(basis: String = KEYCLOAK_BASIS, jetzt: Long = System.currentTimeMillis()): Geraetecode? {
        val a = sende(
            "$basis/auth/device", "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            formular(mapOf("client_id" to CLI_CLIENT, "scope" to "openid")),
            10_000,
        )
        return if (a.status in 200..299) leseGeraetecode(a.text, basis, jetzt) else null
    }

    /** Asks once whether the sign-in was approved in the browser. */
    fun frageAb(code: Geraetecode, basis: String = KEYCLOAK_BASIS): Abfrage {
        val a = sende(
            "$basis/token", "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            formular(
                mapOf(
                    "client_id" to CLI_CLIENT,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to code.deviceCode,
                ),
            ),
            10_000,
        )
        return leseAbfrage(a.status, a.text)
    }

    /** POST /auth/trial/claim with the sign-in AND the trial key. */
    fun uebernimm(apiBase: String, anmeldeToken: String, testSchluessel: String): Ausgang {
        if (anmeldeToken.startsWith("cky_")) {
            // The server would refuse it anyway; this way the reason is clear here.
            return Ausgang.Fehlschlag(403, "sign_in_required", "An API key is not a sign-in.")
        }
        val a = sende(
            "${apiBase.trimEnd('/')}/auth/trial/claim", "POST",
            mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $anmeldeToken",
                TEST_KEY_KOPF to testSchluessel.trim(),
            ),
            Gson().toJson(mapOf("client" to "jetbrains")),
            UEBERNAHME_FRIST_MS,
        )
        return leseUebernahme(a.status, a.text)
    }

    /** Does the NEW key reach the Brain? Only then does the text say "lessons kept". */
    fun brainImKonto(apiBase: String, neuerSchluessel: String, instanzId: String): Boolean {
        val a = sende(
            "${apiBase.trimEnd('/')}/api/v1/instances/${URLEncoder.encode(instanzId, "UTF-8")}", "GET",
            mapOf("Authorization" to "Bearer $neuerSchluessel"),
            null,
            10_000,
        )
        return a.status == 200 && objekt(a.text)?.text("id") == instanzId
    }

    /**
     * Stores the new key where the trial key was. ~/.cachly/credentials.json is
     * replaced only when it held exactly this trial key: the trial key is
     * revoked now, and the MCP server and the hooks read that file. A
     * different key there belongs to someone's other setup and stays.
     */
    internal fun speichereNeuenSchluessel(
        state: CachlySettings.State,
        alterTestSchluessel: String,
        e: Ergebnis,
        heim: File = File(System.getProperty("user.home")),
    ) {
        state.apiKey = e.apiKey
        state.instanceId = e.instanceId
        state.trialEndsAt = ""
        state.onboardingAttempts = 0
        if (CachlyInstructionWriter.leseHeimSchluessel(heim) == alterTestSchluessel.trim()) {
            CachlyInstructionWriter.legeSchluesselImHeimAb(e.apiKey, e.instanceId, heim, ersetzen = true)
        }
    }
}
