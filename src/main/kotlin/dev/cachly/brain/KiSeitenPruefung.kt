package dev.cachly.brain

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pruefungen fuer die KI-Seite im Brain Doctor (03.11.2026,
 * jetbrains-brain-doctor-passes-without-ai-side). Der Doctor prueft sonst nur
 * IDE -> API und meldet "alles gut", obwohl die KI keine cachly-Werkzeuge hat.
 * Alles hier liest nur lokale Dateien und startet hoechstens `node --version`.
 * Jeder Eintrag der Rueckgabe ist ein Hinweis in einfachen Worten; leer heisst: alles da.
 */
internal object KiSeitenPruefung {

    private val regelDateien = listOf(
        "CLAUDE.md", "AGENTS.md", ".github/copilot-instructions.md", ".windsurfrules",
        ".clinerules", ".aiassistant/rules/cachly.md", ".junie/guidelines.md", ".cursor/rules/cachly.mdc",
    )

    private val mcpDateien = listOf(".mcp.json", ".junie/mcp/mcp.json", ".cursor/mcp.json")

    fun pruefe(projektPfad: String?, ideSchluessel: String): List<String> =
        pruefe(
            projekt = projektPfad?.let { File(it) },
            heim = File(System.getProperty("user.home")),
            ideSchluessel = ideSchluessel,
            nodeVorhanden = ::nodeVorhanden,
        )

    fun pruefe(projekt: File?, heim: File, ideSchluessel: String, nodeVorhanden: () -> Boolean): List<String> {
        val hinweise = mutableListOf<String>()

        if (!nodeVorhanden()) {
            hinweise.add("Node.js not found. The AI tools start the Cachly server with npx and need Node.js 18 or newer.")
        }

        val heimSchluessel = CachlyInstructionWriter.leseHeimSchluessel(heim)
        when {
            heimSchluessel == null ->
                hinweise.add("~/.cachly/credentials.json has no API key. AI tools that start the Cachly server cannot sign in.")
            ideSchluessel.isNotBlank() && heimSchluessel != ideSchluessel ->
                hinweise.add("~/.cachly/credentials.json holds a different API key than this IDE. Your AI tools may use another account.")
        }

        if (projekt != null) {
            val mcpOk = mcpDateien.any { hatCachlyEintrag(File(projekt, it)) }
            if (!mcpOk) hinweise.add("No cachly entry in .mcp.json (or .junie/mcp/mcp.json). Claude Code and Junie will not see the Cachly tools in this project.")

            val regelOk = regelDateien.any { f ->
                runCatching {
                    val t = File(projekt, f).takeIf { it.isFile }?.readText() ?: ""
                    t.contains(CachlyInstructionWriter.BRAIN_START)
                }.getOrDefault(false)
            }
            if (!regelOk) hinweise.add("No Cachly rule block in the project's AI rule files. The AI is not told when to use the Brain.")
        }
        return hinweise
    }

    private fun hatCachlyEintrag(datei: File): Boolean = runCatching {
        if (!datei.isFile) return@runCatching false
        val config = CachlyInstructionWriter.parseJsoncObject(datei.readText().trimStart(Char(0xFEFF)))
        (config["mcpServers"] as? Map<*, *>)?.containsKey("cachly") == true
    }.getOrDefault(false)

    /** `node --version` mit kurzer Frist. Der Pfad der IDE kann kleiner sein als der der Shell; dann meldet es der Doctor als Hinweis. */
    private fun nodeVorhanden(): Boolean = runCatching {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        val fertig = p.waitFor(5, TimeUnit.SECONDS)
        if (!fertig) { p.destroyForcibly(); false } else p.exitValue() == 0
    }.getOrDefault(false)
}
