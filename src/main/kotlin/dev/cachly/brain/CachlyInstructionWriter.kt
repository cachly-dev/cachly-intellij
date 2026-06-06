package dev.cachly.brain

import java.io.File

/**
 * Writes the Cachly onboarding files for a project, mirroring the VS Code
 * extension (sdk/vscode) and the MCP autopilot (sdk/mcp). Idempotent and
 * non-destructive: instruction files use HTML markers so only our section is
 * replaced; .mcp.json merges the cachly server while preserving others.
 *
 * Files written into the project root (or its git root):
 *  - .mcp.json
 *  - CLAUDE.md, AGENTS.md, .github/copilot-instructions.md  (same brain block)
 *  - .git/hooks/post-commit  (CLS — Continuous Learning Stream)
 */
object CachlyInstructionWriter {

    private const val BRAIN_START = "<!-- cachly-brain-start -->"
    private const val BRAIN_END = "<!-- cachly-brain-end -->"
    private const val CI_BRAIN_MARKER = "cachly-brain"

    data class Result(val written: List<String>, val skipped: List<String>)

    /** Walk up from [start] to find the directory containing a `.git` entry. */
    fun findGitRoot(start: File): File? {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    fun writeAll(projectPath: String, baseUrl: String, apiKey: String, instanceId: String): Result {
        val start = File(projectPath)
        val root = findGitRoot(start) ?: start
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // 1. .mcp.json — merge the cachly server, preserve everything else
        runCatching {
            val mcpFile = File(root, ".mcp.json")
            val existing = if (mcpFile.exists()) parseJsoncObject(mcpFile.readText()) else mutableMapOf()
            @Suppress("UNCHECKED_CAST")
            val servers = (existing["mcpServers"] as? MutableMap<String, Any?>) ?: mutableMapOf()
            servers["cachly"] = linkedMapOf(
                "command" to "npx",
                "args" to listOf("-y", "@cachly-dev/mcp-server@latest"),
                "env" to linkedMapOf(
                    "CACHLY_API_URL" to baseUrl,
                    "CACHLY_JWT" to apiKey,
                    "CACHLY_BRAIN_INSTANCE_ID" to instanceId,
                ),
            )
            existing["mcpServers"] = servers
            mcpFile.writeText(toJson(existing, 0))
            written.add(".mcp.json")
        }.onFailure { skipped.add(".mcp.json") }

        // 2. Instruction files — same marked brain block in all three
        val block = "$BRAIN_START\n${buildBrainBlock(instanceId)}$BRAIN_END"
        File(root, ".github").mkdirs()
        val targets = listOf(
            File(root, "CLAUDE.md"),
            File(root, "AGENTS.md"),
            File(root, ".github/copilot-instructions.md"),
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

        return Result(written, skipped)
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

    fun buildBrainBlock(instanceId: String): String = """
## Cachly AI Brain — Always Active

This project uses the Cachly AI Brain for persistent memory across sessions.
Instance: `$instanceId`. The Brain MCP server should be running (see `.mcp.json`).

### AT THE START of every new conversation (mandatory — before anything else):
```
session_start(instance_id="$instanceId", workspace_path="<absolute path to this project>", focus="<first topic the user mentioned>")
```
Show the briefing to the user. If the Brain is empty, seed it:
```
brain_from_git(instance_id="$instanceId", repo_path="<workspace_path>", limit=100)
```

### BEFORE every task (mandatory):
```
smart_recall(instance_id="$instanceId", query="<what you are about to do>")
```
If relevant lessons are found, use them before researching.

### BEFORE editing any file (mandatory):
```
remember_context(instance_id="$instanceId", key="wip:<filename>", content="EDITING: <file:line> — <goal>", ttl=86400)
```

### AFTER every fix, deploy, or discovery (mandatory):
```
learn_from_attempts(instance_id="$instanceId", topic="<category:keyword>", outcome="success"|"failure",
  what_worked="...", what_failed="...", severity="critical"|"major"|"minor",
  file_paths=["..."], commands=["..."], tags=["..."])
```

### WHEN debugging any error (before grepping/reading files):
```
causal_trace(instance_id="$instanceId", problem="<exact error or symptom>")
```
If the Brain already has a known fix, use it directly.

### AT THE END of every conversation (mandatory):
```
session_end(instance_id="$instanceId", summary="<one sentence: what was done>", files_changed=["..."])
```

### Half-finished code rule:
Never commit code that does not compile. Run `tsc --noEmit` / `go build ./...` before every commit.

### Why this matters
Each recall saves ~1,200 tokens. The Brain survives IDE restarts, team switches, and model upgrades.
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

    private fun toJson(value: Any?, indent: Int): String {
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
