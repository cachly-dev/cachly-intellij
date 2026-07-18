plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.cachly"
version = "0.7.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1")
        instrumentationTools()
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.cachly.brain"
        name = "Cachly Brain"
        version = project.version.toString()
        description = """
            <p>Your AI assistant is brilliant for one session &mdash; then it forgets everything.
            Every day you re-explain your architecture, your deploy steps, the bug you already fixed.
            <a href="https://cachly.dev">Cachly</a> gives your AI a permanent, shared brain that learns from
            every commit and gets smarter over time. This plugin brings that brain into your JetBrains IDE.</p>
            <ul>
              <li><b>One-click setup</b> &mdash; "Set Up AI Files" writes your MCP config, agent instructions, and a git post-commit learning hook. No terminal needed.</li>
              <li><b>Brain Doctor</b> &mdash; diagnoses your connection (API key, instance, network) and points you straight to the fix.</li>
              <li><b>Ambient learning</b> &mdash; detects repeated patterns and offers to save them as reusable lessons.</li>
              <li><b>Proactive briefing</b> &mdash; surfaces your top lessons on project open, so the brain greets you instead of waiting to be asked.</li>
              <li><b>Status bar widget</b> &mdash; live lesson count, brain health, and estimated tokens saved.</li>
              <li><b>Lesson viewer</b> &mdash; browse every learned lesson with recall counts and severity.</li>
            </ul>
            <p>Works with the Cachly MCP server. Free tier forever &middot; GDPR &middot; EU servers.</p>
        """.trimIndent()
        changeNotes = """
            <h3>0.7.0</h3>
            <ul>
              <li><b>Recall counter survives a restart</b> &mdash; the first health fetch after opening the IDE can run before the network is up (or while the instance is still waking) and come back zeroed. The plugin now persists the last snapshot that actually carried data and shows it (with a &#8635; hint) until a good fetch replaces it, instead of briefly repainting your lessons/recalls as 0. Parity with VS Code 0.12.2.</li>
              <li><b>Quiet mode</b> &mdash; a new setting that turns off every proactive popup at once (status bar and Brain panel still update).</li>
              <li><b>One notification budget</b> &mdash; framework detection, the ambient "save this?" prompt, the startup briefing and per-file warnings now share a single budget: at most one popup every 20 minutes, three per session. Framework detection in particular no longer fires unconditionally on every project open.</li>
            </ul>
            <h3>0.6.0</h3>
            <ul>
              <li><b>Per-file fix hints</b> &mdash; opening a file the Brain knows has a failure pattern now shows a warning with severity and confidence, plus <i>Show fix</i> (full lesson card: outcome, confidence, author, learned date, and the exact tokens that triggered the match), <i>Copy fix</i>, and <i>Not helpful</i> (suppresses that lesson for that file, remembered across restarts). Parity with VS Code 0.12.0.</li>
              <li><b>Hint telemetry</b> &mdash; anonymous shown/opened/copied/not-helpful events so hint quality becomes measurable.</li>
            </ul>
            <h3>0.4.0</h3>
            <ul>
              <li><b>Proactive briefing</b> &mdash; on project open the plugin now surfaces your Brain's top lessons as a notification (push-based recall), instead of waiting for "Show Lessons". Opt-out in Settings.</li>
              <li><b>Cross-harness rules files</b> &mdash; "Set Up AI Files" now also writes <code>.cursor/rules/cachly.mdc</code>, <code>.windsurfrules</code> and <code>.clinerules</code>, so Cursor, Windsurf and Cline get the Brain protocol too.</li>
            </ul>
            <h3>0.3.3</h3>
            <ul>
              <li><b>Fix:</b> the Settings panel crashed on IntelliJ 2024.1 with <code>UnsupportedClassVersionError</code> &mdash; the plugin was compiled for Java 21 while 2024.1 runs on Java 17. Now targets Java 17 across the full 2024.1&ndash;2025.2 range.</li>
            </ul>
            <h3>0.3.2</h3>
            <ul>
              <li><b>CI auto-detection</b> &mdash; "Set Up AI Files" detects your git remote (GitHub or GitLab) and scaffolds the matching CI config: a GitHub Actions workflow or a GitLab CI/CD include. Idempotent and non-destructive.</li>
            </ul>
            <h3>0.3.1</h3>
            <ul>
              <li>Version alignment with MCP server 0.10.103 and VS Code extension 0.9.6</li>
              <li>Compatible with <code>brain_confirm_ci</code> tool for CI self-calibration</li>
            </ul>
            <h3>0.3.0</h3>
            <ul>
              <li>New <b>"Set Up AI Files"</b> action: one-click MCP config, agent instructions (CLAUDE.md, AGENTS.md, copilot-instructions), and a git post-commit learning hook</li>
              <li>New <b>"Run Brain Doctor"</b> action: diagnoses API key, instance ID, and network connectivity with a jump to settings</li>
              <li>JSONC-tolerant <code>.mcp.json</code> merge that preserves your other MCP servers</li>
            </ul>
            <h3>0.2.2</h3>
            <ul>
              <li>Team Brain awareness: lesson count with author attribution in Brain Health dialog</li>
              <li>brain_doctor: IQ Boost % and Crystal freshness in Brain Health panel</li>
              <li>💎 Memory Crystal indicator in status bar when Crystal is loaded</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li>Added dedicated &quot;Show Lessons&quot; action (Tools menu) with full what-worked content and scrollable list</li>
              <li>Lessons viewer shows all lessons with severity, recall count, and date</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>Initial release</li>
              <li>Status bar brain health widget</li>
              <li>Brain health overview with recall stats and token savings</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "252.*"
        }
        vendor {
            name = "Cachly"
            email = "support@cachly.dev"
            url = "https://cachly.dev"
        }
    }

    // Signing is optional. Only configure it when the certificate is provided via
    // env vars (e.g. in CI), otherwise `publishPlugin` would require non-existent files.
    if (providers.environmentVariable("CERTIFICATE_CHAIN").isPresent) {
        signing {
            certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
            privateKey = providers.environmentVariable("PRIVATE_KEY")
            password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    // IntelliJ 2024.1 (sinceBuild 241) bundles JBR 17, so the plugin must
    // target Java 17 — compiling to 21 made CachlySettingsConfigurable throw
    // UnsupportedClassVersionError (class file 65.0) at runtime on 2024.1,
    // crashing the Settings panel. 17 runs across the whole 241–252 range.
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
