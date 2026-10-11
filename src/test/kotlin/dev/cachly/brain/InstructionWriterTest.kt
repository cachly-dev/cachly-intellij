package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Set Up AI Files" (CachlyInstructionWriter.writeAll), 11.10.2026:
 *  - Der Schluessel gehoert in ~/.cachly/credentials.json, nicht in Dateien im
 *    Projekt, die mit committet werden (.mcp.json, .junie/mcp/mcp.json,
 *    .claude/hooks/). Bis 0.8.3 stand er dort im Klartext.
 *  - JetBrains AI Assistant bekommt eine Projektregel in .aiassistant/rules/.
 */
class InstructionWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val schluessel = "cky_live_testschluessel_0001"
    private val instanz = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"

    private fun projektdateienMitSchluessel(projekt: File): List<String> =
        projekt.walkTopDown()
            .filter { it.isFile && it.readText().contains(schluessel) }
            .map { it.relativeTo(projekt).path.replace('\\', '/') }
            .toList()

    @Test
    fun `key goes to the home credentials file, not into project files`() {
        val projekt = tmp.newFolder("projekt")
        val heim = tmp.newFolder("heim")

        CachlyInstructionWriter.writeAll(projekt.path, "https://api.cachly.dev", schluessel, instanz, heim)

        val cred = File(heim, ".cachly/credentials.json")
        assertTrue("credentials.json fehlt", cred.exists())
        val inhalt = CachlyInstructionWriter.parseJsoncObject(cred.readText())
        assertEquals(schluessel, inhalt["apiKey"])
        assertEquals(instanz, inhalt["instanceId"])
        assertEquals("Schluessel steht in Projektdateien", emptyList<String>(), projektdateienMitSchluessel(projekt))
        assertTrue(File(projekt, ".mcp.json").readText().contains(instanz))
    }

    @Test
    fun `a different key at home stays, and this key keeps working via the project file`() {
        val projekt = tmp.newFolder("projekt2")
        val heim = tmp.newFolder("heim2")
        val fremd = File(heim, ".cachly/credentials.json")
        fremd.parentFile.mkdirs()
        fremd.writeText("""{"apiKey":"cky_live_ein_anderes_konto","instanceId":"x"}""")

        CachlyInstructionWriter.writeAll(projekt.path, "https://api.cachly.dev", schluessel, instanz, heim)

        assertTrue(fremd.readText().contains("cky_live_ein_anderes_konto"))
        assertTrue(File(projekt, ".mcp.json").readText().contains(schluessel))
    }

    @Test
    fun `AI Assistant project rule is written once and updated in place`() {
        val projekt = tmp.newFolder("projekt3")
        val heim = tmp.newFolder("heim3")

        CachlyInstructionWriter.writeAll(projekt.path, "https://api.cachly.dev", schluessel, instanz, heim)
        val regel = File(projekt, ".aiassistant/rules/cachly.md")
        assertTrue("Regel fehlt", regel.exists())
        assertTrue(regel.readText().contains(instanz))

        CachlyInstructionWriter.writeAll(projekt.path, "https://api.cachly.dev", schluessel, instanz, heim)
        assertEquals(2, regel.readText().split("<!-- cachly-brain-start -->").size)
        assertFalse(regel.readText().contains(schluessel))
    }
}
