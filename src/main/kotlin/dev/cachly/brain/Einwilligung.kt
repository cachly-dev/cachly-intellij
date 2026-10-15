package dev.cachly.brain

/**
 * Einwilligung (GROW-035, Entscheidung Heinrich 15.10.2026).
 *
 * ZWEI Fragen in EINEM Dialog:
 *   - Funktion: Datei-Briefings und alle Aufrufe an das Brain (api.cachly.dev).
 *     In der Frage vorausgewaehlt.
 *   - Nutzungsstatistik: nur Ereignisnamen. In der Frage NICHT vorausgewaehlt.
 *
 * Solange die Frage nicht beantwortet ist, ist NICHTS erlaubt — auch nicht
 * "einmal noch". Schliessen oder Abbrechen des Dialogs zaehlt als Antwort
 * "nein" auf beide Fragen; die Frage kommt dann nicht wieder, aendern geht in
 * Settings → Tools → Cachly Brain.
 *
 * Diese Datei enthaelt nur reine Funktionen ohne IDE, damit sie ohne IDE
 * testbar sind. Der Dialog steht in EinwilligungDialog.kt.
 */

/** Seite mit der Datenschutzerklaerung (web/app/legal/datenschutz). */
const val DATENSCHUTZ_URL = "https://cachly.dev/legal/datenschutz"

/** Darf das Plugin mit dem Brain sprechen (Briefings, Lektionen, Gesundheitsabfrage, Test-Schluessel)? */
fun darfFunktion(state: CachlySettings.State): Boolean =
    state.einwilligungBeantwortet && state.funktionErlaubt

/** Darf das Plugin Ereignisnamen als Nutzungsstatistik senden? */
fun darfStatistik(state: CachlySettings.State): Boolean =
    state.einwilligungBeantwortet && state.statistikErlaubt

/** Wie [darfFunktion], mit dem Stand der laufenden IDE. */
fun funktionJetzt(): Boolean = darfFunktion(CachlySettings.getInstance().state)

/** Wie [darfStatistik], mit dem Stand der laufenden IDE. */
fun statistikJetzt(): Boolean = darfStatistik(CachlySettings.getInstance().state)

/**
 * Haelt die Antwort fest. Immer "beantwortet", egal wie die Antwort lautet.
 *
 * [bestaetigt] ist falsch, wenn der Dialog geschlossen oder abgebrochen wurde:
 * dann gilt "nein" auf beide Fragen, was auch immer die Haekchen zeigten.
 */
fun einwilligungBeantworten(
    state: CachlySettings.State,
    bestaetigt: Boolean,
    funktion: Boolean,
    statistik: Boolean,
) {
    state.einwilligungBeantwortet = true
    state.funktionErlaubt = bestaetigt && funktion
    state.statistikErlaubt = bestaetigt && statistik
}

/**
 * Die Sperre fuer ausdrueckliche Nutzeraktionen (Brain Doctor, Konto
 * verknuepfen, Brain verbinden, Brain-Fenster ...). Gibt zurueck, ob die Aktion
 * senden darf.
 *
 * - Noch nicht beantwortet: [fragen] zeigt den Dialog. Beantwortet er nicht
 *   (zum Beispiel weil gerade ein anderer Dialog offen ist), darf die Aktion nicht senden.
 * - Beantwortet mit "nein" auf die Funktion: [hinweis] sagt, wo man es einschaltet.
 *   Die Frage wird NICHT noch einmal gestellt — "abgelehnt" bleibt abgelehnt.
 */
fun aktionErlaubt(state: CachlySettings.State, fragen: () -> Unit, hinweis: () -> Unit): Boolean {
    if (!state.einwilligungBeantwortet) fragen()
    if (!state.einwilligungBeantwortet) return false
    if (!darfFunktion(state)) {
        hinweis()
        return false
    }
    return true
}
