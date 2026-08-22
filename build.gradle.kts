plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.cachly"
version = "0.8.1"

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
        // MARKETPLACE-TEXT — DIESE ERKLAERUNGEN GEHOEREN HIERHIN, NICHT IN DEN TEXT.
        //
        // Sie standen zuerst als <!-- ... --> im description-String. Das laeuft
        // mit aus: HTML-Kommentare landen im Paket und damit im Quelltext der
        // oeffentlichen Marketplace-Seite. Eine Begruendung fuer uns hat auf einer
        // Verkaufsseite nichts zu suchen.
        //
        // (1) Die Kernaussage ist dieselbe wie auf der Startseite
        //     (web/lib/i18n/landing.ts, hero). Vorher stand hier eine eigene
        //     Fassung. Die Startseite arbeitet mit einem Gegensatz statt mit einer
        //     Klage und nennt drei konkrete Dinge; sie ist die staerkere Aussage,
        //     und wer von cachly.dev kommt, erkennt sie wieder. Eine Kernaussage
        //     an drei Orten in drei Fassungen ist derselbe Fehler wie eine
        //     Versionsnummer an zwei Orten.
        //
        // (2) Die drei Bilder lagen seit dem 11.08.2026 im Repo und waren unter
        //     cachly.dev oeffentlich erreichbar — verlinkt von nirgendwo. 18
        //     Downloads, null Registrierungen, auf einer Verkaufsseite ohne ein
        //     einziges Bild. JetBrains braucht ABSOLUTE Adressen; relative
        //     Repo-Pfade zeigen nichts an. Alle drei am 17.08.2026 mit HTTP 200
        //     und image/png geprueft.
        description = """
            <p><b>ChatGPT and Claude remember your conversations. <a href="https://cachly.dev">cachly</a> remembers your codebase.</b></p>
            <p>The bug you fixed. Why you chose Postgres. The deploy step that always breaks &mdash; and everything
            your teammates learned. It stays when someone leaves the team, and it comes along when you switch
            assistants. This plugin brings that memory into your JetBrains IDE.</p>
            <ul>
              <li><b>One-click setup</b> &mdash; "Set Up AI Files" writes your MCP config, agent instructions, and a git post-commit learning hook. No terminal needed.</li>
              <li><b>Brain Doctor</b> &mdash; diagnoses your connection (API key, instance, network) and points you straight to the fix.</li>
              <li><b>Ambient learning</b> &mdash; detects repeated patterns and offers to save them as reusable lessons.</li>
              <li><b>Proactive briefing</b> &mdash; surfaces your top lessons on project open, so the brain greets you instead of waiting to be asked.</li>
              <li><b>Status bar widget</b> &mdash; live lesson count, brain health, and estimated tokens saved.</li>
              <li><b>Lesson viewer</b> &mdash; browse every learned lesson with recall counts and severity.</li>
            </ul>
            <p><b>Try it without an account.</b> On first start the plugin fetches a free trial brain by itself
            &mdash; no sign-up, no credit card, no key to paste. Link an account later to keep the data.</p>
            <p><img src="https://cachly.dev/screenshots/intellij/shot1-hero.png" alt="Cachly Brain in a JetBrains IDE: status bar with lesson count and the briefing on project open" width="720"></p>
            <p><img src="https://cachly.dev/screenshots/intellij/shot3-lessons.png" alt="Lesson viewer: every learned lesson with recall count and severity" width="720"></p>
            <p><img src="https://cachly.dev/screenshots/intellij/shot2-doctor.png" alt="Brain Doctor: checks API key, instance and network, and names the fix" width="720"></p>
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
            // NICHT recommended(): das fragt bei jedem Lauf einen
            // JetBrains-Webdienst, welche IDEs zu pruefen sind — am
            // 13.08.2026 empfahl der 2025.3, deren ideaIC-Artefakt unter
            // keiner Repository-Adresse aufloesbar war, und der Spiegel-CI
            // (cachly-intellij) war rot, ohne dass sich am Plugin etwas
            // geaendert hatte. Fest gepinnt auf die Grenzen des erklaerten
            // Support-Bereichs (sinceBuild 241, s. Changelog "241-252").
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")

            // ── DIE OBERE KANTE WIRD NICHT GEPRUEFT — und das ist bekannt ──
            //
            // `untilBuild` ist null: Wir behaupten Vertraeglichkeit ohne
            // Obergrenze. Geprueft wird nur bis 2025.2. Genau in dieser Luecke
            // ist Version 0.8.0 haengengeblieben.
            //
            // Der Marketplace-Verifier prueft gegen die NEUESTE Reihe. Sein
            // Lauf vom 21.08.2026 (Verifier 1.408) gegen
            // IntelliJ IDEA 2026.2.2 EAP (262.10315.19) meldete:
            //
            //     Compatible. 1 usage of internal API
            //       PluginManagerCore.getPlugin(PluginId) (1)
            //
            // Unser Lauf gegen 2024.1 und 2025.2 sagte "Compatible" ohne
            // Zusatz — dort ist dieselbe Methode NICHT intern (nachgesehen mit
            // javap: sie traegt in 2025.2 nur @JvmStatic und @Contract).
            //
            // GEMESSEN AM 22.08.2026: Diese Luecke laesst sich hier NICHT
            // schliessen. Drei Schreibweisen versucht, keine loest auf:
            //
            //   ide(..., "2026.2")          Could not find idea:ideaIC:2026.2
            //   ide(..., "262.10315.19")    Could not find idea:ideaIC:262.10315.19
            //   select { channels = EAP }   laeuft durch, fuegt aber NICHTS hinzu
            //                               (weiterhin nur 2 Verifikationen)
            //
            // EAP-Artefakte liegen nicht in den oeffentlichen Verzeichnissen,
            // die `defaultRepositories()` durchsucht.
            //
            // Damit bleibt nur EIN Weg, und der gehoert entschieden:
            // `untilBuild` setzen, damit wir nicht mehr behaupten, als wir
            // pruefen. Siehe .agent/JETBRAINS-ANTWORT-9078085.md.
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
