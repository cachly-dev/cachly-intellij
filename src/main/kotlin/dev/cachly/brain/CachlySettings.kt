package dev.cachly.brain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.annotations.OptionTag

@State(name = "CachlySettings", storages = [Storage("cachly-brain.xml")])
class CachlySettings : PersistentStateComponent<CachlySettings.State> {

    data class State(
        /**
         * Der API-Schluessel. Steht NICHT mehr in cachly-brain.xml (`Transient`),
         * sondern im Passwortspeicher der IDE (siehe [Ablage]). Die Eigenschaft
         * bleibt hier, weil rund 40 Stellen `state.apiKey` lesen.
         */
        @get:com.intellij.util.xmlb.annotations.Transient
        var apiKey: String = "",
        var instanceId: String = "",
        var apiUrl: String = "https://api.cachly.dev",
        var refreshIntervalSec: Int = 300,
        var showCostSaved: Boolean = true,
        var ambientLearning: Boolean = true,
        var proactiveBriefing: Boolean = true,
        /** Projektwissen in `.aiassistant/rules/cachly-wissen.md` schreiben (siehe Regelwissen). */
        var regelwissen: Boolean = true,
        /** Silences every proactive popup outright — status bar & panel still update. */
        var quietMode: Boolean = false,
        var firstHitShown: Boolean = false,
        /**
         * End of a trial obtained via instant-trial, as sent by the API in
         * `trial_ends_at`. Empty for a linked account.
         */
        var trialEndsAt: String = "",
        /**
         * How often the plugin has tried to fetch a key on its own without ever
         * succeeding. Capped in CachlyOnboarding: if the API answers but storing
         * the key fails for some reason, an uncapped retry would create a fresh
         * trial instance on every single project open.
         */
        var onboardingAttempts: Int = 0,
        /** True once the user has explicitly dismissed the connect prompt. */
        var onboardingDeclined: Boolean = false,
        /** "Don't show again" am Hinweis zur Anbindung von JetBrains AI Assistant. */
        var aiAssistantHinweisAus: Boolean = false,
        /** Vor IDE 2025.3: Die Einstellung wurde einmal kopiert — danach nicht mehr nachfragen. */
        var aiAssistantKopiert: Boolean = false,
        /**
         * "Not helpful" suppressions for per-file briefings, keyed
         * "relPath::topic" -> epoch millis. Capped at 300 (oldest evicted).
         */
        var briefingSuppressed: MutableMap<String, Long> = mutableMapOf(),
        // Last health snapshot that actually carried data, persisted across
        // restarts. A cold-start fetch (network still down, instance waking,
        // transient zeroed stats) otherwise repaints real counters as 0/limit.
        var lastGoodLessons: Int = 0,
        var lastGoodTotalRecalls: Int = 0,
        var lastGoodRecallLimit: Int = -1,
        var lastGoodTokensSaved: Int = 0,
        /**
         * Einwilligung (GROW-035, Heinrich 15.10.2026): ZWEI Fragen in einem
         * Dialog. Bis `einwilligungBeantwortet` wahr ist, geht NICHTS ueber das
         * Netz. Die Antworten stehen in `funktionErlaubt` und `statistikErlaubt`;
         * pruefen immer ueber `darfFunktion` / `darfStatistik` (Einwilligung.kt).
         */
        var einwilligungBeantwortet: Boolean = false,
        /** Datei-Briefings und alle Aufrufe an das Brain. Standard der Frage: ja. */
        var funktionErlaubt: Boolean = false,
        /** Nutzungsstatistik (nur Ereignisnamen). Standard der Frage: nein. */
        var statistikErlaubt: Boolean = false,
        /**
         * Nur zum LESEN des alten Klartext-Werts: Bis 0.8.8 stand der Schluessel
         * unter dem Namen `apiKey` in cachly-brain.xml. Dieses Feld liest ihn von
         * dort. [CachlySettings.schluesselLaden] bringt ihn in den
         * Passwortspeicher und leert das Feld erst dann; beim naechsten Speichern
         * verschwindet er damit aus der Datei.
         */
        @get:OptionTag("apiKey")
        var apiKeyAlt: String = "",
    )

    private var myState = State()

    /** Wo der Schluessel liegt. Tests setzen hier eine Speicher-Ablage. */
    internal var ablage: Ablage = PasswortSpeicher

    /** Der Stand der Ablage, den wir zuletzt gesehen oder geschrieben haben. */
    private var zuletztInAblage: String? = null

    /**
     * Wahr erst, wenn die Ablage gelesen werden konnte. Vorher schreiben wir
     * nichts hinein: ein Speicher, den wir nicht lesen konnten, koennte den
     * echten Schluessel enthalten, und ein leeres Feld ueberschriebe ihn.
     */
    private var ablageBereit = false

    override fun getState(): State {
        schluesselSichern()
        return myState
    }

    override fun loadState(state: State) {
        // Die Datei kennt den Schluessel nicht mehr. Ein erneutes Einlesen
        // (z. B. nach einer Aenderung der Datei von aussen) darf ihn nicht
        // leeren, sonst loescht der naechste Speicherlauf ihn aus dem Passwortspeicher.
        if (state.apiKey.isBlank()) state.apiKey = myState.apiKey
        myState = state
    }

    /**
     * Laeuft nach `loadState` bzw. `noStateLoaded`: holt den Schluessel aus dem
     * Passwortspeicher.
     */
    override fun initializeComponent() {
        schluesselLaden()
    }

    /**
     * Ersetzt den Stand mit dem, was der Nutzer in den Einstellungen eingegeben
     * hat. Anders als [loadState] uebernimmt es auch einen leeren Schluessel:
     * wer das Feld leert, will den Schluessel loswerden.
     */
    @Synchronized
    fun uebernehmen(neu: State) {
        myState = neu
        // Ein ausdruecklich eingegebener Schluessel darf geschrieben werden,
        // auch wenn das Lesen vorher scheiterte. Ein leeres Feld nicht: es
        // koennte nur heissen, dass wir den gespeicherten nicht lesen konnten.
        if (neu.apiKey.isNotBlank()) ablageBereit = true
        schluesselSichern()
    }

    /**
     * Schluessel aus der Ablage holen; einen alten Klartext-Wert uebernehmen.
     *
     * BESTANDSSCHUTZ: Wer bisher mit einem Schluessel in der Datei arbeitete,
     * verliert ihn nie. Der alte Wert wird erst in die Ablage geschrieben, dann
     * zurueckgelesen und verglichen. NUR wenn beides gleich ist, wird das Altfeld
     * geleert — und selbst dann nur, wenn die Ablage einen Neustart uebersteht.
     * In jedem anderen Fall bleibt der Altwert stehen; der naechste Start
     * versucht es noch einmal, und der Schluessel gilt in dieser Sitzung trotzdem.
     */
    @Synchronized
    internal fun schluesselLaden() {
        val s = myState
        val alt = s.apiKeyAlt
        if (alt.isNotBlank()) {
            // Erst lesen, dann schreiben. Ein Dateiwert kann auch aus dem Rueckfall
            // stammen (siehe schluesselSichern): War der Speicher beim letzten Start
            // gesperrt, holte das Onboarding einen Test-Schluessel, und der steht nun
            // in der Datei — der echte aber im Speicher. Ihn zu ueberschreiben hiesse,
            // den Zugang zum Konto zu verlieren.
            val vorhanden = try {
                ablage.lesen()
            } catch (t: Throwable) {
                LOG.warn("IDE password safe not readable, using the key from the settings file this session: ${t.javaClass.simpleName}")
                if (s.apiKey.isBlank()) s.apiKey = alt
                return
            }
            if (vorhanden != null && vorhanden != alt) {
                LOG.warn("IDE password safe already holds a different API key; it wins over the one in the settings file.")
                if (s.apiKey.isBlank() || s.apiKey == alt) s.apiKey = vorhanden
                s.apiKeyAlt = ""
                zuletztInAblage = vorhanden
                ablageBereit = true
                return
            }
            if (s.apiKey.isBlank()) s.apiKey = alt
            val uebernommen = try {
                ablage.schreiben(alt)
                ablage.lesen() == alt
            } catch (t: Throwable) {
                LOG.warn("API key could not be moved to the IDE password safe, keeping the old value for the next start: ${t.javaClass.simpleName}")
                false
            }
            if (uebernommen) {
                zuletztInAblage = alt
                ablageBereit = true
                // Nur leeren, wenn der Wert in der Ablage einen Neustart uebersteht.
                if (ablage.dauerhaft) s.apiKeyAlt = ""
            }
            return
        }
        try {
            val gespeichert = ablage.lesen()
            if (s.apiKey.isBlank() && gespeichert != null) s.apiKey = gespeichert
            zuletztInAblage = gespeichert ?: ""
            ablageBereit = true
        } catch (t: Throwable) {
            // Gesperrt oder verweigert: NICHT als "leer" behandeln.
            LOG.warn("API key could not be read from the IDE password safe: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Schreibt einen geaenderten Schluessel in die Ablage. Aufrufer, die
     * `state.apiKey` setzen (Onboarding, Konto verknuepfen), rufen es danach auf,
     * damit der Schluessel nicht erst beim naechsten Speicherlauf der IDE ankommt.
     */
    @Synchronized
    fun schluesselSichern() {
        val schluessel = myState.apiKey
        if (schluessel == zuletztInAblage) return
        // RUECKFALL IN DIE DATEI: Kann der Passwortspeicher den Schluessel nicht
        // halten (nicht lesbar, Schreiben scheitert, "nach Neustart vergessen"),
        // steht er wie vor 0.8.9 in cachly-brain.xml. Sonst waere ein neuer
        // Schluessel nach dem Neustart weg, und das Onboarding legte ein neues
        // Test-Brain an — der Nutzer verloere seine Lektionen.
        if (!ablageBereit) {
            // Nicht hineinschreiben: der Speicher koennte den echten Schluessel enthalten.
            if (schluessel.isNotBlank()) myState.apiKeyAlt = schluessel
            return
        }
        // Auch bei einem Fehler merken, damit nicht jeder getState()-Aufruf es neu versucht.
        zuletztInAblage = schluessel
        try {
            ablage.schreiben(schluessel.ifBlank { null })
            myState.apiKeyAlt = if (ablage.dauerhaft) "" else schluessel
        } catch (t: Throwable) {
            myState.apiKeyAlt = schluessel
            LOG.warn("API key could not be written to the IDE password safe, keeping it in the settings file: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CachlySettings::class.java)

        /** Nur fuer Tests: ersetzt den Dienst der IDE. */
        internal var testInstanz: CachlySettings? = null

        fun getInstance(): CachlySettings =
            testInstanz ?: ApplicationManager.getApplication().getService(CachlySettings::class.java)
    }
}
