package dev.cachly.brain

/**
 * One shared interruption budget for every proactive popup (framework detection,
 * ambient "save this?", startup briefing, per-file failure warning). Each gated
 * itself locally, which was reasonable per surface and added up to a popup every
 * few minutes. They now share one budget: at most one interruption every
 * [MIN_GAP_MS] and [MAX_PER_SESSION] per IDE session. `quietMode` turns them all
 * off outright. Anything denied simply doesn't show — the status bar and Brain
 * panel stay available.
 */
object ProactiveBudget {
    private const val MIN_GAP_MS = 20 * 60_000L
    private const val MAX_PER_SESSION = 3
    private var lastAt = 0L
    private var count = 0

    /**
     * Ask permission to interrupt. Returns true at most [MAX_PER_SESSION] times
     * per session and never within [MIN_GAP_MS]; false in quiet mode. Callers
     * spend the budget by asking, so only call it right before showing.
     */
    @Synchronized
    fun claimInterrupt(): Boolean {
        if (CachlySettings.getInstance().state.quietMode) return false
        val now = System.currentTimeMillis()
        if (count >= MAX_PER_SESSION) return false
        if (now - lastAt < MIN_GAP_MS) return false
        lastAt = now
        count++
        return true
    }
}
