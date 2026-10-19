package dev.cachly.brain

import java.io.File

/**
 * Writes the Cachly onboarding files for a project, mirroring the VS Code
 * extension (sdk/vscode) and the MCP autopilot (sdk/mcp). Idempotent and
 * non-destructive: instruction files use HTML markers so only our section is
 * replaced; .mcp.json merges the cachly server while preserving others.
 *
 * Files written into the project root (or its git root):
 *  - .mcp.json, .junie/mcp/mcp.json, .junie/guidelines.md
 *  - CLAUDE.md, AGENTS.md, .github/copilot-instructions.md,
 *    .windsurfrules, .clinerules  (same marked brain block)
 *  - .cursor/rules/cachly.mdc  (frontmatter + brain block, alwaysApply)
 *  - .git/hooks/post-commit  (CLS — Continuous Learning Stream)
 *
 * Cross-Harness Tier A — see docs/make_cachly_great_again.md §6.7.
 */
object CachlyInstructionWriter {

    internal const val BRAIN_START = "<!-- cachly-brain-start -->"
    internal const val BRAIN_END = "<!-- cachly-brain-end -->"
    private const val CI_BRAIN_MARKER = "cachly-brain"

    /**
     * Was mit dem Schluessel im Heimordner geschah. In KEINEM Fall steht er danach in einer
     * Projektdatei.
     */
    enum class HeimAblage {
        /** Neu in ~/.cachly/credentials.json geschrieben. */
        GESCHRIEBEN,
        /** Lag schon so dort. */
        UNVERAENDERT,
        /** Dort liegt ein ANDERER Schluessel; nichts wurde veraendert. Der Mensch entscheidet. */
        KONFLIKT,
        /** Nicht schreibbar (z. B. Heimordner gesperrt). */
        FEHLER,
    }

    /**
     * [bereinigt]: Projektdateien, aus denen ein Schluessel entfernt wurde, den eine
     * aeltere Fassung (bis 0.8.6) dort abgelegt hatte.
     * [nochImProjekt]: Projektdateien, die einen solchen Schluessel noch tragen und deshalb
     * nicht angefasst wurden, weil er (noch) nicht im Heimordner liegt. Wird er dort
     * abgelegt (siehe [HeimAblage.KONFLIKT]), raeumt ein zweiter Lauf von [writeAll] auf.
     */
    data class Result(
        val written: List<String>,
        val skipped: List<String>,
        val heim: HeimAblage = HeimAblage.UNVERAENDERT,
        val bereinigt: List<String> = emptyList(),
        val nochImProjekt: List<String> = emptyList(),
    )

    /** Walk up from [start] to find the directory containing a `.git` entry. */
    fun findGitRoot(start: File): File? {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** Ein echter Schluessel: nicht leer, kein Platzhalter wie `${user_config.api_key}` oder YOUR_API_KEY. */
    internal fun echterSchluessel(v: Any?): String? {
        val k = (v as? String)?.trim() ?: return null
        if (k.isEmpty() || k == "YOUR_API_KEY" || k.contains('←') || Regex("""^\$\{[^}]*}$""").matches(k)) return null
        return k
    }

    private fun credentialsDatei(heim: File) = File(heim, ".cachly/credentials.json")

    private fun leseCredentials(heim: File): MutableMap<String, Any?>? = runCatching {
        val datei = credentialsDatei(heim)
        if (datei.exists()) parseJsoncObject(datei.readText().trimStart(Char(0xFEFF))) else null
    }.getOrNull()

    /** Der Schluessel, der im Heimordner liegt, oder null. Wirft nie. */
    internal fun leseHeimSchluessel(heim: File): String? = echterSchluessel(leseCredentials(heim)?.get("apiKey"))

    /**
     * Legt den Schluessel in `<heim>/.cachly/credentials.json` ab — dieselbe
     * Datei und dasselbe Format ({"apiKey", "instanceId"}), aus der der
     * MCP-Server ihn liest (sdk/mcp/src/credentials.ts, resolveApiKey: nach
     * CACHLY_JWT und vor Projektdateien).
     *
     * Warum (11.10.2026): Bis 0.8.3 stand CACHLY_JWT im Klartext in .mcp.json
     * und .junie/mcp/mcp.json IM PROJEKT — Dateien, die oft mit committet
     * werden. Der MCP-Server haelt den Schluessel seit GROW-033 aus
     * Projektdateien heraus; das Plugin zog nicht nach.
     *
     * Liegt dort schon ein ANDERER Schluessel, bleibt er stehen und es kommt
     * [HeimAblage.KONFLIKT] zurueck: Der Server wuerde sonst mit dem falschen
     * Konto sprechen. Bis 0.8.6 schrieb writeAll den Schluessel dann "wie
     * bisher" ins Projekt — genau die Datei, die `git add .` einsammelt
     * (12.10.2026). Jetzt kommt er auch dann NICHT ins Projekt; der Mensch
     * entscheidet (SetupBrainAction), und mit [ersetzen] = true wird der
     * gespeicherte Schluessel ersetzt.
     */
    internal fun legeSchluesselImHeimAb(
        apiKey: String,
        instanceId: String,
        heim: File,
        ersetzen: Boolean = false,
    ): HeimAblage = runCatching {
        val key = echterSchluessel(apiKey) ?: return@runCatching HeimAblage.FEHLER
        val datei = credentialsDatei(heim)
        val alt = leseCredentials(heim)
        val altKey = echterSchluessel(alt?.get("apiKey"))
        val instanz = instanceId.trim().takeIf { it.isNotEmpty() }

        val inhalt: Map<String, Any?> = if (altKey == key) {
            // Derselbe Schluessel: nur die Instanz nachtragen, wenn sie fehlt. Eine vorhandene
            // bleibt — sie gehoert zu diesem Konto.
            if (instanz == null || echterSchluessel(alt?.get("instanceId")) != null) return@runCatching HeimAblage.UNVERAENDERT
            LinkedHashMap(alt).apply { put("apiKey", key); put("instanceId", instanz) }
        } else {
            if (altKey != null && !ersetzen) return@runCatching HeimAblage.KONFLIKT
            // Neuer Schluessel: eine alte Instanz daneben waere eine falsche Paarung (sie kann zu
            // einem anderen Konto gehoeren) — wie saveApiKey im MCP-Server.
            linkedMapOf<String, Any?>("apiKey" to key).apply { if (instanz != null) put("instanceId", instanz) }
        }
        datei.parentFile.mkdirs()
        val tmp = File(datei.parentFile, "credentials.json.tmp")
        tmp.writeText(toJson(inhalt, 0))
        // Nur fuer den Besitzer lesbar (wie chmod 0600 im MCP-Server; unter Windows best effort).
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        java.nio.file.Files.move(
            tmp.toPath(), datei.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        HeimAblage.GESCHRIEBEN
    }.getOrDefault(HeimAblage.FEHLER)

    /** Wie [legeSchluesselImHeimAb], nur ja/nein: Liegt der Schluessel jetzt im Heimordner? (AiAssistantAnbindung) */
    internal fun speichereSchluesselImHeim(apiKey: String, instanceId: String, heim: File): Boolean =
        legeSchluesselImHeimAb(apiKey, instanceId, heim).let { it == HeimAblage.GESCHRIEBEN || it == HeimAblage.UNVERAENDERT }

    /** Der Schluessel, den eine aeltere Fassung in den cachly-Eintrag einer MCP-Datei geschrieben hat. */
    private fun schluesselImMcpEintrag(config: Map<String, Any?>): String? {
        val eintrag = (config["mcpServers"] as? Map<*, *>)?.get("cachly") as? Map<*, *>
        return echterSchluessel((eintrag?.get("env") as? Map<*, *>)?.get("CACHLY_JWT"))
    }

    private val HOOK_SCHLUESSEL = Regex("""process\.env\.CACHLY_JWT = '((?:[^'\\]|\\.)*)'""")
    private val HOOK_ESCAPE = Regex("""\\(.)""")

    /** Der Schluessel, den eine aeltere Fassung in ein Hook-Skript geschrieben hat (`process.env.CACHLY_JWT = '...'`). */
    internal fun schluesselImHookSkript(text: String): String? =
        echterSchluessel(HOOK_SCHLUESSEL.find(text)?.groupValues?.get(1)?.let { raw -> HOOK_ESCAPE.replace(raw) { it.groupValues[1] } })

    fun writeAll(
        projectPath: String,
        baseUrl: String,
        apiKey: String,
        instanceId: String,
        heim: File = File(System.getProperty("user.home")),
    ): Result {
        val start = File(projectPath)
        val root = findGitRoot(start) ?: start
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val bereinigt = mutableListOf<String>()
        val nochImProjekt = mutableListOf<String>()
        // Der Schluessel kommt in den Heimordner und NIE ins Projekt — auch nicht, wenn dort ein
        // anderer Schluessel liegt oder der Heimordner nicht beschreibbar ist (Ergebnis KONFLIKT / FEHLER;
        // dann fragt SetupBrainAction). Altbestand (Schluessel aus 0.8.6 und frueher) wird nur
        // entfernt, wenn er jetzt im Heimordner liegt: so geht nichts verloren.
        val ablage = legeSchluesselImHeimAb(apiKey, instanceId, heim)
        val heimSchluessel = leseHeimSchluessel(heim)
        fun darfRaus(alt: String) = alt == heimSchluessel

        // 1. MCP server configs — merge the cachly server, preserve everything else.
        //    .mcp.json                is read by Claude Code.
        //    .junie/mcp/mcp.json      is read by Junie in JetBrains IDEs (project
        //                             scope, same "mcpServers" format; JetBrains
        //                             docs: junie.jetbrains.com/docs/junie-cli-mcp-configuration.html).
        //    Until 0.8.3 only the first was written, so Junie — the agent that
        //    ships with the IDE this plugin runs in — never saw the Brain.
        //    JetBrains AI Assistant reads MCP servers from its settings only, not
        //    from a project file; /docs/jetbrains says how to paste the block there.
        for (rel in listOf(".mcp.json", ".junie/mcp/mcp.json")) {
            runCatching {
                val mcpFile = File(root, rel)
                mcpFile.parentFile?.mkdirs()
                val existing = if (mcpFile.exists()) parseJsoncObject(mcpFile.readText()) else mutableMapOf()
                val altSchluessel = schluesselImMcpEintrag(existing)
                if (altSchluessel != null && !darfRaus(altSchluessel)) {
                    // Der Schluessel liegt (noch) nicht im Heimordner: Datei in Ruhe lassen, sonst ginge er verloren.
                    nochImProjekt.add(rel)
                    return@runCatching
                }
                @Suppress("UNCHECKED_CAST")
                val servers = (existing["mcpServers"] as? MutableMap<String, Any?>) ?: mutableMapOf()
                servers["cachly"] = linkedMapOf(
                    "command" to "npx",
                    "args" to listOf("-y", "@cachly-dev/mcp-server@latest"),
                    // Adresse und Instanz, nie der Schluessel: der Server findet ihn selbst
                    // (resolveApiKey: Umgebung, dann ~/.cachly/credentials.json).
                    "env" to linkedMapOf<String, String>().apply {
                        put("CACHLY_API_URL", baseUrl)
                        put("CACHLY_BRAIN_INSTANCE_ID", instanceId)
                    },
                )
                existing["mcpServers"] = servers
                mcpFile.writeText(toJson(existing, 0))
                written.add(rel)
                if (altSchluessel != null) bereinigt.add(rel)
            }.onFailure { skipped.add(rel) }
        }

        // 2. Instruction/rules files — same marked brain block in each. CLAUDE.md
        //    (Claude Code), AGENTS.md (Codex/agents), copilot-instructions.md
        //    (Copilot), .windsurfrules (Windsurf), .clinerules (Cline) are shared
        //    root files → append/replace, never clobber the user's content.
        val block = "$BRAIN_START\n${buildBrainBlock(instanceId)}$BRAIN_END"
        File(root, ".github").mkdirs()
        // JetBrains AI Assistant (11.10.2026): keine Hooks, aber Projektregeln in
        // .aiassistant/rules/*.md. Laut Hilfe 2026.2 werden sie "by default ...
        // automatically added to each chat session" — die Datei wirkt ohne Klick.
        File(root, ".aiassistant/rules").mkdirs()
        val targets = listOf(
            File(root, "CLAUDE.md"),
            File(root, "AGENTS.md"),
            File(root, ".github/copilot-instructions.md"),
            File(root, ".windsurfrules"),
            File(root, ".clinerules"),
            File(root, ".aiassistant/rules/cachly.md"),
        ) + listOfNotNull(
            // Junie liest .junie/guidelines.md (wie die MCP-Einrichtung): nur wo .junie/ da ist.
            // Fremder Text bleibt, unser Block wird angehaengt oder ersetzt.
            File(root, ".junie/guidelines.md").takeIf { File(root, ".junie").isDirectory },
        )
        for (file in targets) {
            runCatching {
                val existing = if (file.exists()) file.readText() else ""
                val updated = when {
                    existing.contains(BRAIN_START) && existing.contains(BRAIN_END) -> {
                        val before = existing.substring(0, existing.indexOf(BRAIN_START))
                        val after = existing.substring(existing.indexOf(BRAIN_END) + BRAIN_END.length)
                        before + block + after
                    }
                    existing.isNotBlank() -> existing.trimEnd() + "\n\n" + block + "\n"
                    else -> block + "\n"
                }
                file.writeText(updated)
                written.add(file.relativeTo(root).path.replace('\\', '/'))
            }.onFailure { skipped.add(file.name) }
        }

        // 2b. Cursor: .cursor/rules/cachly.mdc — needs YAML frontmatter (alwaysApply)
        //     so the block is active every request. Frontmatter sits outside the
        //     brain markers, so idempotent marker-replacement leaves it intact.
        runCatching {
            val cursorDir = File(root, ".cursor/rules").apply { mkdirs() }
            val mdc = File(cursorDir, "cachly.mdc")
            val frontmatter = "---\n" +
                "description: Cachly AI Brain — persistent memory protocol (recall before tasks, learn after fixes)\n" +
                "alwaysApply: true\n" +
                "---\n\n"
            val existing = if (mdc.exists()) mdc.readText() else ""
            val updated = when {
                existing.contains(BRAIN_START) && existing.contains(BRAIN_END) -> {
                    val before = existing.substring(0, existing.indexOf(BRAIN_START))
                    val after = existing.substring(existing.indexOf(BRAIN_END) + BRAIN_END.length)
                    before + block + after
                }
                existing.isNotBlank() -> existing.trimEnd() + "\n\n" + block + "\n"
                else -> frontmatter + block + "\n"
            }
            mdc.writeText(updated)
            written.add(".cursor/rules/cachly.mdc")
        }.onFailure { skipped.add(".cursor/rules/cachly.mdc") }

        // 3. CLS git post-commit hook
        runCatching {
            val gitDir = File(root, ".git")
            if (gitDir.isDirectory) {
                val hooks = File(gitDir, "hooks").apply { mkdirs() }
                val hook = File(hooks, "post-commit")
                val existing = if (hook.exists()) hook.readText() else ""
                if (existing.contains("cachly CLS")) {
                    skipped.add(".git/hooks/post-commit")
                } else {
                    val script = buildClsHook(instanceId)
                    if (existing.isNotBlank()) {
                        hook.writeText(existing.trimEnd() + "\n\n" + script + "\n")
                    } else {
                        hook.writeText(script + "\n")
                        runCatching { hook.setExecutable(true, false) }
                    }
                    written.add(".git/hooks/post-commit")
                }
            }
        }.onFailure { skipped.add(".git/hooks/post-commit") }

        // 3b. Ambient Recall hooks — .claude/hooks/*.mjs + settings.json merge.
        // Kept in sync with sdk/mcp/src/ambient-hooks.ts (cachly-dev/cachly): users
        // who run Claude Code in the IDE terminal get push-based memory (session
        // briefing, per-prompt recall, file briefing, auto-learn) automatically.
        runCatching {
            val hookDir = File(root, ".claude/hooks").apply { mkdirs() }
            val events = linkedMapOf(
                "SessionStart" to "cachly-ambient-session-start.mjs",
                "UserPromptSubmit" to "cachly-ambient-prompt-submit.mjs",
                "PreToolUse" to "cachly-ambient-pre-tool.mjs",
                "Stop" to "cachly-ambient-stop.mjs",
            )
            val timeouts = mapOf("SessionStart" to 30, "UserPromptSubmit" to 10, "PreToolUse" to 10, "Stop" to 60)
            var changedAny = false
            val scriptPaths = linkedMapOf<String, String>()
            for ((event, name) in events) {
                val f = File(hookDir, name)
                scriptPaths[event] = f.absolutePath.replace('\\', '/')
                val altSchluessel = if (f.exists()) schluesselImHookSkript(f.readText()) else null
                if (altSchluessel != null && !darfRaus(altSchluessel)) {
                    // Wie bei .mcp.json: ein Skript mit einem Schluessel, der nicht im Heimordner liegt, bleibt stehen.
                    nochImProjekt.add(".claude/hooks/$name")
                    continue
                }
                // Nie ein Schluessel im Skript: ambient-recall liest ihn aus der Umgebung oder
                // aus ~/.cachly/credentials.json.
                val script = buildAmbientHook(event, instanceId) + "\n"
                if (!f.exists() || f.readText() != script) {
                    f.writeText(script)
                    runCatching { f.setExecutable(true, false) }
                    changedAny = true
                    if (altSchluessel != null) bereinigt.add(".claude/hooks/$name")
                }
            }

            val settingsFile = File(root, ".claude/settings.json")
            val before = if (settingsFile.exists()) settingsFile.readText() else ""
            val settings = if (before.isNotBlank()) parseJsoncObject(before) else mutableMapOf()
            @Suppress("UNCHECKED_CAST")
            val hooksMap = (settings["hooks"] as? MutableMap<String, Any?>) ?: mutableMapOf()
            for ((event, scriptPath) in scriptPaths) {
                // Upgrade-safe: drop any prior cachly-ambient group, keep foreign hooks.
                val groups = ((hooksMap[event] as? List<Any?>) ?: emptyList()).filterNot { g ->
                    val hooks = ((g as? Map<*, *>)?.get("hooks") as? List<*>) ?: emptyList<Any?>()
                    hooks.isNotEmpty() && hooks.all { h ->
                        (((h as? Map<*, *>)?.get("command")) as? String ?: "").contains(".claude/hooks/cachly-ambient-")
                    }
                }.toMutableList()
                val entry = linkedMapOf<String, Any?>()
                if (event == "PreToolUse") entry["matcher"] = "Edit|Write|MultiEdit|NotebookEdit"
                // v3: `node "<script>"` — one command string that /bin/sh (macOS/
                // Linux, Windows+Git-Bash) and PowerShell (native Windows) run alike.
                entry["hooks"] = listOf(
                    linkedMapOf("type" to "command", "command" to "node \"$scriptPath\"", "timeout" to timeouts[event]),
                )
                groups.add(entry)
                hooksMap[event] = groups
            }
            settings["hooks"] = hooksMap
            val after = toJson(settings, 0)
            if (after != before) {
                settingsFile.parentFile.mkdirs()
                settingsFile.writeText(after)
                changedAny = true
            }
            if (changedAny) written.add(".claude/hooks (Ambient Recall)") else skipped.add(".claude/hooks (Ambient Recall)")
        }.onFailure { skipped.add(".claude/hooks (Ambient Recall)") }

        // 4. CI scaffold — auto-detect GitHub vs GitLab and write the matching config.
        runCatching {
            when (detectGitRemoteHost(root)) {
                "github" -> {
                    val wf = File(root, ".github/workflows/cachly.yml")
                    if (wf.exists()) {
                        skipped.add(".github/workflows/cachly.yml")
                    } else {
                        wf.parentFile.mkdirs()
                        wf.writeText(buildGithubWorkflow(instanceId))
                        written.add(".github/workflows/cachly.yml")
                    }
                }
                "gitlab" -> {
                    val ci = File(root, ".gitlab-ci.yml")
                    val existing = if (ci.exists()) ci.readText() else ""
                    if (existing.contains(CI_BRAIN_MARKER)) {
                        skipped.add(".gitlab-ci.yml")
                    } else {
                        val block = buildGitlabInclude(instanceId)
                        ci.writeText(if (existing.isNotBlank()) existing.trimEnd() + "\n\n" + block else block)
                        written.add(".gitlab-ci.yml")
                    }
                }
                else -> { /* no recognizable remote — write nothing */ }
            }
        }.onFailure { skipped.add("ci-config") }

        return Result(written, skipped, ablage, bereinigt, nochImProjekt)
    }

    /** Detect whether origin points at GitHub or GitLab by reading .git/config. */
    fun detectGitRemoteHost(root: File): String? {
        val cfg = File(root, ".git/config")
        if (!cfg.isFile) return null
        val text = runCatching { cfg.readText().lowercase() }.getOrNull() ?: return null
        val urls = Regex("""url\s*=\s*(.+)""").findAll(text).map { it.groupValues[1] }.toList()
        return when {
            urls.any { it.contains("gitlab") } -> "gitlab"
            urls.any { it.contains("github") } -> "github"
            else -> null
        }
    }

    private fun buildGithubWorkflow(instanceId: String): String = """
        # $CI_BRAIN_MARKER: auto-generated by the Cachly Brain IntelliJ plugin.
        # Learns from merged commits and predicts PR failures. Set CACHLY_API_KEY in
        # your repo secrets (Settings -> Secrets and variables -> Actions).
        name: Cachly Brain

        on:
          push:
            branches: [main]
          pull_request:

        jobs:
          learn:
            if: github.event_name == 'push'
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
                with:
                  fetch-depth: 20
              - uses: cachly-dev/cachly-action@main
                with:
                  mode: learn
                  api-key: ${'$'}{{ secrets.CACHLY_API_KEY }}
                  instance-id: $instanceId

          scan:
            if: github.event_name == 'pull_request'
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - uses: cachly-dev/cachly-action@main
                with:
                  mode: scan
                  api-key: ${'$'}{{ secrets.CACHLY_API_KEY }}
                  instance-id: $instanceId
                  pr-number: ${'$'}{{ github.event.number }}
                  pr-title: ${'$'}{{ github.event.pull_request.title }}
                  pr-body: ${'$'}{{ github.event.pull_request.body }}
    """.trimIndent() + "\n"

    private fun buildGitlabInclude(instanceId: String): String = """
        # $CI_BRAIN_MARKER: auto-generated by the Cachly Brain IntelliJ plugin.
        # Set CACHLY_API_KEY (masked) in Settings -> CI/CD -> Variables.
        include:
          - remote: 'https://raw.githubusercontent.com/cachly-dev/cachly-action/main/templates/cachly.gitlab-ci.yml'

        variables:
          CACHLY_INSTANCE_ID: "$instanceId"

        cachly-learn:
          extends: .cachly_learn
          rules:
            - if: '${'$'}CI_COMMIT_BRANCH == ${'$'}CI_DEFAULT_BRANCH'

        cachly-scan:
          extends: .cachly_scan
          rules:
            - if: '${'$'}CI_PIPELINE_SOURCE == "merge_request_event"'
    """.trimIndent() + "\n"

    private fun buildClsHook(instanceId: String): String = listOf(
        "#!/bin/sh",
        "# cachly CLS — Continuous Learning Stream (installed by Cachly IntelliJ plugin)",
        "# Runs silently on every commit to keep your brain up to date.",
        "CACHLY_INSTANCE=\"$instanceId\"",
        "SHA=\$(git rev-parse HEAD 2>/dev/null || echo \"\")",
        "MSG=\$(git log -1 --pretty=%B 2>/dev/null | head -1 | tr '\"' \"'\" | cut -c1-200)",
        "FILES=\$(git diff-tree --no-commit-id -r --name-only HEAD 2>/dev/null | tr '\\n' ',' | sed 's/,\$//')",
        "node -e \"try{require('child_process').execSync('npx @cachly-dev/mcp-server@latest cls-ingest \\\\''+ JSON.stringify({instance_id:'\$CACHLY_INSTANCE',source:'git_commit',payload:{message:'\$MSG',sha:'\$SHA',files:'\$FILES'.split(',').filter(Boolean)}})+'\\\\'' ,{stdio:'ignore',timeout:5000})}catch(e){}\" 2>/dev/null &",
        "exit 0",
    ).joinToString("\n")

    /**
     * One Ambient Recall hook script. Kept byte-identical with the generators in
     * sdk/mcp/src/ambient-hooks.ts (cachly-dev/cachly) so every installer
     * (MCP init, VS Code, IntelliJ) upgrades the same scripts in place.
     */
    fun buildAmbientHook(event: String, instanceId: String): String {
        // v3: cross-platform Node script — the v1/v2 POSIX shell hooks silently
        // never ran on native Windows (no /bin/sh).
        fun js(v: String) = v.replace("\\", "\\\\").replace("'", "\\'")
        // Kein Schluessel im Skript (12.10.2026): ein Hook-Skript liegt unter .claude/hooks/ im Projekt.
        val lines = mutableListOf(
            "#!/usr/bin/env node",
            "// cachly Ambient Recall — $event v3",
            "// Pushes relevant memory into context automatically. Cross-platform Node hook",
            "// (no shell script — runs identically on Windows/macOS/Linux). Never blocks",
            "// the agent: every failure path exits 0 with no output (graceful degrade).",
            "import { spawn } from 'node:child_process';",
            "process.env.CACHLY_BRAIN_INSTANCE_ID = '${js(instanceId)}';",
        )
        lines.add("process.env.CACHLY_HOOK_EVENT = '${js(event)}';")
        lines.add("try {")
        lines.add("  const child = spawn('npx @cachly-dev/mcp-server@latest ambient-recall', { shell: true, stdio: ['inherit', 'inherit', 'ignore'] });")
        lines.add("  child.on('error', () => process.exit(0));")
        lines.add("  child.on('close', () => process.exit(0));")
        lines.add("} catch {")
        lines.add("  process.exit(0);")
        lines.add("}")
        return lines.joinToString("\n")
    }

    enum class RegelErgebnis { NEU, AKTUALISIERT, GLEICH, FREMD, FEHLER }

    /**
     * Schreibt `.aiassistant/rules/cachly.md` mit demselben Brain-Block wie alle
     * anderen Anweisungsdateien (buildBrainBlock). Eine Datei ohne unsere Marken
     * gehoert jemand anderem und bleibt unberuehrt (FREMD). Eigene Dateien werden
     * nur bei Abweichung neu geschrieben. Wirft nie.
     */
    internal fun schreibeAiAssistantRegel(root: File, instanceId: String): RegelErgebnis = runCatching {
        val datei = File(root, ".aiassistant/rules/cachly.md")
        val block = "$BRAIN_START\n${buildBrainBlock(instanceId)}$BRAIN_END"
        if (datei.exists()) {
            val alt = datei.readText()
            if (!(alt.contains(BRAIN_START) && alt.contains(BRAIN_END))) return@runCatching RegelErgebnis.FREMD
            val neu = alt.substring(0, alt.indexOf(BRAIN_START)) + block +
                alt.substring(alt.indexOf(BRAIN_END) + BRAIN_END.length)
            if (neu == alt) return@runCatching RegelErgebnis.GLEICH
            datei.writeText(neu)
            return@runCatching RegelErgebnis.AKTUALISIERT
        }
        datei.parentFile?.mkdirs()
        datei.writeText(block + "\n")
        RegelErgebnis.NEU
    }.getOrDefault(RegelErgebnis.FEHLER)

    fun buildBrainBlock(instanceId: String): String = """
## Cachly AI Brain — Always Active

The Brain MCP server runs automatically. Instance: `$instanceId`

---

### AT THE START of every new conversation (mandatory — first thing, before anything else):
```
session_start(instance_id="$instanceId", workspace_path="<absolute path to this project>", focus="<first topic the user mentioned>")
```
Show the full briefing output to the user. If the response says the brain is empty or has 0 lessons, immediately seed it:
```
brain_from_git(instance_id="$instanceId", repo_path="<workspace_path>", limit=100)
```
Then tell the user: "Your brain just learned from your git history. You're now pre-briefed."

### BEFORE every task (mandatory — no exceptions):
```
smart_recall(instance_id="$instanceId", query="<what you are about to do>")
```
If relevant lessons are found, say: "Your brain knows this pattern — [lesson]." Then proceed.

### BEFORE editing any file (mandatory):
```
remember_context(instance_id="$instanceId", key="wip:<filename>", content="EDITING: <file:line> — <goal>", ttl=86400)
```
When done: update content to `"DONE: <file> — <summary>"`.

### AFTER every fix, deploy, or discovery (mandatory):
```
learn_from_attempts(instance_id="$instanceId", topic="<category:keyword>", outcome="success"|"failure",
  what_worked="...", what_failed="...", severity="critical"|"major"|"minor",
  file_paths=["..."], commands=["..."], tags=["..."])
```

### AT THE END of every conversation (mandatory):
```
session_end(instance_id="$instanceId", summary="<one sentence: what was done>", files_changed=["..."])
```

### WHEN debugging any error (mandatory before grepping/reading files):
```
causal_trace(instance_id="$instanceId", problem="<exact error or symptom>")
```
If a known fix exists in the Brain, use it directly. Skip research if Brain has the answer.

### BEFORE any risky operation (deploy, migration, dependency upgrade):
```
brain_predict(instance_id="$instanceId", context="<what you are about to do>")
```
Report predicted failure risks to the user before proceeding.

### Half-finished code rule:
Never commit code that does not compile. Run `tsc --noEmit` / `go build ./...` before every commit.
""".trimStart()

    // ── Minimal JSONC-tolerant parser + JSON writer (object/array/string/number/bool/null) ──

    fun parseJsoncObject(text: String): MutableMap<String, Any?> {
        val parsed = JsoncParser(stripComments(text)).parseValue()
        @Suppress("UNCHECKED_CAST")
        return (parsed as? MutableMap<String, Any?>) ?: mutableMapOf()
    }

    /** Strip // line and /* */ block comments that are outside string literals. */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private class JsoncParser(val s: String) {
        var i = 0
        fun parseValue(): Any? {
            skipWs()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> { i += 4; null }
                else -> parseNumber()
            }
        }
        private fun parseObject(): MutableMap<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            i++ // {
            skipWs()
            if (s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs(); i++ // :
                map[key] = parseValue()
                skipWs()
                if (s[i] == ',') { i++; skipWs(); if (s[i] == '}') { i++; break } }
                else if (s[i] == '}') { i++; break }
            }
            return map
        }
        private fun parseArray(): MutableList<Any?> {
            val list = mutableListOf<Any?>()
            i++ // [
            skipWs()
            if (s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (s[i] == ',') { i++; skipWs(); if (s[i] == ']') { i++; break } }
                else if (s[i] == ']') { i++; break }
            }
            return list
        }
        private fun parseString(): String {
            val sb = StringBuilder()
            i++ // opening "
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++ // closing "
            return sb.toString()
        }
        private fun parseBool(): Boolean {
            return if (s[i] == 't') { i += 4; true } else { i += 5; false }
        }
        private fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val str = s.substring(start, i)
            return if (str.contains('.') || str.contains('e') || str.contains('E')) str.toDouble()
            else str.toLongOrNull() ?: str.toDouble()
        }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }

    internal fun toJson(value: Any?, indent: Int): String {
        val pad = "  ".repeat(indent)
        val padIn = "  ".repeat(indent + 1)
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean -> value.toString()
            is Int, is Long -> value.toString()
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Map<*, *> -> {
                if (value.isEmpty()) "{}"
                else value.entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) ->
                    "$padIn${quote(k.toString())}: ${toJson(v, indent + 1)}"
                }
            }
            is List<*> -> {
                if (value.isEmpty()) "[]"
                else value.joinToString(",\n", "[\n", "\n$pad]") { "$padIn${toJson(it, indent + 1)}" }
            }
            else -> quote(value.toString())
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }
}
