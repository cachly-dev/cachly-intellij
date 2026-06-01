package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JScrollPane

class SaveLessonAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = SaveLessonDialog(project)
        if (dialog.showAndGet()) {
            val topic = dialog.topicField.text.trim()
            val lesson = dialog.lessonArea.text.trim()
            if (topic.isNotEmpty() && lesson.isNotEmpty()) {
                Thread {
                    val ok = CachlyApiClient.saveLesson(topic, lesson)
                    javax.swing.SwingUtilities.invokeLater {
                        if (ok) {
                            Messages.showInfoMessage(project, "Lesson saved to your Brain!", "Cachly Brain")
                        } else {
                            Messages.showErrorDialog(project, "Failed to save lesson. Check your API key and instance ID in Settings → Tools → Cachly Brain.", "Cachly Brain")
                        }
                    }
                }.start()
            }
        }
    }
}

class SaveLessonDialog(project: Project) : DialogWrapper(project) {

    val topicField = JBTextField(30)
    val lessonArea = JTextArea(5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Save Lesson to Cachly Brain"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Topic (e.g. deploy:k8s):"), gbc)

        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(topicField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JBLabel("What worked:"), gbc)

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
        panel.add(JScrollPane(lessonArea), gbc)

        return panel
    }
}
