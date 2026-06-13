package dev.cachly.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [computeWoW] — pure week-over-week logic, no IDE fixtures.
 * Mirrors the VS Code extension's wow.test.ts so both ports stay in sync.
 */
class WeekOverWeekTest {

    /** Build N consecutive days starting 2026-01-01; index 0 is the oldest. */
    private fun days(events: List<Int>): List<TrendBucket> =
        events.mapIndexed { i, e ->
            val day = (1 + i).toString().padStart(2, '0')
            TrendBucket(date = "2026-01-$day", events = e, fixes = 0)
        }

    @Test
    fun emptyTrendHasNullPct() {
        val r = computeWoW(emptyList())
        assertEquals(0, r.thisWeek)
        assertEquals(0, r.lastWeek)
        assertNull(r.pct)
    }

    @Test
    fun noPriorWeekBaselineYieldsNullPct() {
        val r = computeWoW(days(listOf(1, 2, 3, 4, 5, 6, 7)))
        assertEquals(28, r.thisWeek)
        assertEquals(0, r.lastWeek)
        assertNull(r.pct)
    }

    @Test
    fun positiveWeekOverWeek() {
        val r = computeWoW(days(listOf(1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2)))
        assertEquals(7, r.lastWeek)
        assertEquals(14, r.thisWeek)
        assertEquals(100.0, r.pct)
    }

    @Test
    fun negativeWeekOverWeek() {
        val r = computeWoW(days(listOf(10, 10, 10, 10, 10, 10, 10, 5, 5, 5, 5, 5, 5, 5)))
        assertEquals(70, r.lastWeek)
        assertEquals(35, r.thisWeek)
        assertEquals(-50.0, r.pct)
    }

    @Test
    fun equalWeeksAreZeroPct() {
        val r = computeWoW(days(List(14) { 3 }))
        assertEquals(0.0, r.pct)
    }

    @Test
    fun onlyLast14DaysCount() {
        val r = computeWoW(days(List(28) { 1 }))
        assertEquals(7, r.thisWeek)
        assertEquals(7, r.lastWeek)
        assertEquals(0.0, r.pct)
    }

    @Test
    fun sortsByDateBeforeSlicing() {
        val ordered = days(listOf(1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9))
        val shuffled = ordered.reversed()
        assertEquals(computeWoW(ordered), computeWoW(shuffled))
    }
}
