package dev.cachly.brain

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AmbientLearningService(private val project: Project) {

    private data class EditPattern(val text: String, var count: Int = 1, var lastSeen: Long = System.currentTimeMillis())

    private val recentPatterns = ConcurrentHashMap<String, EditPattern>()
    private val notifiedPatterns = mutableSetOf<String>()

    private val listener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!CachlySettings.getInstance().state.ambientLearning) return
            val fragment = event.newFragment.toString().trim()
            if (fragment.length < 20 || fragment.length > 300) return
            trackEdit(fragment)
        }
    }

    fun install() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(listener, project)
    }

    private fun trackEdit(text: String) {
        val key = text.take(80)
        val now = System.currentTimeMillis()

        recentPatterns.entries.removeIf { now - it.value.lastSeen > 10 * 60 * 1000 }

        val existing = recentPatterns.entries.find { diceSimilarity(it.key, key) > 0.75 }
        if (existing != null) {
            existing.value.count++
            existing.value.lastSeen = now
            if (existing.value.count >= 3 && existing.key !in notifiedPatterns) {
                notifiedPatterns.add(existing.key)
                suggestLesson(existing.key)
            }
        } else {
            recentPatterns[key] = EditPattern(key)
        }
    }

    private fun suggestLesson(pattern: String) {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("Cachly Brain Ambient")
            ?: return

        group.createNotification(
            "Cachly Brain — Save This?",
            "You've typed a similar pattern 3+ times: \"${pattern.take(60)}…\" — save it as a lesson?",
            NotificationType.INFORMATION
        ).addAction(object : com.intellij.openapi.actionSystem.AnAction("Save Lesson") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val dialog = SaveLessonDialog(project).apply {
                    lessonArea.text = pattern
                }
                if (dialog.showAndGet()) {
                    val topic = dialog.topicField.text.trim()
                    val lesson = dialog.lessonArea.text.trim()
                    if (topic.isNotEmpty() && lesson.isNotEmpty()) {
                        Thread { CachlyApiClient.saveLesson(topic, lesson) }.start()
                    }
                }
            }
        }).notify(project)
    }

    companion object {
        fun getInstance(project: Project): AmbientLearningService =
            project.getService(AmbientLearningService::class.java)

        fun diceSimilarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val bigramsA = bigrams(a)
            val bigramsB = bigrams(b)
            if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
            val intersection = bigramsA.intersect(bigramsB).size
            return 2.0 * intersection / (bigramsA.size + bigramsB.size)
        }

        private fun bigrams(s: String): Set<String> {
            if (s.length < 2) return emptySet()
            return (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
        }
    }
}
