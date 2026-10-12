package dev.cachly.brain

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * Bringt cachly in JetBrains AI Assistant.
 *
 * WARUM (12.10.2026): 11 echte JetBrains-Konten seit dem 11.10., alle mit
 * 0 Recalls. AI Assistant liest MCP-Server weder aus .mcp.json noch aus
 * .junie/mcp/mcp.json, sondern nur aus seinen eigenen Einstellungen. Das Plugin
 * hatte den Brain-Schluessel, aber der Chat hatte nie ein cachly-Werkzeug.
 *
 * Zwei Wege, je nach IDE-Fassung. Belegt aus dem Bytecode des AI-Assistant-
 * Plugins 251 bis 263 (geladen von plugins.jetbrains.com, 12.10.2026):
 *
 *  - Ab Build 253 (2025.3) liest AI Assistant die globale Datei
 *    ~/.ai/mcp/mcp.json (Registry-Schluessel llm.mcp.client.global.mcp.json.path).
 *    Ein neuer Server darin startet ABGESCHALTET. Eine IDE-Meldung "Enable
 *    Server" gab es in der echten Probe (IntelliJ IDEA 2025.3, 12.10.2026)
 *    NIE; 0.8.5 versprach sie trotzdem. Der Weg, der funktioniert: MCP-Seite
 *    oeffnen, bei cachly den Haken setzen, OK. Diesen Haken nimmt das Plugin
 *    nicht ab: Er ist die Sicherheitsabfrage der IDE.
 *    Unter Windows bemerkt AI Assistant eine geaenderte mcp.json erst nach
 *    einem IDE-Neustart (gemessen: Datei geleert, Server lief unveraendert
 *    weiter; nach dem Neustart stand cachly abgeschaltet auf der Seite).
 *    Vermutete Ursache: Er vergleicht `C:\...` mit `C:/...`. Der erste Start
 *    brauchte 34 bis 39 s nach dem Haken (npx-Zwischenspeicher leer).
 *  - 241 bis 252 kennen keine solche Datei. Das Plugin kopiert die Einstellung
 *    in die Zwischenablage und oeffnet die Seite Settings | Tools | AI Assistant |
 *    Model Context Protocol (MCP). Dort: Add, einfuegen, OK, Apply.
 *
 * Bewusst NICHT: die XML-Einstellung des AI Assistant bearbeiten (ab 253 nur
 * Zustand, davor Pruefsumme gegen Manipulation) und die experimentelle
 * McpClientRegistry (Modul als intern markiert, Zugriff ungeprueft).
 */
object AiAssistantAnbindung {

    private const val AI_ASSISTANT = "com.intellij.ml.llm"
    private const val GROUP = "Cachly Brain Ambient"
    private const val MCP_SEITE = "Model Context Protocol (MCP)"
    internal const val MCP_KLICKWEG = "Tools → AI Assistant → Model Context Protocol (MCP)"
    private const val GLOBAL_SCHLUESSEL = "llm.mcp.client.global.mcp.json.path"
    private const val GLOBAL_VORGABE = ".ai/mcp/mcp.json"
    private const val PAKET = "@cachly-dev/mcp-server@latest"
    internal const val ERSTER_BUILD_MIT_DATEI = 253

    /** Einmal je IDE-Sitzung, nicht bei jedem Projekt erneut. */
    @Volatile private var angeboten = false

    fun aiAssistantAktiv(): Boolean {
        val id = PluginId.getId(AI_ASSISTANT)
        return PluginManagerCore.isPluginInstalled(id) && !PluginManagerCore.isDisabled(id)
    }

    fun liestGlobaleDatei(): Boolean =
        ApplicationInfo.getInstance().build.baselineVersion >= ERSTER_BUILD_MIT_DATEI

    private fun heim() = File(System.getProperty("user.home"))

    /** Der Pfad, den AI Assistant liest; die Registry kann ihn umstellen. */
    fun globaleDatei(heim: File = heim()): File {
        val rel = runCatching { Registry.stringValue(GLOBAL_SCHLUESSEL) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: GLOBAL_VORGABE
        return loeseAuf(heim, rel)
    }

    /**
     * So rechnet AI Assistant selbst: `Paths.get(heim).resolve(rel)`. Ein
     * ABSOLUTER Wert gewinnt dort. `File(heim, rel)` haengte ihn dagegen an den
     * Heimordner an — die Datei landete an einem Ort, den AI Assistant nie liest,
     * [hatCachly] fand sie nicht, und das Angebot kam bei jedem Start wieder.
     * Ein Wert, den der Dateisystemtreiber nicht als Pfad kennt, faellt auf die
     * Vorgabe zurueck.
     */
    internal fun loeseAuf(heim: File, rel: String): File =
        runCatching { heim.toPath().resolve(rel).toFile() }
            .getOrElse { heim.toPath().resolve(GLOBAL_VORGABE).toFile() }

    /**
     * Der Servereintrag. Unter Windows ueber `cmd /c`: Die IDE startet den
     * Befehl ohne Shell, und `npx` ist dort eine .cmd-Datei, die ein direkter
     * Prozessstart nicht findet. [apiKey] nur dann, wenn der Schluessel nicht in
     * ~/.cachly/credentials.json liegt — dort liest ihn der MCP-Server selbst.
     */
    internal fun serverEintrag(
        baseUrl: String,
        instanceId: String,
        apiKey: String?,
        windows: Boolean,
    ): LinkedHashMap<String, Any?> {
        val aufruf = if (windows) "cmd" to listOf("/c", "npx", "-y", PAKET) else "npx" to listOf("-y", PAKET)
        return linkedMapOf(
            "command" to aufruf.first,
            "args" to aufruf.second,
            "env" to linkedMapOf<String, String>().apply {
                put("CACHLY_API_URL", baseUrl)
                if (!apiKey.isNullOrBlank()) put("CACHLY_JWT", apiKey)
                put("CACHLY_BRAIN_INSTANCE_ID", instanceId)
            },
        )
    }

    /** Der Text fuer "Add" in den MCP-Einstellungen (Format laut JetBrains-Hilfe). */
    internal fun einfuegeText(eintrag: Map<String, Any?>): String =
        CachlyInstructionWriter.toJson(linkedMapOf("mcpServers" to linkedMapOf("cachly" to eintrag)), 0)

    enum class Ergebnis { NEU, GEAENDERT, GLEICH }

    /**
     * Fuehrt cachly in [datei] zusammen; andere Server bleiben stehen. Schreibt
     * nur bei Abweichung: Jede Aenderung schaltet den Server in der IDE wieder
     * ab und loest eine neue Nachfrage aus. null bei einem Fehler.
     */
    internal fun eintragen(datei: File, eintrag: Map<String, Any?>): Ergebnis? = runCatching {
        val text = if (datei.exists()) datei.readText() else ""
        val inhalt = if (text.isBlank()) mutableMapOf() else CachlyInstructionWriter.parseJsoncObject(text)
        // Unlesbare Datei des Nutzers nie still ersetzen — dann lieber der Weg ueber die Zwischenablage.
        check(text.isBlank() || inhalt.isNotEmpty() || text.replace(Regex("\\s"), "") == "{}") { "unlesbar" }
        @Suppress("UNCHECKED_CAST")
        val servers = (inhalt["mcpServers"] as? MutableMap<String, Any?>) ?: mutableMapOf()
        val vorher = servers["cachly"]
        if (vorher != null && CachlyInstructionWriter.toJson(vorher, 0) == CachlyInstructionWriter.toJson(eintrag, 0)) {
            return@runCatching Ergebnis.GLEICH
        }
        servers["cachly"] = eintrag
        inhalt["mcpServers"] = servers
        datei.parentFile?.mkdirs()
        val tmp = File(datei.parentFile, datei.name + ".tmp")
        tmp.writeText(CachlyInstructionWriter.toJson(inhalt, 0) + "\n")
        java.nio.file.Files.move(tmp.toPath(), datei.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        if (vorher == null) Ergebnis.NEU else Ergebnis.GEAENDERT
    }.getOrNull()

    /** Steht cachly schon in der Datei, die AI Assistant liest? */
    internal fun hatCachly(datei: File): Boolean = runCatching {
        if (!datei.exists()) return@runCatching false
        @Suppress("UNCHECKED_CAST")
        val servers = CachlyInstructionWriter.parseJsoncObject(datei.readText())["mcpServers"] as? Map<String, Any?>
        servers?.containsKey("cachly") == true
    }.getOrDefault(false)

    /**
     * Bietet die Anbindung an, wenn ein verbundenes Brain und ein aktiver
     * AI Assistant da sind und cachly dort noch fehlt. Hoechstens einmal je
     * IDE-Sitzung; "Don't show again" und der leise Modus schalten es ab.
     */
    fun anbieten(project: Project) {
        val s = CachlySettings.getInstance().state
        if (s.apiKey.isBlank() || s.instanceId.isBlank()) return
        if (s.quietMode || s.aiAssistantHinweisAus) return
        if (!aiAssistantAktiv()) return
        if (liestGlobaleDatei()) {
            if (hatCachly(globaleDatei())) return
        } else if (s.aiAssistantKopiert) {
            // Vor 253 sieht das Plugin nicht, ob eingefuegt wurde; einmal kopiert reicht.
            return
        }
        if (angeboten) return
        angeboten = true
        ApplicationManager.getApplication().executeOnPooledThread {
            CachlyApiClient.trackEvent("jetbrains_ai_angeboten")
        }

        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        val n = if (liestGlobaleDatei()) {
            group.createNotification(
                "Cachly Brain — connect JetBrains AI Assistant",
                "AI Assistant does not see your Brain yet. One click adds cachly to its MCP servers; " +
                    "you then tick cachly on the MCP settings page.",
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring("Connect AI Assistant") { verbinden(project) })
        } else {
            group.createNotification(
                "Cachly Brain — connect JetBrains AI Assistant",
                "AI Assistant does not see your Brain yet. Copy the server config, then in the MCP settings " +
                    "click Add, paste it, and press OK and Apply.",
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring("Copy config and open settings") {
                kopierenUndOeffnen(project)
            })
        }
        n.addAction(NotificationAction.createSimpleExpiring("Don't show again") {
            CachlySettings.getInstance().state.aiAssistantHinweisAus = true
        }).notify(project)
    }

    /**
     * Der Hinweis nach dem Eintragen. Der Klickweg nennt die Bezeichnungen der
     * Seite so, wie sie in der IDE stehen. Ueber den Schalter "Automatically
     * enable new and changed MCP servers" (Vorgabe: aus) steht hier nur, dass es
     * ihn gibt: Ob er die Neustart-Luecke unter Windows schliesst, ist nicht
     * geprueft. Der Text ist HTML (Zeilenumbruch `<br>`); die Gruppe schreibt
     * ihn ins Ereignisprotokoll, wo er nach dem Ausblenden nachlesbar bleibt.
     */
    internal fun hinweisText(pfad: String, ergebnis: Ergebnis): String {
        val schritte = "(1) Open MCP settings ($MCP_KLICKWEG), tick cachly, press OK. " +
            "The first start can take ~40 s.<br>" +
            "(2) cachly missing in the list? Restart the IDE once, it then reads the file."
        return if (ergebnis == Ergebnis.GLEICH) {
            "cachly is already in $pfad. If AI Assistant still has no cachly tools:<br>$schritte"
        } else {
            "cachly is now in $pfad.<br>$schritte<br>" +
                "Optional: tick \"Automatically enable new and changed MCP servers\" on that page."
        }
    }

    /** Vom Hinweis und von Tools → Connect JetBrains AI Assistant. */
    fun verbinden(project: Project) {
        if (!liestGlobaleDatei()) {
            kopierenUndOeffnen(project)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val s = CachlySettings.getInstance().state
            val heim = heim()
            val imHeim = CachlyInstructionWriter.speichereSchluesselImHeim(s.apiKey, s.instanceId, heim)
            val eintrag = serverEintrag(s.apiUrl.trimEnd('/'), s.instanceId, if (imHeim) null else s.apiKey, SystemInfo.isWindows)
            val datei = globaleDatei(heim)
            val ergebnis = eintragen(datei, eintrag)
            // Sofort ins VFS, damit AI Assistant die Datei jetzt bemerkt und nicht erst beim naechsten Abgleich.
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(datei)
            CachlyApiClient.trackEvent(if (ergebnis == null) "jetbrains_ai_eintrag_fehler" else "jetbrains_ai_eingetragen")
            if (project.isDisposed) return@executeOnPooledThread
            val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return@executeOnPooledThread
            if (ergebnis == null) {
                group.createNotification(
                    "Cachly Brain — could not write ${datei.path}",
                    "Add the server by hand instead: copy the config, then Add, paste, OK and Apply.",
                    NotificationType.WARNING,
                ).addAction(NotificationAction.createSimpleExpiring("Copy config and open settings") {
                    kopierenUndOeffnen(project)
                }).notify(project)
                return@executeOnPooledThread
            }
            val text = hinweisText(datei.path, ergebnis)
            group.createNotification("Cachly Brain — AI Assistant", text, NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring("Open MCP settings") { oeffnen(project) })
                .notify(project)
        }
    }

    private fun kopierenUndOeffnen(project: Project) {
        val s = CachlySettings.getInstance().state
        val imHeim = CachlyInstructionWriter.speichereSchluesselImHeim(s.apiKey, s.instanceId, heim())
        val eintrag = serverEintrag(s.apiUrl.trimEnd('/'), s.instanceId, if (imHeim) null else s.apiKey, SystemInfo.isWindows)
        CopyPasteManager.getInstance().setContents(StringSelection(einfuegeText(eintrag)))
        s.aiAssistantKopiert = true
        ApplicationManager.getApplication().executeOnPooledThread {
            CachlyApiClient.trackEvent("jetbrains_ai_kopiert")
        }
        oeffnen(project)
    }

    /** Oeffnet die MCP-Seite; fehlt sie, oeffnet sich der Einstellungsdialog ohne Auswahl. */
    private fun oeffnen(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) ShowSettingsUtil.getInstance().showSettingsDialog(project, MCP_SEITE)
        }
    }
}
