plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.cachly"
version = "0.7.2"

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

/**
 * Reads the topmost `## [x.y.z]` section of CHANGELOG.md and turns it into the
 * simple HTML the JetBrains Marketplace expects for `changeNotes`, so the notes
 * can no longer drift out of sync with this file the way a hand-copied string did.
 */
fun changeNotesFromChangelog(): String {
    val changelog = file("CHANGELOG.md").readText()
    val header = Regex("""^## \[(.+?)\].*$""", RegexOption.MULTILINE)
    val matches = header.findAll(changelog).toList()
    check(matches.isNotEmpty()) { "CHANGELOG.md has no '## [version]' section to read change notes from." }

    val top = matches[0]
    val entryVersion = top.groupValues[1]
    val bodyStart = top.range.last + 1
    val bodyEnd = if (matches.size > 1) matches[1].range.first else changelog.length
    val body = changelog.substring(bodyStart, bodyEnd)

    val items = mutableListOf<String>()
    var current: StringBuilder? = null
    for (raw in body.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("- ") -> {
                current?.let { items += it.toString() }
                current = StringBuilder(line.removePrefix("- ").trim())
            }
            line.isEmpty() || line.startsWith("#") || line.startsWith("---") -> {
                current?.let { items += it.toString() }
                current = null
            }
            else -> current?.append(' ')?.append(line)
        }
    }
    current?.let { items += it.toString() }

    fun toHtml(text: String): String {
        var html = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("—", "&mdash;")
        html = Regex("""\*\*(.+?)\*\*""").replace(html) { "<b>${it.groupValues[1]}</b>" }
        html = Regex("`([^`]+)`").replace(html) { "<code>${it.groupValues[1]}</code>" }
        return html
    }

    return buildString {
        append("<h3>").append(entryVersion).append("</h3>\n")
        append("<ul>\n")
        items.forEach { append("  <li>").append(toHtml(it)).append("</li>\n") }
        append("</ul>")
    }
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
        changeNotes = changeNotesFromChangelog()
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
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
