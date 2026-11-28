package dev.cachly.brain

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Die gespeicherte Instanz gibt es nicht mehr (HTTP 404, Schluessel wird angenommen).
 *
 * WARUM: Seit dem 28.11.2026 loescht die API taeglich Sofort-Test-Brains, die 14 Tage
 * alt, nie benutzt und leer sind (api/internal/service/sofort_test_abraeumer.go). Konto
 * und Schluessel bleiben gueltig, nur die Instanz verschwindet. Wer zurueckkommt, bekam
 * bis 0.9.16 nur "offline" in der Statusleiste, im Brain Doctor "wrong instance ID?" und
 * bei Verbinden "already connected". `ensureInstanceId` reparierte nur eine LEERE ID.
 * Die VS-Code-Erweiterung faengt das ab (Status `not_found`, extension.ts).
 *
 * REGELN
 *  - Sofort-Test-Schluessel (cky_trial_): EINMAL je IDE-Sitzung POST /instances/auto, neue
 *    ID speichern, ehrlich melden. Schlaegt das fehl: sagen, was der naechste Schritt ist;
 *    kein zweiter Versuch in dieser Sitzung.
 *  - Registrierter Schluessel: NIE von allein anlegen. Hinweis mit "Open dashboard" und
 *    "Reconnect". Erst der Klick legt an bzw. waehlt ein vorhandenes Brain.
 *  - Ein ausdruecklicher Klick (Connect Brain, Reconnect) darf die Sitzungssperre umgehen.
 *
 * Netz: nie auf dem EDT.
 */
object InstanzVerlust {

    sealed class Ausgang {
        /** 404 gab es nicht, oder diese Sitzung hat es schon behandelt: nichts zu tun. */
        object Nichts : Ausgang()

        /**
         * Neue ID gespeichert. [testBrain]: der Schluessel ist ein Sofort-Test-Schluessel.
         * [leerAbgeraeumt]: die API hat in der 404-Antwort `grund: "leer_abgeraeumt"` genannt; nur dann
         * stimmt "14 Tage leer und unbenutzt, nichts verloren".
         */
        data class Neu(
            val alteId: String,
            val neueId: String,
            val angelegt: Boolean,
            val testBrain: Boolean,
            val leerAbgeraeumt: Boolean = false,
        ) : Ausgang()

        /** Registrierter Schluessel: Brain weg, es wurde NICHTS angelegt. */
        data class KontoBrainFehlt(val id: String) : Ausgang()

        /** POST /instances/auto scheiterte. [code] -1 = Netz. */
        data class Scheitert(val code: Int, val grund: String?, val testBrain: Boolean) : Ausgang()
    }

    private const val DASHBOARD_BRAINS = "https://cachly.dev/instances"

    /** Loeschgrund in der 404-Antwort der API (api/internal/model/instance.go, GrundLeerAbgeraeumt). */
    internal const val GRUND_LEER_ABGERAEUMT = "leer_abgeraeumt"

    /** Hat diese IDE-Sitzung schon /instances/auto fuer ein verlorenes Test-Brain versucht? */
    private val autoVersucht = AtomicBoolean(false)

    /** IDs registrierter Konten, deren Verlust diese Sitzung schon gemeldet hat. */
    private val gemeldet = ConcurrentHashMap.newKeySet<String>()

    /** Nur fuer Tests: neue "IDE-Sitzung". */
    internal fun zuruecksetzen() {
        autoVersucht.set(false)
        gemeldet.clear()
    }

    /**
     * Entscheidet und handelt, ohne UI. [bekannterStatus] spart die Nachfrage, wenn der
     * Aufrufer das 404 schon gesehen hat. Muss abseits des EDT laufen. Wirft nie.
     */
    internal fun behandle(
        heim: File = File(System.getProperty("user.home")),
        bekannterStatus: Int? = null,
        ausdruecklich: Boolean = false,
    ): Ausgang = try {
        val s = CachlySettings.getInstance().state
        val key = s.apiKey.trim()
        val alteId = s.instanceId.trim()
        if (!darfFunktion(s) || key.isEmpty() || alteId.isEmpty()) {
            Ausgang.Nichts
        } else if (bekannterStatus != null && bekannterStatus != 404) {
            Ausgang.Nichts
        } else {
            // Eine Anfrage: sie liefert den Status UND, bei 404, den Loeschgrund. Kennt der Aufrufer
            // das 404 schon, zaehlt sein Status; die Anfrage holt dann nur den Grund.
            val abfrage = CachlyApiClient.instanzAbfrage(alteId)
            if ((bekannterStatus ?: abfrage.status) != 404) {
                // 401/403/Netz/200: altes Verhalten, hier gibt es nichts zu retten.
                Ausgang.Nichts
            } else {
                entscheide(s, key, alteId, abfrage.grund == GRUND_LEER_ABGERAEUMT, ausdruecklich, heim)
            }
        }
    } catch (_: Exception) {
        Ausgang.Nichts
    }

    private fun entscheide(
        s: CachlySettings.State,
        key: String,
        alteId: String,
        leerAbgeraeumt: Boolean,
        ausdruecklich: Boolean,
        heim: File,
    ): Ausgang {
        val testBrain = TestBrainUebernahme.istTestSchluessel(key)
        return if (testBrain || ausdruecklich) {
            if (testBrain && !ausdruecklich && !autoVersucht.compareAndSet(false, true)) {
                Ausgang.Nichts
            } else {
                legeNeuAn(s, alteId, testBrain, heim, leerAbgeraeumt)
            }
        } else if (gemeldet.add(alteId)) {
            Ausgang.KontoBrainFehlt(alteId)
        } else {
            Ausgang.Nichts
        }
    }

    private fun legeNeuAn(s: CachlySettings.State, alteId: String, testBrain: Boolean, heim: File, leerAbgeraeumt: Boolean): Ausgang =
        when (val r = CachlyApiClient.requestAutoInstance()) {
            is CachlyApiClient.AutoErgebnis.Fehler -> Ausgang.Scheitert(r.code, r.grund, testBrain)
            is CachlyApiClient.AutoErgebnis.Id -> {
                s.instanceId = r.id
                // Im Heimordner nur, wenn dort genau die alte ID stand (anderes Setup bleibt).
                CachlyInstructionWriter.ersetzeHeimInstanz(alteId, r.id, heim)
                Ausgang.Neu(alteId, r.id, r.neu, testBrain, leerAbgeraeumt)
            }
        }

    /**
     * Wie [behandle], plus Hinweis. Fuer Aufrufer auf Pool-Threads. [project] darf null sein
     * (Statusleiste): dann geht der Hinweis an das erste offene Projekt.
     */
    fun pruefeUndMelde(project: Project?, bekannterStatus: Int? = null, ausdruecklich: Boolean = false) {
        val ausgang = behandle(bekannterStatus = bekannterStatus, ausdruecklich = ausdruecklich)
        if (ausgang is Ausgang.Nichts) return
        val ziel = project ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        val gruppe = NotificationGroupManager.getInstance().getNotificationGroup(CachlyOnboarding.GROUP) ?: return
        val text = text(ausgang)
        val n = gruppe.createNotification(
            "Cachly Brain",
            text,
            if (ausgang is Ausgang.Neu) NotificationType.INFORMATION else NotificationType.WARNING,
        )
        when (ausgang) {
            is Ausgang.KontoBrainFehlt -> {
                n.addAction(NotificationAction.createSimple("Open dashboard") { BrowserUtil.browse(DASHBOARD_BRAINS) })
                n.addAction(NotificationAction.createSimple("Reconnect") { neuVerbinden(ziel) })
            }
            is Ausgang.Scheitert -> {
                if (ausgang.code == 403 || ausgang.grund == "sign_in_required") {
                    n.addAction(NotificationAction.createSimple("Sign in") {
                        if (ziel != null) CachlyOnboarding.startDeviceFlow(ziel)
                    })
                } else {
                    n.addAction(NotificationAction.createSimple("Open dashboard") { BrowserUtil.browse(DASHBOARD_BRAINS) })
                }
            }
            is Ausgang.Neu -> Unit
            Ausgang.Nichts -> Unit
        }
        n.notify(ziel)
        if (ausgang is Ausgang.Neu && ziel != null) {
            // Das neue Brain ist leer: derselbe Weg wie nach dem ersten Verbinden.
            ApplicationManager.getApplication().executeOnPooledThread {
                if (!ziel.isDisposed) StartwissenDienst.anstossen(ziel)
            }
        }
    }

    /** "Reconnect": ein ausdruecklicher Klick, wie Tools -> Connect Brain. */
    private fun neuVerbinden(project: Project?) {
        if (project != null && !EinwilligungFrage.fuerAktion(project)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            pruefeUndMelde(project, ausdruecklich = true)
        }
    }

    /** Wortlaut der Hinweise (englisch, wie die uebrigen des Plugins). */
    internal fun text(a: Ausgang): String = when (a) {
        Ausgang.Nichts -> ""
        is Ausgang.KontoBrainFehlt -> "The Brain ${a.id} no longer exists in your account."
        is Ausgang.Neu ->
            // "14 Tage leer, nichts verloren" nur, wenn die API den Grund genannt hat.
            if (a.testBrain && a.angelegt && a.leerAbgeraeumt) {
                "Your trial Brain was removed because it stayed empty and unused for 14 days. " +
                    "cachly created a new, empty Brain for you. Nothing was lost: the old one had no saved lessons."
            } else if (a.testBrain && a.angelegt) {
                "Your trial Brain ${a.alteId} no longer exists. cachly created a new, empty Brain for you."
            } else if (a.testBrain && a.leerAbgeraeumt) {
                "Your trial Brain was removed because it stayed empty and unused for 14 days. " +
                    "cachly switched to the Brain ${a.neueId} that your key already had. Nothing was lost: the old one had no saved lessons."
            } else if (a.testBrain) {
                "Your trial Brain ${a.alteId} no longer exists. cachly switched to the Brain ${a.neueId} that your key already had."
            } else {
                "The Brain ${a.alteId} no longer exists. The plugin now uses the Brain ${a.neueId}."
            }
        is Ausgang.Scheitert -> when {
            a.code == 403 || a.grund == "sign_in_required" ->
                "Your Brain no longer exists, and creating a new one needs a sign-in. " +
                    "Click Sign in; cachly will not retry in this IDE session."
            a.code == -1 || a.code >= 500 ->
                "Your Brain no longer exists, and cachly could not be reached to create a new one. " +
                    "It will not retry in this IDE session. Tools → Connect Brain tries again."
            else ->
                "Your Brain no longer exists, and cachly could not create a new one" +
                    (a.grund?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ". " +
                    "Open the dashboard to free a Brain or change your plan. It will not retry in this IDE session."
        }
    }

    /** Zeile im Brain Doctor bei 404: Grund und naechster Schritt statt "wrong instance ID?". */
    internal fun doctorGrund(apiKey: String): String =
        if (TestBrainUebernahme.istTestSchluessel(apiKey)) {
            "not found (404) — the key is accepted, but this trial Brain no longer exists. " +
                "Next: Tools → Connect Brain creates a new one."
        } else {
            "not found (404) — the key is accepted, but this Brain no longer exists in your account. " +
                "Next: Tools → Connect Brain picks or creates a Brain, or check cachly.dev/instances."
        }
}
