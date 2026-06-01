package dev.cachly.brain

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.LocalFileSystem

class FrameworkDetectionStartup : StartupActivity {

    override fun runActivity(project: Project) {
        AmbientLearningService.getInstance(project).install()

        val projectPath = project.basePath ?: return
        val fs = LocalFileSystem.getInstance()

        val frameworks = mutableListOf<String>()

        val packageJson = fs.findFileByPath("$projectPath/package.json")
        if (packageJson != null) {
            val content = String(packageJson.contentsToByteArray())
            if ("\"next\"" in content) frameworks.add("Next.js")
            else if ("\"react\"" in content) frameworks.add("React")
            else if ("\"vue\"" in content) frameworks.add("Vue")
            else frameworks.add("Node.js")
        }

        val goMod = fs.findFileByPath("$projectPath/go.mod")
        if (goMod != null) {
            val content = String(goMod.contentsToByteArray())
            val module = content.lines().firstOrNull { it.startsWith("module ") }?.removePrefix("module ")?.trim()
            frameworks.add("Go${if (module != null) " ($module)" else ""}")
        }

        val requirements = fs.findFileByPath("$projectPath/requirements.txt")
            ?: fs.findFileByPath("$projectPath/pyproject.toml")
        if (requirements != null) frameworks.add("Python")

        val buildGradle = fs.findFileByPath("$projectPath/build.gradle")
            ?: fs.findFileByPath("$projectPath/build.gradle.kts")
        if (buildGradle != null) frameworks.add("JVM/Gradle")

        if (frameworks.isEmpty()) return

        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("Cachly Brain Ambient")
            ?: return

        group.createNotification(
            "Cachly Brain — Project Detected",
            "Detected: ${frameworks.joinToString(", ")}. Your Brain is ready with context for this stack.",
            NotificationType.INFORMATION
        ).notify(project)
    }
}
