package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git-Hook des Plugins (03.11.2026, jetbrains-cls-hook-drops-lessons): Die Commit-Nachricht darf
 * weder Shell noch JSON brechen. Der Lauftest braucht sh, node und git; fehlt eins, wird er uebersprungen.
 */
class ClsHookTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun kann(vararg cmd: String) = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    @Test
    fun `Hook enthaelt keine Nachricht im Quelltext und kein tr auf Anfuehrungszeichen`() {
        val s = CachlyInstructionWriter.buildClsHook("inst-1")
        assertTrue(s.startsWith("#!/bin/sh\n"))
        assertTrue(s.contains("CLS_MSG=\$(git log"))
        assertFalse(s.contains("'\$MSG'"))
        assertFalse("kein Apostroph im Node-Programm", CachlyInstructionWriter.CLS_NODE_PROGRAMM.contains('\''))
    }

    @Test
    fun `Instanz-ID mit Shell-Zeichen wird bereinigt`() {
        val s = CachlyInstructionWriter.buildClsHook("a\"; rm -rf ~ #\$(x)")
        assertTrue(s.contains("CLS_ID=\"arm-rfx\""))
    }

    @Test
    fun `alter Hook wird durch den neuen ersetzt, fremder Teil bleibt`() {
        val alt = "#!/bin/sh\necho vorher\n\n" + listOf(
            "# cachly CLS — Continuous Learning Stream (installed by Cachly IntelliJ plugin)",
            "# Runs silently on every commit to keep your brain up to date.",
            "CACHLY_INSTANCE=\"inst-1\"",
            "SHA=\$(git rev-parse HEAD 2>/dev/null || echo \"\")",
            "MSG=\$(git log -1 --pretty=%B | head -1)",
            "FILES=\$(git diff-tree HEAD)",
            "node -e \"try{}catch(e){}\" 2>/dev/null &",
        ).joinToString("\n") + "\nexit 0\n"
        val neu = CachlyInstructionWriter.erneuereAltenClsHook(alt, "inst-1")
        assertNotNull(neu)
        assertTrue(neu!!.contains("echo vorher"))
        assertTrue(neu.contains("CLS_MSG="))
        assertFalse(neu.contains("CACHLY_INSTANCE="))
        assertEquals(null, CachlyInstructionWriter.erneuereAltenClsHook(neu, "inst-1"))
    }

    private fun mcpClsHook(): String {
        // Gradle startet Tests im Ordner sdk/intellij; zur Sicherheit nach oben suchen.
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "sdk/mcp/src/cls-hook.ts")
            if (f.isFile) return f.readText()
            d = d.parentFile
        }
        throw AssertionError("sdk/mcp/src/cls-hook.ts nicht gefunden (Start: ${File("").absolutePath}). Der Vergleich darf nicht still entfallen.")
    }

    private fun zeitgrenze(programm: String) = Regex("""timeout:(\d+)""").find(programm)?.groupValues?.get(1)

    @Test
    fun `Zeitgrenze des Node-Programms ist dieselbe wie im MCP-Hook (30 s)`() {
        // 04.11.2026: bis 0.9.8 standen hier 8 s, im MCP-Hook 30 s. Der erste npx-Start nach jeder
        // neuen MCP-Version laedt das Paket (gemessen bis 22,5 s) — mit 8 s ging dieser Commit verloren.
        val quelle = mcpClsHook()
        val start = quelle.indexOf("export const CLS_NODE_PROGRAM")
        assertTrue("CLS_NODE_PROGRAM nicht gefunden", start > -1)
        val mcp = zeitgrenze(quelle.substring(start, quelle.indexOf("}catch(e){}", start)))
        assertNotNull("timeout im MCP-Programm nicht gefunden", mcp)
        assertEquals(mcp, zeitgrenze(CachlyInstructionWriter.CLS_NODE_PROGRAMM))
    }

    @Test
    fun `Block aus 0_9_7 mit 8 s wird erneuert, fremder Teil bleibt, aktueller Block bleibt stehen`() {
        val kern8 = CachlyInstructionWriter.buildClsHook("inst-1")
            .removePrefix("#!/bin/sh\n").removeSuffix("\nexit 0")
            .replace("timeout:30000", "timeout:8000")
        val alt = "#!/bin/sh\necho vorher\n\n$kern8\nexit 0\n"
        val neu = CachlyInstructionWriter.erneuereAltenClsHook(alt, "inst-1")
        assertNotNull(neu)
        assertTrue(neu!!.contains("echo vorher"))
        assertTrue(neu.contains("timeout:30000"))
        assertFalse(neu.contains("timeout:8000"))
        assertTrue(neu.endsWith("\nexit 0\n"))
        assertEquals("aktueller Block: nichts zu erneuern", null, CachlyInstructionWriter.erneuereAltenClsHook(neu, "inst-1"))
    }

    @Test
    fun `ein Block fuer eine andere Instanz wird auf die gewaehlte umgestellt`() {
        val neu = CachlyInstructionWriter.erneuereAltenClsHook(CachlyInstructionWriter.buildClsHook("inst-1"), "inst-2")
        assertNotNull(neu)
        assertTrue(neu!!.contains("CLS_ID=\"inst-2\""))
        assertFalse(neu.contains("CLS_ID=\"inst-1\""))
    }

    @Test
    fun `Hook gibt das JSON als EIN Argument weiter, auch bei Leerzeichen, Anfuehrungszeichen, Apostroph und Zeilenumbruch`() {
        assumeTrue(kann("git", "--version") && kann("node", "--version") && kann("sh", "-c", "exit 0"))
        val repo = tmp.newFolder("repo")
        val bin = tmp.newFolder("bin")
        val log = File(tmp.root, "argv.txt")
        // 03.11.2026: Der Hook startet npx-cli.js neben node. Die Attrappe ist eine Kopie von node
        // in bin/ mit eigener npx-cli.js daneben; sie schreibt Anzahl und Text der Argumente in eine Datei.
        val nodeExe = ProcessBuilder(if (windows) listOf("where", "node") else listOf("sh", "-c", "command -v node"))
            .redirectErrorStream(true).start().let { p -> p.inputStream.readBytes().toString(Charsets.UTF_8).lines().first { it.isNotBlank() }.trim().also { p.waitFor() } }
        val kopie = File(bin, if (windows) "node.exe" else "node")
        File(nodeExe).copyTo(kopie)
        kopie.setExecutable(true)
        val cli = File(bin, "node_modules/npm/bin/npx-cli.js").apply { parentFile.mkdirs() }
        cli.writeText("require('fs').writeFileSync(" + "'" + log.absolutePath.replace("\\", "/") + "'," +
            "JSON.stringify({n:process.argv.length-2,args:process.argv.slice(2)}))\n")
        fun git(vararg a: String) {
            val p = ProcessBuilder(listOf("git", "-C", repo.absolutePath, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + a)
                .redirectErrorStream(true).start()
            p.inputStream.readBytes(); p.waitFor(20, TimeUnit.SECONDS)
        }
        git("init", "-q")
        File(repo, "f.txt").writeText("x")
        git("add", "f.txt")
        val kopf = "fix: don't crash on \"empty\" input 100% %PATH% \$HOME"
        // Nachricht ueber Datei: Javas Argumentquoting unter Windows frisst sonst die Anfuehrungszeichen.
        val msgDatei = File(tmp.root, "msg.txt").apply { writeText(kopf + "\n\nzweite Zeile\nund dritte\n") }
        git("commit", "-q", "-F", msgDatei.absolutePath)
        val hook = File(tmp.root, "hook.sh").apply { writeText(CachlyInstructionWriter.buildClsHook("inst-1")) }

        val pb = ProcessBuilder("sh", hook.absolutePath).directory(repo)
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        pb.redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor(10, TimeUnit.SECONDS) }

        val ende = System.currentTimeMillis() + 20000
        while (!log.exists() && System.currentTimeMillis() < ende) Thread.sleep(200)
        assertTrue("die npx-cli-Attrappe wurde nicht aufgerufen", log.exists())
        Thread.sleep(300)
        val aussen = com.google.gson.JsonParser.parseString(log.readText()).asJsonObject
        // -y, Paketname, cls-ingest, JSON: genau vier Argumente, das JSON ist ein einziges.
        assertEquals(4, aussen.get("n").asInt)
        val args = aussen.getAsJsonArray("args")
        assertEquals("cls-ingest", args.get(2).asString)
        val json = com.google.gson.JsonParser.parseString(args.get(3).asString).asJsonObject
        assertEquals(kopf, json.getAsJsonObject("payload").get("message").asString)
        assertEquals("git_commit", json.get("source").asString)
        assertEquals("inst-1", json.get("instance_id").asString)
    }
}
