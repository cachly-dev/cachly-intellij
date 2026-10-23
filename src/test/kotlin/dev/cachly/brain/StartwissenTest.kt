package dev.cachly.brain

import dev.cachly.brain.Startwissen.Entscheidung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Start-Import (23.10.2026): 69 neue fremde Konten in 45 Tagen, 40 ueber JetBrains, nur
 * eines mit Git-Import. Legt das Plugin das Brain an, las niemand die Git-Geschichte ein.
 */
class StartwissenTest {

    private val schluessel = "cky_live_nur_fuer_den_test_0001"

    @Test
    fun `Aufruf ueber npx, Schluessel nur in der Umgebung`() {
        val unix = Startwissen.befehl("/home/a/projekt", windows = false)!!
        assertEquals(listOf("npx", "-y", Startwissen.PAKET, Startwissen.BEFEHL, "/home/a/projekt"), unix)
        val win = Startwissen.befehl("C:\\Users\\a\\Mein Projekt", windows = true)!!
        assertEquals(listOf("cmd", "/c", "npx", "-y", Startwissen.PAKET, Startwissen.BEFEHL, "C:\\Users\\a\\Mein Projekt"), win)
        val env = Startwissen.umgebung("https://api.cachly.dev", schluessel, "i-1")
        assertEquals(schluessel, env["CACHLY_JWT"])
        assertEquals("i-1", env["CACHLY_BRAIN_INSTANCE_ID"])
        assertFalse((unix + win).any { it.contains(schluessel) })
    }

    @Test
    fun `Pfad mit cmd-Steuerzeichen geht unter Windows nicht an cmd`() {
        for (p in listOf("C:\\a&b", "C:\\a|b", "C:\\a>b", "C:\\a^b", "C:\\100%", "C:\\\"x\"")) {
            assertNull("$p muesste abgewiesen werden", Startwissen.befehl(p, windows = true))
        }
        assertTrue("auf Unix gibt es kein cmd", Startwissen.befehl("/tmp/a&b", windows = false) != null)
        assertNull(Startwissen.befehl("  ", windows = false))
    }

    @Test
    fun `nur ein leeres Brain, das antwortet`() {
        assertEquals(Entscheidung.STARTEN, Startwissen.entscheide("healthy", 0, schnappschuss = false))
        assertEquals(Entscheidung.STARTEN, Startwissen.entscheide("degraded", 0, schnappschuss = false))
        assertEquals(Entscheidung.FERTIG, Startwissen.entscheide("healthy", 12, schnappschuss = false))
        assertEquals(Entscheidung.SPAETER, Startwissen.entscheide("unreachable", 0, schnappschuss = false))
        assertEquals(Entscheidung.SPAETER, Startwissen.entscheide("not_configured", 0, schnappschuss = false))
        assertEquals("ein alter Stand ist kein Beleg", Entscheidung.SPAETER, Startwissen.entscheide("degraded", 30, schnappschuss = true))
    }

    @Test
    fun `hoechstens drei Versuche`() {
        assertTrue(Startwissen.faellig(null))
        assertTrue(Startwissen.faellig(Startwissen.VERSUCHE - 1))
        assertFalse(Startwissen.faellig(Startwissen.VERSUCHE))
    }

    /** Eine Quelle: das MCP-Paket muss den Befehl kennen, sonst importiert niemand. */
    @Test
    fun `MCP-Paket nimmt den Befehl an`() {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "sdk/mcp/src/startwissen-hook.ts").exists()) d = d.parentFile
        val wurzel = d ?: throw AssertionError("sdk/mcp/src/startwissen-hook.ts nicht gefunden (Start: ${File("").absolutePath}).")
        assertTrue(File(wurzel, "sdk/mcp/src/startwissen-hook.ts").readText().contains("STARTWISSEN_IMPORT = '${Startwissen.BEFEHL}'"))
        assertTrue(
            "index.ts muss den Unterbefehl als process.argv[2] annehmen",
            File(wurzel, "sdk/mcp/src/index.ts").readText().contains("process.argv[2] === STARTWISSEN_IMPORT"),
        )
    }
}
