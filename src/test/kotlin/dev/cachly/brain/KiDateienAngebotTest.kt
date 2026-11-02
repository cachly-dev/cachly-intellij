package dev.cachly.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Das Angebot beim Oeffnen eines Projekts erscheint nur, wenn keine Regeldatei
 * den cachly-Block traegt. Geprueft wird die Erkennung; der Hinweis selbst
 * braucht eine laufende IDE.
 */
class KiDateienAngebotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val block = "${CachlyInstructionWriter.BRAIN_START}\nsmart_recall …\n${CachlyInstructionWriter.BRAIN_END}"

    @Test
    fun `an empty project has no AI files`() {
        assertFalse(KiDateienAngebot.hatKiDateien(tmp.root))
    }

    @Test
    fun `a CLAUDE md without the cachly block does not count`() {
        File(tmp.root, "CLAUDE.md").writeText("# Our own rules\n")
        assertFalse(KiDateienAngebot.hatKiDateien(tmp.root))
    }

    @Test
    fun `any rule file with the cachly block counts, also one written by the npx setup`() {
        File(tmp.root, ".junie").mkdirs()
        File(tmp.root, ".junie/guidelines.md").writeText("# Team\n\n$block\n")
        assertTrue(KiDateienAngebot.hatKiDateien(tmp.root))
    }

    @Test
    fun `the list covers the files the writer creates`() {
        val quelle = File("src/main/kotlin/dev/cachly/brain/CachlyInstructionWriter.kt").readText()
        for (pfad in KiDateienAngebot.REGEL_DATEIEN) {
            assertTrue(pfad, quelle.contains("File(root, \"$pfad\")"))
        }
    }
}
