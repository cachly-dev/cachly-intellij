package dev.cachly.brain

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import java.net.HttpURLConnection
import java.net.URI

/**
 * "Run Brain Doctor" — runs a set of ✓/✗ diagnostics against the configured
 * Brain and shows the results, mirroring the VS Code extension's Brain Doctor.
 * Helps users self-diagnose missing keys, wrong instance IDs, and connectivity.
 */
class BrainDoctorAction : AnAction() {

    private val apiKeyRegex = Regex("^cky_(live|trial|test)_[A-Za-z0-9]+$")
    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        object : Task.Backgroundable(project, "Running Cachly Brain Doctor", false) {
            override fun run(indicator: ProgressIndicator) {
                val s = CachlySettings.getInstance().state
                val lines = mutableListOf<String>()
                var allOk = true
                fun check(ok: Boolean, label: String, detail: String = "") {
                    if (!ok) allOk = false
                    lines.add("${if (ok) "✓" else "✗"} $label${if (detail.isNotEmpty()) " — $detail" else ""}")
                }

                val baseUrl = s.apiUrl.trimEnd('/').ifBlank { "https://api.cachly.dev" }
                check(baseUrl.startsWith("http"), "API URL", baseUrl)

                val keyPresent = s.apiKey.isNotBlank()
                check(keyPresent, "API key present")
                if (keyPresent) check(apiKeyRegex.matches(s.apiKey), "API key format", "expected cky_live_… / cky_trial_… / cky_test_…")

                val idPresent = s.instanceId.isNotBlank()
                check(idPresent, "Instance ID present")
                if (idPresent) check(uuidRegex.matches(s.instanceId), "Instance ID format", "expected a UUID")

                if (keyPresent && idPresent) {
                    val instStatus = httpStatus("$baseUrl/api/v1/instances/${s.instanceId}", s.apiKey)
                    when (instStatus) {
                        200 -> check(true, "Instance reachable")
                        401, 403 -> check(false, "Instance reachable", "auth rejected ($instStatus) — check API key / setup")
                        404 -> check(false, "Instance reachable", "not found (404) — wrong instance ID?")
                        -1 -> check(false, "Instance reachable", "network error — Brain unreachable")
                        else -> check(false, "Instance reachable", "HTTP $instStatus")
                    }
                    val memStatus = httpStatus("$baseUrl/api/v1/instances/${s.instanceId}/memory", s.apiKey)
                    check(memStatus == 200, "Memory readable", if (memStatus == 200) "" else "HTTP $memStatus")
                }

                val header = if (allOk) "🧠 Brain Doctor — all checks passed" else "🧠 Brain Doctor — problems found"
                javax.swing.SwingUtilities.invokeLater {
                    if (allOk) Messages.showInfoMessage(project, lines.joinToString("\n"), header)
                    else {
                        val choice = Messages.showDialog(
                            project,
                            lines.joinToString("\n") + "\n\nFix your API key / instance ID in Settings → Tools → Cachly Brain.",
                            header,
                            arrayOf("Open Settings", "Close"),
                            0,
                            Messages.getWarningIcon(),
                        )
                        if (choice == 0) {
                            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                                .showSettingsDialog(project, "Cachly Brain")
                        }
                    }
                }
            }
        }.queue()
    }

    /** Returns the HTTP status code, or -1 on a network-level error. */
    private fun httpStatus(url: String, apiKey: String): Int {
        return try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            conn.disconnect()
            code
        } catch (_: Exception) { -1 }
    }
}
