package dev.cachly.brain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CachlySettings", storages = [Storage("cachly-brain.xml")])
class CachlySettings : PersistentStateComponent<CachlySettings.State> {

    data class State(
        var apiKey: String = "",
        var instanceId: String = "",
        var apiUrl: String = "https://api.cachly.dev",
        var refreshIntervalSec: Int = 300,
        var showCostSaved: Boolean = true,
        var ambientLearning: Boolean = true,
        var proactiveBriefing: Boolean = true,
        /** Silences every proactive popup outright — status bar & panel still update. */
        var quietMode: Boolean = false,
        var firstHitShown: Boolean = false,
        /**
         * End of a trial obtained via instant-trial, as sent by the API in
         * `trial_ends_at`. Empty for a linked account.
         */
        var trialEndsAt: String = "",
        /**
         * How often the plugin has tried to fetch a key on its own without ever
         * succeeding. Capped in CachlyOnboarding: if the API answers but storing
         * the key fails for some reason, an uncapped retry would create a fresh
         * trial instance on every single project open.
         */
        var onboardingAttempts: Int = 0,
        /** True once the user has explicitly dismissed the connect prompt. */
        var onboardingDeclined: Boolean = false,
        /**
         * "Not helpful" suppressions for per-file briefings, keyed
         * "relPath::topic" -> epoch millis. Capped at 300 (oldest evicted).
         */
        var briefingSuppressed: MutableMap<String, Long> = mutableMapOf(),
        // Last health snapshot that actually carried data, persisted across
        // restarts. A cold-start fetch (network still down, instance waking,
        // transient zeroed stats) otherwise repaints real counters as 0/limit.
        var lastGoodLessons: Int = 0,
        var lastGoodTotalRecalls: Int = 0,
        var lastGoodRecallLimit: Int = -1,
        var lastGoodTokensSaved: Int = 0,
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(): CachlySettings =
            ApplicationManager.getApplication().getService(CachlySettings::class.java)
    }
}
