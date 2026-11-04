package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Die Ambient-Recall-Hooks kommen aus EINER Quelle: sdk/mcp (04.11.2026).
 *
 * Bis 0.9.7 schrieb das Plugin eine eigene Abschrift der Skripte. Sie blieb bei
 * v3 stehen: jeder Prompt startete `npx @cachly-dev/mcp-server@latest
 * ambient-recall`, gemessen 7,7 s warm und 17,1 s kalt bei 10 s Grenze. MCP und
 * VS Code schrieben laengst v5 (Buendel daneben, kein npx). Kein Test bemerkte es.
 *
 * Jetzt liegen Buendel und Skript-Vorlage als Ressourcen im Plugin, geschrieben
 * von sdk/mcp/scripts/plugin-hooks-schreiben.mjs. Der MCP-Waechter
 * (plugin-hooks.test.ts) prueft die Vorlage gegen buildHook; dieser Test prueft
 * die Seite des Plugins.
 */
class HookVorlageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Erfunden: der Vorrat eines echten Brains (Temp-Ordner, je Instanz) bleibt unberuehrt.
    private val instanz = "11111111-2222-3333-4444-5555deadbeef"
    private val events = linkedMapOf(
        "SessionStart" to "cachly-ambient-session-start.mjs",
        "UserPromptSubmit" to "cachly-ambient-prompt-submit.mjs",
        "PreToolUse" to "cachly-ambient-pre-tool.mjs",
        "Stop" to "cachly-ambient-stop.mjs",
    )

    private fun mcpBuendel(): File {
        // Gradle startet Tests im Ordner sdk/intellij; zur Sicherheit nach oben suchen.
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "sdk/mcp/hooks/${CachlyInstructionWriter.HOOK_BUENDEL}")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("sdk/mcp/hooks/${CachlyInstructionWriter.HOOK_BUENDEL} nicht gefunden (Start: ${File("").absolutePath}). Der Gleichheitstest darf nicht still entfallen.")
    }

    @Test
    fun `the bundle in the plugin is byte-identical with sdk_mcp_hooks`() {
        assertTrue(
            "Buendel im Plugin veraltet — cd sdk/mcp && npm run plugin-hooks:write",
            CachlyInstructionWriter.hookRessource(CachlyInstructionWriter.HOOK_BUENDEL).toByteArray(Charsets.UTF_8)
                .contentEquals(mcpBuendel().readBytes()),
        )
    }

    @Test
    fun `every hook script is v5, loads the bundle next to it and never calls npx`() {
        for ((event, _) in events) {
            val skript = CachlyInstructionWriter.buildAmbientHook(event, instanz)
            assertTrue(skript, skript.contains("// cachly Ambient Recall — $event v5"))
            assertTrue(skript.contains("await import('./${CachlyInstructionWriter.HOOK_BUENDEL}');"))
            assertTrue(skript.contains("process.env.CACHLY_BRAIN_INSTANCE_ID = '$instanz';"))
            assertTrue(skript.contains("process.argv.splice(2, process.argv.length, '$event');"))
            assertFalse("npx im Skript", skript.contains("npx"))
            assertFalse("Platzhalter uebrig", skript.contains("__CACHLY_"))
        }
    }

    @Test
    fun `the instance id is escaped like jsString in ambient-hooks_ts`() {
        val skript = CachlyInstructionWriter.buildAmbientHook("Stop", "a'b\\c")
        assertTrue(skript, skript.contains("process.env.CACHLY_BRAIN_INSTANCE_ID = 'a\\'b\\\\c';"))
    }

    @Test
    fun `writeAll puts the bundle next to the scripts and wires them via CLAUDE_PROJECT_DIR`() {
        val projekt = tmp.newFolder("projekt")
        val heim = tmp.newFolder("heim")

        CachlyInstructionWriter.writeAll(projekt.path, "http://127.0.0.1:9", "cky_live_testschluessel_0001", instanz, heim)

        val hooks = File(projekt, ".claude/hooks")
        assertEquals(
            CachlyInstructionWriter.hookRessource(CachlyInstructionWriter.HOOK_BUENDEL),
            File(hooks, CachlyInstructionWriter.HOOK_BUENDEL).readText(),
        )
        val settings = File(projekt, ".claude/settings.json").readText()
        for ((event, name) in events) {
            assertEquals(CachlyInstructionWriter.buildAmbientHook(event, instanz) + "\n", File(hooks, name).readText())
            assertTrue(settings, settings.contains("node \\\"\${CLAUDE_PROJECT_DIR}/.claude/hooks/$name\\\""))
        }
        assertFalse("absoluter Pfad in settings.json", settings.contains(projekt.absolutePath.replace('\\', '/')))
    }

    @Test
    fun `the settings_json hooks equal the MCP template (timeouts, matcher, command)`() {
        val projekt = tmp.newFolder("projekt3")
        val heim = tmp.newFolder("heim3")
        CachlyInstructionWriter.writeAll(projekt.path, "http://127.0.0.1:9", "cky_live_testschluessel_0001", instanz, heim)

        val geschrieben = CachlyInstructionWriter.parseJsoncObject(File(projekt, ".claude/settings.json").readText())["hooks"]
        val soll = CachlyInstructionWriter.parseJsoncObject(CachlyInstructionWriter.hookRessource("cachly-ambient-settings.vorlage.json"))
        assertEquals(soll, geschrieben)
    }

    private fun kann(vararg cmd: String) = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    @Test
    fun `the written prompt hook runs with node and degrades silently when the API is unreachable`() {
        // Wie in ClsHookTest: ohne node wird uebersprungen. Der CI-Job "SDK (JetBrains)" richtet
        // node seit 04.11.2026 ein, damit beide Lauftests dort wirklich laufen.
        assumeTrue("node fehlt", kann("node", "--version"))
        val projekt = tmp.newFolder("projekt2")
        val heim = tmp.newFolder("heim2")
        CachlyInstructionWriter.writeAll(projekt.path, "http://127.0.0.1:9", "cky_live_testschluessel_0001", instanz, heim)

        val pb = ProcessBuilder("node", File(projekt, ".claude/hooks/cachly-ambient-prompt-submit.mjs").absolutePath)
            .directory(projekt)
            .redirectErrorStream(false)
        val env = pb.environment()
        listOf("CACHLY_JWT", "CACHLY_API_KEY", "CACHLY_BRAIN_INSTANCE_ID").forEach { env.remove(it) }
        // Nie Prod, nie ein Konto: die Adresse ist tot, der Heimordner gehoert dem Test.
        env["CACHLY_API_URL"] = "http://127.0.0.1:9"
        env["HOME"] = heim.absolutePath
        env["USERPROFILE"] = heim.absolutePath
        env["CACHLY_LERNEN"] = "0"
        val start = System.nanoTime()
        val p = pb.start()
        p.outputStream.use { it.write("""{"hook_event_name":"UserPromptSubmit","session_id":"s1","prompt":"warum schlaegt der deploy fehl"}""".toByteArray()) }
        val fertig = p.waitFor(20, TimeUnit.SECONDS)
        if (!fertig) p.destroyForcibly()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("Hook lief ueber 20 s", fertig)
        assertEquals("Exit-Code", 0, p.exitValue())
        // Weit unter der 10-s-Grenze von UserPromptSubmit; v3 mit npx brauchte warm 7,7 s.
        assertTrue("$ms ms", ms < 5_000)
    }
}
