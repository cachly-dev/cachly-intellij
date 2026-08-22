package dev.cachly.brain

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URI

data class TopLesson(
    val topic: String = "",
    val outcome: String = "",
    @SerializedName("recall_count") val recallCount: Int = 0,
    val severity: String? = null,
    @SerializedName("what_worked") val whatWorked: String = "",
    val ts: String? = null,
    val author: String? = null,
)

data class MemoryCrystal(
    val summary: String = "",
    @SerializedName("patterns_hit") val patternsHit: Int = 0,
    @SerializedName("created_at") val createdAt: String = "",
)

data class MemoryResponse(
    @SerializedName("lesson_count") val lessonCount: Int = 0,
    @SerializedName("context_count") val contextCount: Int = 0,
    val topics: List<String> = emptyList(),
    @SerializedName("top_lessons") val topLessons: List<TopLesson> = emptyList(),
    @SerializedName("last_session") val lastSession: Map<String, Any>? = null,
    @SerializedName("memory_used_bytes") val memoryUsedBytes: Long = 0,
    @SerializedName("memory_limit_bytes") val memoryLimitBytes: Long = 0,
    @SerializedName("memory_used_pct") val memoryUsedPct: Double = 0.0,
    @SerializedName("total_recall_count") val totalRecallCount: Long = 0,
    @SerializedName("recall_limit") val recallLimit: Int = -1,
    /**
     * Ein Satz vom Server an den Menschen hinter dieser Instanz.
     *
     * Den Kanal gibt es seit dem 13.08.2026, angezeigt hat ihn bis zum
     * 17.08. NUR der MCP-Server. Wer cachly ueber dieses Plugin benutzt und
     * nicht ueber einen Agenten, hat ihn nie gesehen — auch nicht die Warnung,
     * dass sein Schluessel nur in dieser IDE liegt.
     */
    val notice: String? = null,
    @SerializedName("iq_boost_pct") val iqBoostPct: Double = 0.0,
    @SerializedName("team_authors") val teamAuthors: List<String> = emptyList(),
    val crystal: MemoryCrystal? = null,
)

/**
 * Answer of POST /auth/instant-trial — the zero-click onboarding path.
 *
 * Field names verified against the live API on 2026-08-17: api_key,
 * instance_id, message, trial, trial_ends_at. The VS Code extension read
 * `expires_at` here for weeks, which silently disabled its whole
 * trial-expiry warning — so this type carries the name the server
 * actually sends.
 */
data class InstantTrialResponse(
    @SerializedName("api_key") val apiKey: String = "",
    @SerializedName("instance_id") val instanceId: String = "",
    val trial: Boolean = false,
    @SerializedName("trial_ends_at") val trialEndsAt: String? = null,
    val message: String? = null,
)

/**
 * Answer of POST /auth/device (RFC 8628 device authorization).
 *
 * Verified against the live API on 2026-08-17: device_code, user_code,
 * verification_uri, expires_in=600, interval=5.
 */
data class DeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String = "",
    @SerializedName("user_code") val userCode: String = "",
    @SerializedName("verification_uri") val verificationUri: String = "https://cachly.dev/device",
    @SerializedName("expires_in") val expiresIn: Int = 600,
    val interval: Int = 5,
)

/**
 * Answer of POST /auth/device/token.
 *
 * CAREFUL: while the user has not approved yet, this endpoint answers
 * HTTP 200 with {"error":"authorization_pending"} — not an HTTP error status.
 * Treating a non-200 as "still waiting" would therefore poll forever, and
 * treating any 200 as success would store an empty key.
 */
data class DeviceTokenResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("instance_id") val instanceId: String? = null,
    val error: String? = null,
)

data class InstanceResponse(
    val tier: String? = null,
    val status: String? = null,
)

data class InsightsResponse(
    @SerializedName("minutes_saved") val minutesSaved: Double = 0.0,
    @SerializedName("dollars_saved") val dollarsSaved: Double = 0.0,
    @SerializedName("recalls_total") val recallsTotal: Long = 0,
    @SerializedName("reuse_pct") val reusePct: Double = 0.0,
    @SerializedName("ttfr_p50_sec") val ttfrP50Sec: Double = -1.0,
    @SerializedName("ttfr_p90_sec") val ttfrP90Sec: Double = -1.0,
    val currency: String = "EUR",
    @SerializedName("hourly_rate") val hourlyRate: Double = 75.0,
)

data class BriefingWarning(
    val topic: String = "",
    val confidence: Double = 0.0,
    val severity: String = "info",
    val message: String = "",
    val fix: String = "",
    val outcome: String? = null,
    val author: String? = null,
    @SerializedName("learned_at") val learnedAt: String? = null,
    @SerializedName("matched_on") val matchedOn: List<String> = emptyList(),
)

data class BriefingResponse(
    @SerializedName("risk_level") val riskLevel: String = "low",
    val warnings: List<BriefingWarning> = emptyList(),
    @SerializedName("matched_lessons") val matchedLessons: Int = 0,
)

data class BrainHealth(
    val lessons: Int = 0,
    val contexts: Int = 0,
    val totalRecalls: Int = 0,
    val estimatedTokensSaved: Int = 0,
    val tier: String = "unknown",
    val status: String = "unreachable",
    val topLessons: List<TopLesson> = emptyList(),
    val topics: List<String> = emptyList(),
    val lastSession: String? = null,
    val memoryUsedBytes: Long = 0,
    val memoryLimitBytes: Long = 0,
    val memoryUsedPct: Double = 0.0,
    val iqBoostPct: Double = 0.0,
    val teamAuthors: List<String> = emptyList(),
    val crystal: MemoryCrystal? = null,
    /** Lessons locally queued offline (not yet synced to Brain). */
    val pendingLessons: Int = 0,
    /** -1 = unlimited (paid tier). */
    val recallLimit: Int = -1,
    /** ROI aggregates from /api/v1/insights — null if endpoint unavailable. */
    val insights: InsightsResponse? = null,
    /** True when the counts are the persisted last-good snapshot, not a fresh fetch. */
    val showingSnapshot: Boolean = false,
    /** Ein Satz vom Server — siehe MemoryResponse.notice. Leer = nichts anliegend. */
    val notice: String? = null,
) {
    companion object {
        /** Average tokens saved per recall — reuses known solution instead of re-researching. */
        const val TOKENS_PER_RECALL = 1200

        /** ~$3 per 1M tokens (blended LLM input rate) — used for the cost estimate. */
        const val COST_PER_TOKEN = 0.000003
    }
}

object CachlyApiClient {

    /**
     * Sent on every request so the server can tell IntelliJ traffic apart from
     * VS Code and the CLI.
     *
     * Until 2026-08-17 this plugin sent no User-Agent at all. That is not
     * cosmetic: with 18 marketplace downloads and zero registrations, there was
     * no way to see from the server side whether the plugin ever called the API
     * — the one measurement that would have shown the missing onboarding
     * months earlier.
     *
     * Read from the plugin descriptor so it cannot drift away from the shipped
     * version, with a literal fallback for unit tests that run without an IDE.
     *
     * ── Warum NICHT PluginManagerCore (JetBrains-Vorgang 9078085) ──────────
     *
     * Bis zum 22.08.2026 stand hier:
     *
     *     PluginManagerCore.getPlugin(PluginId.getId("dev.cachly.brain"))?.version
     *
     * Genau diese eine Zeile hat die Freigabe von 0.8.0 blockiert. Der
     * Marketplace-Verifier (Lauf 1141205, 21.08.2026, Verifier 1.408):
     *
     *     Compatible. 1 usage of internal API
     *       Internal method usage (1)
     *         PluginManagerCore.getPlugin(PluginId) (1)
     *
     * Wichtig fuer die Fehlersuche: `./gradlew verifyPlugin` findet das NICHT.
     * Der Marktplatz prueft gegen **IntelliJ IDEA 2026.2.2 EAP
     * (262.10315.19)**, unser Build nur gegen 2024.1 und 2025.2. In 2025.2
     * traegt die Methode nachweislich nur `@JvmStatic` und `@Contract` — in
     * 262 ist sie intern.
     *
     * Der Grund dafuer ist unser eigener `untilBuild = null`: Wir behaupten
     * Vertraeglichkeit ohne Obergrenze und pruefen nur bis 2025.2. Wer diese
     * Luecke schliessen will, siehe .agent/JETBRAINS-ANTWORT-9078085.md.
     *
     * `PluginManager.getPluginByClass` ist oeffentlich (geprueft in 2025.2:
     * traegt nur `@Nullable`) und ausserdem der bessere Weg — es braucht die
     * Plugin-Kennung nicht als Zeichenkette und kann deshalb nicht von der
     * plugin.xml abweichen.
     */
    val USER_AGENT: String by lazy {
        val v = try {
            com.intellij.ide.plugins.PluginManager
                .getPluginByClass(CachlyApiClient::class.java)
                ?.version
        } catch (_: Throwable) { null }
        "cachly-intellij/${v ?: "dev"}"
    }

    private val gson = Gson()

    fun fetchHealth(): BrainHealth {
        val settings = CachlySettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.instanceId.isBlank()) {
            return BrainHealth(status = "not_configured")
        }

        val baseUrl = settings.apiUrl.trimEnd('/')
        val id = settings.instanceId

        // 1. Fetch instance info
        val instJson = httpGet("$baseUrl/api/v1/instances/$id", settings.apiKey)
            ?: return applyColdStartGuard(BrainHealth(status = "unreachable"))
        val inst = gson.fromJson(instJson, InstanceResponse::class.java)

        // 2. Fetch memory stats
        val memJson = httpGet("$baseUrl/api/v1/instances/$id/memory", settings.apiKey)
        val mem = if (memJson != null) gson.fromJson(memJson, MemoryResponse::class.java) else MemoryResponse()

        val totalRecalls = if (mem.totalRecallCount > 0) mem.totalRecallCount.toInt()
            else mem.topLessons.sumOf { it.recallCount }
        val lastSessionStr = mem.lastSession?.get("summary")?.toString()
            ?: mem.lastSession?.get("focus")?.toString()

        val pendingCount = OfflineLessonQueue.getInstance().pendingCount()

        // 3. Fetch tenant-level ROI insights (best-effort — null if unavailable)
        val insights = fetchInsights(baseUrl, settings.apiKey)

        return applyColdStartGuard(BrainHealth(
            lessons = mem.lessonCount,
            contexts = mem.contextCount,
            totalRecalls = totalRecalls,
            estimatedTokensSaved = totalRecalls * BrainHealth.TOKENS_PER_RECALL,
            tier = inst.tier ?: "unknown",
            status = if (inst.tier != null) "healthy" else "degraded",
            topLessons = mem.topLessons,
            topics = mem.topics,
            lastSession = lastSessionStr,
            memoryUsedBytes = mem.memoryUsedBytes,
            memoryLimitBytes = mem.memoryLimitBytes,
            memoryUsedPct = mem.memoryUsedPct,
            iqBoostPct = mem.iqBoostPct,
            teamAuthors = mem.teamAuthors,
            crystal = mem.crystal,
            pendingLessons = pendingCount,
            recallLimit = mem.recallLimit,
            insights = insights,
            notice = mem.notice?.takeIf { it.isNotBlank() },
        ))
    }

    /**
     * Cold-start guard: persist the last snapshot that carried data, and when a
     * fetch comes back empty-handed (network still down after a restart, the
     * instance still waking, transient zeroed stats) show that snapshot instead
     * of repainting real counters as 0. Mirrors the VS Code extension (0.12.2).
     */
    private fun applyColdStartGuard(health: BrainHealth): BrainHealth {
        val settings = CachlySettings.getInstance().state
        if (health.lessons > 0 || health.totalRecalls > 0) {
            settings.lastGoodLessons = health.lessons
            settings.lastGoodTotalRecalls = health.totalRecalls
            settings.lastGoodRecallLimit = health.recallLimit
            settings.lastGoodTokensSaved = health.estimatedTokensSaved
            return health
        }
        val snapshotHasData = settings.lastGoodLessons > 0 || settings.lastGoodTotalRecalls > 0
        if (!snapshotHasData) return health
        return health.copy(
            lessons = settings.lastGoodLessons,
            totalRecalls = settings.lastGoodTotalRecalls,
            recallLimit = settings.lastGoodRecallLimit,
            estimatedTokensSaved = settings.lastGoodTokensSaved,
            status = if (health.status == "unreachable") "unreachable" else "degraded",
            showingSnapshot = true,
        )
    }

    /** Fetches tenant-level ROI aggregates. Returns null on any error (best-effort). */
    fun fetchInsights(baseUrl: String, apiKey: String): InsightsResponse? {
        val json = httpGet("$baseUrl/api/v1/insights", apiKey) ?: return null
        return try {
            gson.fromJson(json, InsightsResponse::class.java)
        } catch (_: Exception) { null }
    }

    fun saveLesson(topic: String, whatWorked: String): Boolean {
        val settings = CachlySettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.instanceId.isBlank()) {
            // No credentials — queue offline for later sync
            OfflineLessonQueue.getInstance().enqueue(topic, whatWorked)
            return false
        }
        val baseUrl = settings.apiUrl.trimEnd('/')
        val body = gson.toJson(mapOf(
            "topic" to topic,
            "outcome" to "success",
            "what_worked" to whatWorked,
            "source" to "intellij",
        ))
        val ok = httpPost("$baseUrl/api/v1/instances/${settings.instanceId}/learn", settings.apiKey, body) != null
        if (!ok) {
            // API unreachable — save locally, will sync when Brain is reachable
            OfflineLessonQueue.getInstance().enqueue(topic, whatWorked)
        }
        return ok
    }

    /**
     * Attempts to upload all locally-queued lessons to the Brain.
     * Called by the status bar widget on each successful refresh cycle.
     * Lessons that fail to upload are re-queued for the next cycle.
     */
    fun drainOfflineQueue() {
        val queue = OfflineLessonQueue.getInstance()
        if (queue.pendingCount() == 0) return
        val settings = CachlySettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.instanceId.isBlank()) return
        val baseUrl = settings.apiUrl.trimEnd('/')
        val pending = queue.drainSnapshot()
        val failed = mutableListOf<OfflineLessonQueue.PendingLesson>()
        for (lesson in pending) {
            val body = gson.toJson(mapOf(
                "topic" to lesson.topic,
                "outcome" to "success",
                "what_worked" to lesson.whatWorked,
                "source" to lesson.source,
            ))
            val ok = httpPost("$baseUrl/api/v1/instances/${settings.instanceId}/learn", settings.apiKey, body) != null
            if (!ok) failed.add(lesson)
        }
        if (failed.isNotEmpty()) queue.requeue(failed)
    }

    /**
     * Per-file proactive briefing: asks the Brain whether it holds failure
     * patterns for a just-opened file. Null on any error or missing credentials
     * — the caller degrades silently (a briefing must never surface errors).
     */
    fun fetchBriefing(relPath: String): BriefingResponse? {
        val settings = CachlySettings.getInstance().state
        if (settings.apiKey.isBlank() || settings.instanceId.isBlank()) return null
        val baseUrl = settings.apiUrl.trimEnd('/')
        val body = gson.toJson(mapOf("event_type" to "file_open", "context" to relPath))
        val json = httpPost(
            "$baseUrl/api/v1/instances/${settings.instanceId}/briefing",
            settings.apiKey,
            body,
        ) ?: return null
        return try {
            gson.fromJson(json, BriefingResponse::class.java)
        } catch (_: Exception) { null }
    }

    /**
     * Fire-and-forget anonymous usage ping (same endpoint the VS Code extension
     * and MCP server use). Must be called off the EDT; never throws.
     */
    fun trackEvent(event: String) {
        val settings = CachlySettings.getInstance().state
        val baseUrl = settings.apiUrl.trimEnd('/')
        val body = gson.toJson(mapOf(
            "event" to event,
            "editor" to "intellij",
            "source" to "intellij",
            "api_key" to settings.apiKey,
            "instance_id" to settings.instanceId,
        ))
        try {
            val conn = URI("$baseUrl/api/v1/telemetry/mcp").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.bufferedWriter().use { it.write(body) }
            conn.responseCode // drain — telemetry must never break UX
        } catch (_: Exception) { /* silently ignore */ }
    }

    private fun httpGet(url: String, apiKey: String): String? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Fetches a trial Brain without asking the user for anything.
     *
     * WHY THIS EXISTS (measured 2026-08-17): the plugin had 18 marketplace
     * downloads and produced zero registrations. It offered exactly one way
     * in — an empty API-key field in the settings dialog — and on first start
     * without a key it fetched health, saw "not healthy" and returned in
     * silence. Nothing asked the user to sign up, and nothing showed them
     * where. The VS Code extension has had this endpoint wired for weeks;
     * this plugin never did.
     *
     * Returns null on any failure. A failed onboarding attempt must never
     * throw into IDE startup.
     */
    fun requestInstantTrial(): InstantTrialResponse? {
        val raw = httpPostAnon("${apiBase()}/auth/instant-trial", "{}") ?: return null
        return try {
            val parsed = Gson().fromJson(raw, InstantTrialResponse::class.java)
            if (parsed?.apiKey.isNullOrBlank()) null else parsed
        } catch (_: Exception) { null }
    }

    /**
     * POST without credentials. Onboarding happens BEFORE a key exists, so the
     * authenticated helper below cannot be reused: it would send
     * "Authorization: Bearer " and the API would reject it.
     *
     * Accepts 200 and 201 — the trial endpoint answers 201 Created, and
     * checking only for 200 would have made this silently useless.
     */
    private fun httpPostAnon(url: String, body: String): String? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.bufferedWriter().use { it.write(body) }
            if (conn.responseCode in 200..201) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } catch (_: Exception) { null }
    }

    /** Configured API root without a trailing slash, falling back to production. */
    private fun apiBase(): String =
        CachlySettings.getInstance().state.apiUrl.trimEnd('/').ifBlank { "https://api.cachly.dev" }

    /** Starts the device flow. Returns null if the endpoint is unreachable. */
    fun requestDeviceCode(): DeviceCodeResponse? {
        val raw = httpPostAnon("${apiBase()}/auth/device", "{}") ?: return null
        return try {
            val parsed = Gson().fromJson(raw, DeviceCodeResponse::class.java)
            if (parsed?.deviceCode.isNullOrBlank() || parsed?.userCode.isNullOrBlank()) null else parsed
        } catch (_: Exception) { null }
    }

    /**
     * Asks once whether the user has approved the device code yet.
     *
     * Returns null when the answer could not be read at all (network glitch) —
     * the caller keeps waiting. A parsed answer with error="authorization_pending"
     * is a normal "not yet", NOT a failure.
     */
    fun pollDeviceToken(deviceCode: String): DeviceTokenResponse? {
        val body = Gson().toJson(mapOf("device_code" to deviceCode))
        val raw = httpPostAnon("${apiBase()}/auth/device/token", body) ?: return null
        return try { Gson().fromJson(raw, DeviceTokenResponse::class.java) } catch (_: Exception) { null }
    }

    private fun httpPost(url: String, apiKey: String, body: String): String? {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.bufferedWriter().use { it.write(body) }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (_: Exception) { null }
    }
}
