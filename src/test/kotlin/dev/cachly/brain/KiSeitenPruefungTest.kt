package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brain Doctor, KI-Seite (03.11.2026, jetbrains-brain-doctor-passes-without-ai-side). */
class KiSeitenPruefungTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = "cky_live_abc123"

    @Test
    fun `frisches Projekt ohne alles meldet vier Hinweise`() {
        val hinweise = KiSeitenPruefung.pruefe(tmp.newFolder("p"), tmp.newFolder("h"), key) { false }
        assertEquals(4, hinweise.size)
        assertTrue(hinweise.any { it.startsWith("Node.js") })
        assertTrue(hinweise.any { it.contains("credentials.json has no API key") })
    }

    @Test
    fun `alles da meldet nichts`() {
        val p = tmp.newFolder("p")
        val h = tmp.newFolder("h")
        File(h, ".cachly").mkdirs()
        File(h, ".cachly/credentials.json").writeText("""{"apiKey":"$key"}""")
        File(p, ".mcp.json").writeText("""{"mcpServers":{"cachly":{"command":"npx"}}}""")
        File(p, "CLAUDE.md").writeText("${CachlyInstructionWriter.BRAIN_START}\nx\n${CachlyInstructionWriter.BRAIN_END}")
        assertEquals(emptyList<String>(), KiSeitenPruefung.pruefe(p, h, key) { true })
    }

    @Test
    fun `anderer Schluessel in credentials wird gemeldet`() {
        val p = tmp.newFolder("p")
        val h = tmp.newFolder("h")
        File(h, ".cachly").mkdirs()
        File(h, ".cachly/credentials.json").writeText("""{"apiKey":"cky_live_other"}""")
        val hinweise = KiSeitenPruefung.pruefe(p, h, key) { true }
        assertTrue(hinweise.any { it.contains("different API key") })
    }
}
