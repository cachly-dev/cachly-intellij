package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `buildBrainBlock` ist eine Handkopie von `buildClaudeMdBlock` im MCP-Paket
 * (sdk/mcp/src/index.ts). Dieser Test liest die MCP-Quelle und verlangt
 * Wortgleichheit. Fehlt die Quelle, scheitert er mit Meldung: ein stilles
 * Ueberspringen liesse die Kopien unbemerkt auseinanderlaufen.
 */
class BrainBlockGleichheitTest {

    private val id = "11111111-2222-3333-4444-555555555555"

    private fun mcpQuelle(): File {
        // Gradle startet Tests im Ordner sdk/intellij; zur Sicherheit nach oben suchen.
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "sdk/mcp/src/index.ts")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("MCP-Quelle sdk/mcp/src/index.ts nicht gefunden (Start: ${File("").absolutePath}). Der Gleichheitstest darf nicht still entfallen.")
    }

    private fun mcpBlock(): String {
        val quelle = mcpQuelle().readText().replace("\r\n", "\n")
        val start = quelle.indexOf("export function buildClaudeMdBlock")
        assertTrue("buildClaudeMdBlock nicht gefunden", start > -1)
        val von = quelle.indexOf("return `", start) + "return `".length
        val bis = quelle.indexOf("`;", von)
        return quelle.substring(von, bis)
            .replace("\u005C`", "`")
            .replace("\${CLAUDE_MD_MARKER_START}", CachlyInstructionWriter.BRAIN_START)
            .replace("\${CLAUDE_MD_MARKER_END}", CachlyInstructionWriter.BRAIN_END)
            .replace("\${instanceId}", id)
    }

    @Test
    fun `brain block is word for word the block the MCP setup writes`() {
        val kotlin = "${CachlyInstructionWriter.BRAIN_START}\n${CachlyInstructionWriter.buildBrainBlock(id)}${CachlyInstructionWriter.BRAIN_END}"
        assertEquals(mcpBlock(), kotlin)
    }

    @Test
    fun `brain block carries the smart_recall duty`() {
        val b = CachlyInstructionWriter.buildBrainBlock(id)
        assertTrue(b.contains("### BEFORE every task (mandatory"))
        assertTrue(b.contains("smart_recall(instance_id=\"$id\""))
    }
}
