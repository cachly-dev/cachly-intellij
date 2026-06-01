package dev.cachly.brain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent offline lesson queue — mirrors the VS Code extension's
 * cachly.offlineLessonQueue globalState store.
 *
 * When the Brain API is unreachable, lessons are enqueued here and
 * persisted in cachly-brain.xml (same file as CachlySettings).
 * The status bar widget drains the queue automatically on each
 * successful refresh cycle.
 *
 * Capped at 500 entries to prevent unbounded growth.
 */
@State(name = "CachlyOfflineLessonQueue", storages = [Storage("cachly-brain.xml")])
class OfflineLessonQueue : PersistentStateComponent<OfflineLessonQueue.State> {

    data class PendingLesson(
        var topic: String = "",
        var whatWorked: String = "",
        var savedAt: Long = 0L,
        var source: String = "intellij",
    )

    data class State(
        var lessons: MutableList<PendingLesson> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun enqueue(topic: String, whatWorked: String) {
        val entry = PendingLesson(topic = topic, whatWorked = whatWorked, savedAt = System.currentTimeMillis())
        myState.lessons.add(entry)
        // Cap at 500 to avoid unbounded growth
        if (myState.lessons.size > 500) {
            myState.lessons = myState.lessons.takeLast(500).toMutableList()
        }
    }

    fun pendingCount(): Int = myState.lessons.size

    /** Returns a snapshot and clears the queue atomically. */
    fun drainSnapshot(): List<PendingLesson> {
        val snap = myState.lessons.toList()
        myState.lessons.clear()
        return snap
    }

    /** Re-enqueues lessons that failed to sync (e.g. API still down). */
    fun requeue(lessons: List<PendingLesson>) {
        myState.lessons.addAll(0, lessons)
        if (myState.lessons.size > 500) {
            myState.lessons = myState.lessons.takeLast(500).toMutableList()
        }
    }

    companion object {
        fun getInstance(): OfflineLessonQueue =
            ApplicationManager.getApplication().getService(OfflineLessonQueue::class.java)
    }
}
