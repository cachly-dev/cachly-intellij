plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.cachly"
version = "0.3.1"

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
              <li><b>Status bar widget</b> &mdash; live lesson count, brain health, and estimated tokens saved.</li>
              <li><b>Lesson viewer</b> &mdash; browse every learned lesson with recall counts and severity.</li>
            </ul>
            <p>Works with the Cachly MCP server. Free tier forever &middot; GDPR &middot; EU servers.</p>
        """.trimIndent()
        changeNotes = """
            <h3>0.3.1</h3>
            <ul>
              <li>Version bump to align with MCP server 0.10.103</li>
              <li><b>brain_confirm_ci</b> now available — CI self-calibration closes the feedback loop automatically</li>
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
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}
