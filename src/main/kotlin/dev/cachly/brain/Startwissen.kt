package dev.cachly.brain

/**
 * Start-Import der Git-Geschichte in ein neues Brain (23.10.2026).
 *
 * Warum: Von 69 neuen fremden Konten der letzten 45 Tage (40 davon ueber JetBrains) hat
 * nur eines je einen Git-Import gemeldet. Der MCP-Server importiert nur, wenn ER das
 * Brain anlegt; legt das Plugin es an, bleibt es leer — und mit ihm die Regeldatei
 * `.aiassistant/rules/cachly-wissen.md` (siehe [Regelwissen]).
 *
 * Die Regeln stehen EINMAL im MCP-Paket (sdk/mcp/src/startwissen-hook.ts): nur in ein
 * leeres Brain, nur aus einem Git-Repo des Nutzers, hoechstens 30 Commits, Schalter
 * CACHLY_STARTWISSEN=0. Das Plugin startet nur
 * `npx -y @cachly-dev/mcp-server@latest startwissen-import <projekt>`. Node ist da, wo
 * cachly im AI Assistant laeuft, ohnehin noetig (derselbe npx-Aufruf, [AiAssistantAnbindung]).
 *
 * Der Schluessel geht nur ueber die Umgebung, nie als Argument: Argumente stehen in jeder
 * Prozessliste.
 *
 * Nur reine Funktionen, darum ohne IDE testbar. Den Lauf startet [StartwissenDienst].
 */
object Startwissen {

    /** Unterbefehl; muss STARTWISSEN_IMPORT in sdk/mcp/src/startwissen-hook.ts gleichen. */
    const val BEFEHL = "startwissen-import"

    /** So oft je Instanz. Ein Brain mit Lektionen braucht keinen Start-Import. */
    const val VERSUCHE = 3

    /** npx startet kalt in bis zu ~30 s, das Paket wartet bis zu 90 s auf die Instanz. */
    const val FRIST_MINUTEN = 4L

    internal const val PAKET = "@cachly-dev/mcp-server@latest"

    enum class Entscheidung {
        /** Brain antwortet und ist leer: importieren. */
        STARTEN,
        /** Brain hat schon Lektionen: nie wieder fragen. */
        FERTIG,
        /** Brain antwortet (noch) nicht oder zeigt einen alten Stand: spaeter noch einmal. */
        SPAETER,
    }

    fun faellig(versuche: Int?): Boolean = (versuche ?: 0) < VERSUCHE

    /** Aus der Gesundheitsabfrage. Ein Schnappschuss (Kaltstart-Schutz) ist kein echter Stand. */
    fun entscheide(status: String, lektionen: Int, schnappschuss: Boolean): Entscheidung = when {
        schnappschuss -> Entscheidung.SPAETER
        status != "healthy" && status != "degraded" -> Entscheidung.SPAETER
        lektionen > 0 -> Entscheidung.FERTIG
        else -> Entscheidung.STARTEN
    }

    /** Zeichen, die `cmd /c` als Befehl deutet. Ein solcher Projektpfad wird nicht weitergereicht. */
    private val CMD_STEUERZEICHEN = Regex("""[&|<>^%"]""")

    /** Der Aufruf, oder null, wenn der Pfad unter Windows nicht sicher an `cmd` geht. */
    fun befehl(projekt: String, windows: Boolean): List<String>? {
        if (projekt.isBlank()) return null
        if (windows) {
            if (CMD_STEUERZEICHEN.containsMatchIn(projekt)) return null
            return listOf("cmd", "/c", "npx", "-y", PAKET, BEFEHL, projekt)
        }
        return listOf("npx", "-y", PAKET, BEFEHL, projekt)
    }

    fun umgebung(apiUrl: String, apiKey: String, instanceId: String): Map<String, String> = linkedMapOf(
        "CACHLY_API_URL" to apiUrl,
        "CACHLY_JWT" to apiKey,
        "CACHLY_BRAIN_INSTANCE_ID" to instanceId,
    )
}
