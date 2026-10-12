package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Anbindung an JetBrains AI Assistant (12.10.2026): ab IDE 2025.3 ueber
 * ~/.ai/mcp/mcp.json, davor ueber die Zwischenablage. Geprueft wird die
 * Dateiarbeit; der Hinweis selbst braucht eine laufende IDE.
 */
class AiAssistantAnbindungTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val instanz = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"
    private val url = "https://api.cachly.dev"

    @Test
    fun `windows starts npx through cmd, others call npx directly`() {
        val win = AiAssistantAnbindung.serverEintrag(url, instanz, null, windows = true)
        assertEquals("cmd", win["command"])
        assertEquals(listOf("/c", "npx", "-y", "@cachly-dev/mcp-server@latest"), win["args"])

        val unix = AiAssistantAnbindung.serverEintrag(url, instanz, null, windows = false)
        assertEquals("npx", unix["command"])
        assertEquals(listOf("-y", "@cachly-dev/mcp-server@latest"), unix["args"])
    }

    @Test
    fun `key stays out of the entry when the home credentials file holds it`() {
        @Suppress("UNCHECKED_CAST")
        val ohne = AiAssistantAnbindung.serverEintrag(url, instanz, null, windows = false)["env"] as Map<String, String>
        assertFalse(ohne.containsKey("CACHLY_JWT"))
        assertEquals(instanz, ohne["CACHLY_BRAIN_INSTANCE_ID"])

        @Suppress("UNCHECKED_CAST")
        val mit = AiAssistantAnbindung.serverEintrag(url, instanz, "cky_x", windows = false)["env"] as Map<String, String>
        assertEquals("cky_x", mit["CACHLY_JWT"])
    }

    @Test
    fun `paste text is the mcpServers format from the JetBrains help`() {
        val text = AiAssistantAnbindung.einfuegeText(AiAssistantAnbindung.serverEintrag(url, instanz, null, false))
        val obj = CachlyInstructionWriter.parseJsoncObject(text)
        @Suppress("UNCHECKED_CAST")
        val servers = obj["mcpServers"] as Map<String, Any?>
        assertTrue(servers.containsKey("cachly"))
    }

    @Test
    fun `merge keeps other servers and reports new, then unchanged`() {
        val datei = File(tmp.newFolder("heim"), ".ai/mcp/mcp.json")
        datei.parentFile.mkdirs()
        datei.writeText("""{ "mcpServers": { "github": { "command": "gh-mcp" } } }""")
        val eintrag = AiAssistantAnbindung.serverEintrag(url, instanz, null, false)

        assertEquals(AiAssistantAnbindung.Ergebnis.NEU, AiAssistantAnbindung.eintragen(datei, eintrag))
        val zeitNachErstem = datei.lastModified()
        @Suppress("UNCHECKED_CAST")
        val servers = CachlyInstructionWriter.parseJsoncObject(datei.readText())["mcpServers"] as Map<String, Any?>
        assertTrue("fremder Server verloren", servers.containsKey("github"))
        assertTrue(AiAssistantAnbindung.hatCachly(datei))

        // Gleicher Eintrag: nicht schreiben — jede Aenderung schaltet den Server in der IDE wieder ab.
        Thread.sleep(20)
        assertEquals(AiAssistantAnbindung.Ergebnis.GLEICH, AiAssistantAnbindung.eintragen(datei, eintrag))
        assertEquals(zeitNachErstem, datei.lastModified())

        val anders = AiAssistantAnbindung.serverEintrag("https://example.test", instanz, null, false)
        assertEquals(AiAssistantAnbindung.Ergebnis.GEAENDERT, AiAssistantAnbindung.eintragen(datei, anders))
    }

    @Test
    fun `missing file is created with the parent folders`() {
        val datei = File(tmp.newFolder("leer"), ".ai/mcp/mcp.json")
        assertFalse(AiAssistantAnbindung.hatCachly(datei))
        assertEquals(
            AiAssistantAnbindung.Ergebnis.NEU,
            AiAssistantAnbindung.eintragen(datei, AiAssistantAnbindung.serverEintrag(url, instanz, null, false)),
        )
        assertTrue(AiAssistantAnbindung.hatCachly(datei))
    }

    @Test
    fun `broken json is not overwritten`() {
        val datei = File(tmp.newFolder("kaputt"), "mcp.json")
        datei.writeText("{ das ist kein json")
        // Der Inhalt des Nutzers darf nicht still verschwinden.
        assertNull(AiAssistantAnbindung.eintragen(datei, AiAssistantAnbindung.serverEintrag(url, instanz, null, false)))
        assertEquals("{ das ist kein json", datei.readText())

        val liste = File(tmp.root, "liste.json")
        liste.writeText("[1, 2]")
        assertNull(AiAssistantAnbindung.eintragen(liste, AiAssistantAnbindung.serverEintrag(url, instanz, null, false)))
        assertEquals("[1, 2]", liste.readText())
    }
}
