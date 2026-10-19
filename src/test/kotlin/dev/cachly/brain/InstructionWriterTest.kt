package dev.cachly.brain

import dev.cachly.brain.CachlyInstructionWriter.HeimAblage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Set Up AI Files" (CachlyInstructionWriter.writeAll):
 *  - 11.10.2026: Der Schluessel gehoert in ~/.cachly/credentials.json, nicht in Dateien im
 *    Projekt, die mit committet werden (.mcp.json, .junie/mcp/mcp.json, .claude/hooks/).
 *    Bis 0.8.3 stand er dort im Klartext.
 *  - 12.10.2026: Auch dann nicht, wenn im Heimordner schon ein ANDERER Schluessel liegt.
 *    Bis 0.8.6 schrieb das Plugin ihn in diesem Fall doch ins Projekt. Jetzt fragt
 *    SetupBrainAction; hier steht, was der Schreiber dafuer liefert.
 *  - JetBrains AI Assistant bekommt eine Projektregel in .aiassistant/rules/.
 */
class InstructionWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val schluessel = "cky_live_testschluessel_0001"
    private val fremd = "cky_live_ein_anderes_konto"
    private val instanz = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"
    private val url = "https://api.cachly.dev"

    private fun projektdateienMitText(projekt: File, text: String): List<String> =
        projekt.walkTopDown()
            .filter { it.isFile && it.readText().contains(text) }
            .map { it.relativeTo(projekt).path.replace('\\', '/') }
            .toList()

    private fun credentials(heim: File): Map<String, Any?> =
        CachlyInstructionWriter.parseJsoncObject(File(heim, ".cachly/credentials.json").readText())

    private fun legeFremdenSchluesselAb(heim: File) {
        val datei = File(heim, ".cachly/credentials.json")
        datei.parentFile.mkdirs()
        datei.writeText("""{"apiKey":"$fremd","instanceId":"x"}""")
    }

    /** Ein Projekt, wie es Version 0.8.6 und frueher hinterlassen hat: der Schluessel steht in den MCP-Dateien und den vier Hook-Skripten. */
    private fun altProjekt(projekt: File, key: String) {
        for (rel in listOf(".mcp.json", ".junie/mcp/mcp.json")) {
            val f = File(projekt, rel)
            f.parentFile.mkdirs()
            f.writeText(
                """
                {
                  "mcpServers": {
                    "andere": { "command": "x" },
                    "cachly": {
                      "command": "npx",
                      "args": ["-y", "@cachly-dev/mcp-server@latest"],
                      "env": { "CACHLY_API_URL": "$url", "CACHLY_JWT": "$key", "CACHLY_BRAIN_INSTANCE_ID": "$instanz" }
                    }
                  }
                }
                """.trimIndent(),
            )
        }
        val hooks = File(projekt, ".claude/hooks").apply { mkdirs() }
        for ((event, name) in HOOKS) {
            val ohne = CachlyInstructionWriter.buildAmbientHook(event, instanz)
            val mit = ohne.replace(
                "process.env.CACHLY_HOOK_EVENT",
                "process.env.CACHLY_JWT = '$key';\nprocess.env.CACHLY_HOOK_EVENT",
            )
            File(hooks, name).writeText(mit + "\n")
        }
    }

    private val HOOKS = linkedMapOf(
        "SessionStart" to "cachly-ambient-session-start.mjs",
        "UserPromptSubmit" to "cachly-ambient-prompt-submit.mjs",
        "PreToolUse" to "cachly-ambient-pre-tool.mjs",
        "Stop" to "cachly-ambient-stop.mjs",
    )

    @Test
    fun `key goes to the home credentials file, not into project files`() {
        val projekt = tmp.newFolder("projekt")
        val heim = tmp.newFolder("heim")

        val r = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        val cred = File(heim, ".cachly/credentials.json")
        assertTrue("credentials.json fehlt", cred.exists())
        val inhalt = credentials(heim)
        assertEquals(schluessel, inhalt["apiKey"])
        assertEquals(instanz, inhalt["instanceId"])
        assertEquals(HeimAblage.GESCHRIEBEN, r.heim)
        assertEquals("Schluessel steht in Projektdateien", emptyList<String>(), projektdateienMitText(projekt, schluessel))
        assertEquals("CACHLY_JWT steht in Projektdateien", emptyList<String>(), projektdateienMitText(projekt, "CACHLY_JWT"))
        assertTrue(File(projekt, ".mcp.json").readText().contains(instanz))
    }

    @Test
    fun `a different key at home stays, and NO key lands in the project either`() {
        val projekt = tmp.newFolder("projekt2")
        val heim = tmp.newFolder("heim2")
        legeFremdenSchluesselAb(heim)

        val r = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        assertEquals(HeimAblage.KONFLIKT, r.heim)
        assertTrue(File(heim, ".cachly/credentials.json").readText().contains(fremd))
        assertEquals("der eigene Schluessel steht im Projekt", emptyList<String>(), projektdateienMitText(projekt, schluessel))
        assertEquals("der fremde Schluessel steht im Projekt", emptyList<String>(), projektdateienMitText(projekt, fremd))
        assertEquals("CACHLY_JWT steht im Projekt", emptyList<String>(), projektdateienMitText(projekt, "CACHLY_JWT"))
        // Die Dateien sind trotzdem da und tragen Adresse und Instanz.
        assertTrue(File(projekt, ".mcp.json").readText().contains(instanz))
        assertTrue(File(projekt, ".claude/hooks/cachly-ambient-session-start.mjs").exists())
    }

    @Test
    fun `replacing the stored key is possible on request and still leaves the project clean`() {
        val projekt = tmp.newFolder("projekt2b")
        val heim = tmp.newFolder("heim2b")
        legeFremdenSchluesselAb(heim)

        assertEquals(HeimAblage.KONFLIKT, CachlyInstructionWriter.legeSchluesselImHeimAb(schluessel, instanz, heim))
        assertTrue(File(heim, ".cachly/credentials.json").readText().contains(fremd))

        assertEquals(HeimAblage.GESCHRIEBEN, CachlyInstructionWriter.legeSchluesselImHeimAb(schluessel, instanz, heim, ersetzen = true))
        assertEquals(mapOf("apiKey" to schluessel, "instanceId" to instanz), credentials(heim))

        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        assertEquals(emptyList<String>(), projektdateienMitText(projekt, schluessel))
    }

    @Test
    fun `an unwritable home directory stores nothing and still leaves the project clean`() {
        val projekt = tmp.newFolder("projekt2c")
        val heim = tmp.newFile("heim-ist-eine-datei")

        val r = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        assertEquals(HeimAblage.FEHLER, r.heim)
        assertEquals(emptyList<String>(), projektdateienMitText(projekt, schluessel))
        assertEquals(emptyList<String>(), projektdateienMitText(projekt, "CACHLY_JWT"))
    }

    @Test
    fun `the same key is not rewritten, a missing instance is added, an existing one stays`() {
        val heim = tmp.newFolder("heim2d")
        assertEquals(HeimAblage.GESCHRIEBEN, CachlyInstructionWriter.legeSchluesselImHeimAb(schluessel, "", heim))
        assertNull(credentials(heim)["instanceId"])

        assertEquals(HeimAblage.GESCHRIEBEN, CachlyInstructionWriter.legeSchluesselImHeimAb(schluessel, instanz, heim))
        assertEquals(instanz, credentials(heim)["instanceId"])

        assertEquals(HeimAblage.UNVERAENDERT, CachlyInstructionWriter.legeSchluesselImHeimAb(schluessel, "andere", heim))
        assertEquals(instanz, credentials(heim)["instanceId"])
        assertEquals(listOf("credentials.json"), File(heim, ".cachly").list()!!.toList())
    }

    @Test
    fun `placeholders and empty keys are never stored`() {
        val heim = tmp.newFolder("heim2e")
        for (schlecht in listOf("", "  ", "YOUR_API_KEY", "\${user_config.api_key}")) {
            assertEquals(HeimAblage.FEHLER, CachlyInstructionWriter.legeSchluesselImHeimAb(schlecht, instanz, heim))
        }
        assertFalse(File(heim, ".cachly").exists())
    }

    @Test
    fun `the Ambient Recall hook scripts carry no key`() {
        for ((event, _) in HOOKS) {
            val skript = CachlyInstructionWriter.buildAmbientHook(event, instanz)
            assertFalse(skript.contains("CACHLY_JWT"))
            assertTrue(skript.contains(instanz))
        }
    }

    @Test
    fun `a project from 0_8_6 is cleaned once the key is at home, and the files say what was removed`() {
        val projekt = tmp.newFolder("projekt4")
        val heim = tmp.newFolder("heim4")
        altProjekt(projekt, schluessel)
        assertEquals(6, projektdateienMitText(projekt, schluessel).size)

        val r = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        assertEquals(emptyList<String>(), projektdateienMitText(projekt, schluessel))
        assertEquals(schluessel, credentials(heim)["apiKey"])
        assertEquals(
            listOf(
                ".mcp.json", ".junie/mcp/mcp.json",
                ".claude/hooks/cachly-ambient-session-start.mjs", ".claude/hooks/cachly-ambient-prompt-submit.mjs",
                ".claude/hooks/cachly-ambient-pre-tool.mjs", ".claude/hooks/cachly-ambient-stop.mjs",
            ),
            r.bereinigt,
        )
        assertEquals(emptyList<String>(), r.nochImProjekt)
        // Der fremde Server in der Datei bleibt.
        assertTrue(File(projekt, ".mcp.json").readText().contains("\"andere\""))
    }

    @Test
    fun `with a different key at home the old project key is left alone until the human decides, then cleaned`() {
        val projekt = tmp.newFolder("projekt5")
        val heim = tmp.newFolder("heim5")
        legeFremdenSchluesselAb(heim)
        altProjekt(projekt, schluessel)

        val erst = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        assertEquals(HeimAblage.KONFLIKT, erst.heim)
        assertEquals(emptyList<String>(), erst.bereinigt)
        assertEquals(6, erst.nochImProjekt.size)
        assertEquals("nichts darf verloren gehen", 6, projektdateienMitText(projekt, schluessel).size)

        // Der Mensch waehlt "Use this key for all projects".
        assertEquals(HeimAblage.GESCHRIEBEN, CachlyInstructionWriter.legeSchluesselImHeimAb(schluessel, instanz, heim, ersetzen = true))
        val zweit = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        assertEquals(6, zweit.bereinigt.size)
        assertEquals(emptyList<String>(), zweit.nochImProjekt)
        assertEquals(emptyList<String>(), projektdateienMitText(projekt, schluessel))
    }

    @Test
    fun `a second run changes nothing and reports nothing`() {
        val projekt = tmp.newFolder("projekt6")
        val heim = tmp.newFolder("heim6")
        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        val zweit = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        assertEquals(HeimAblage.UNVERAENDERT, zweit.heim)
        assertEquals(emptyList<String>(), zweit.bereinigt)
        assertEquals(emptyList<String>(), projektdateienMitText(projekt, schluessel))
    }

    @Test
    fun `the key is read back out of an old hook script, escapes included`() {
        assertEquals("cky_live_abc", CachlyInstructionWriter.schluesselImHookSkript("process.env.CACHLY_JWT = 'cky_live_abc';"))
        assertEquals("ab'c", CachlyInstructionWriter.schluesselImHookSkript("process.env.CACHLY_JWT = 'ab\\'c';"))
        assertNull(CachlyInstructionWriter.schluesselImHookSkript("process.env.CACHLY_BRAIN_INSTANCE_ID = 'x';"))
        assertNull(CachlyInstructionWriter.schluesselImHookSkript("process.env.CACHLY_JWT = '';"))
    }

    @Test
    fun `AI Assistant project rule is written once and updated in place`() {
        val projekt = tmp.newFolder("projekt3")
        val heim = tmp.newFolder("heim3")

        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        val regel = File(projekt, ".aiassistant/rules/cachly.md")
        assertTrue("Regel fehlt", regel.exists())
        assertTrue(regel.readText().contains(instanz))

        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        assertEquals(2, regel.readText().split("<!-- cachly-brain-start -->").size)
        assertFalse(regel.readText().contains(schluessel))
    }

    @Test
    fun `Junie guidelines are written new, with the smart_recall rule and no key`() {
        val projekt = tmp.newFolder("junie-neu")
        val heim = tmp.newFolder("heim-junie-neu")

        val r = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        val regel = File(projekt, ".junie/guidelines.md")
        assertTrue("guidelines.md fehlt", regel.exists())
        val text = regel.readText()
        assertTrue(text.startsWith(CachlyInstructionWriter.BRAIN_START))
        assertTrue(text.contains("smart_recall(instance_id=\"$instanz\""))
        assertFalse(text.contains(schluessel))
        assertTrue(r.written.contains(".junie/guidelines.md"))
    }

    @Test
    fun `foreign Junie guidelines keep their text and get our block appended once`() {
        val projekt = tmp.newFolder("junie-fremd")
        val heim = tmp.newFolder("heim-junie-fremd")
        val regel = File(projekt, ".junie/guidelines.md")
        regel.parentFile.mkdirs()
        regel.writeText("Meine eigenen Regeln\n")

        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)
        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        val text = regel.readText()
        assertTrue(text.startsWith("Meine eigenen Regeln\n"))
        assertEquals(2, text.split(CachlyInstructionWriter.BRAIN_START).size)
        assertTrue(text.contains("smart_recall"))
    }

    @Test
    fun `our own old Junie guidelines are updated in place and the text around stays`() {
        val projekt = tmp.newFolder("junie-alt")
        val heim = tmp.newFolder("heim-junie-alt")
        val regel = File(projekt, ".junie/guidelines.md")
        regel.parentFile.mkdirs()
        regel.writeText("Vorspann\n${CachlyInstructionWriter.BRAIN_START}\nalter Text\n${CachlyInstructionWriter.BRAIN_END}\nNachspann\n")

        CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        val text = regel.readText()
        assertFalse(text.contains("alter Text"))
        assertTrue(text.contains("smart_recall"))
        assertTrue(text.startsWith("Vorspann\n"))
        assertTrue(text.endsWith("Nachspann\n"))
    }

    @Test
    fun `a Junie guidelines write failure does not stop the other files`() {
        val projekt = tmp.newFolder("junie-fehler")
        val heim = tmp.newFolder("heim-junie-fehler")
        // Ein Ordner an der Stelle der Datei: Schreiben scheitert.
        File(projekt, ".junie/guidelines.md").mkdirs()

        val r = CachlyInstructionWriter.writeAll(projekt.path, url, schluessel, instanz, heim)

        assertTrue(r.skipped.contains("guidelines.md"))
        assertTrue(File(projekt, "CLAUDE.md").readText().contains("smart_recall"))
    }

    // ── Die Texte in "Set Up AI Files" ───────────────────────────────────────

    @Test
    fun `the question offers both choices and says the key stays out of the project`() {
        assertTrue(SchluesselWahl.ERSETZEN.startsWith("Use this key for all projects"))
        assertEquals("Keep the stored key", SchluesselWahl.BEHALTEN)
        assertTrue(SchluesselWahl.FRAGE.contains("NOT written into your project files either way"))
        assertTrue(SchluesselWahl.FRAGE.contains(SchluesselWahl.ERSETZEN))
        assertTrue(SchluesselWahl.FRAGE.contains(SchluesselWahl.BEHALTEN))
    }

    @Test
    fun `the report names the outcome for each case`() {
        fun r(heim: HeimAblage, bereinigt: List<String> = emptyList(), noch: List<String> = emptyList()) =
            CachlyInstructionWriter.Result(emptyList(), emptyList(), heim, bereinigt, noch)

        assertTrue(SchluesselWahl.bericht(r(HeimAblage.GESCHRIEBEN), false).contains("stored in ~/.cachly/credentials.json, not in your project"))
        assertTrue(SchluesselWahl.bericht(r(HeimAblage.KONFLIKT), false).contains("You kept the key stored"))
        assertTrue(SchluesselWahl.bericht(r(HeimAblage.UNVERAENDERT), true).contains("replaced the one stored"))
        assertTrue(SchluesselWahl.bericht(r(HeimAblage.FEHLER), false).contains("Nothing was written to your project"))
        assertEquals("", SchluesselWahl.bericht(r(HeimAblage.UNVERAENDERT), false))
        assertTrue(SchluesselWahl.bericht(r(HeimAblage.GESCHRIEBEN, bereinigt = listOf(".mcp.json")), false).contains("• .mcp.json"))
        assertTrue(SchluesselWahl.bericht(r(HeimAblage.KONFLIKT, noch = listOf(".mcp.json")), false).contains("left untouched"))
    }
}
