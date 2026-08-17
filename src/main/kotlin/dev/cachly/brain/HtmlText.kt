package dev.cachly.brain

/**
 * Entschaerft Text, bevor er in ein Swing-JLabel mit HTML geht.
 *
 * ─── WARUM DAS HIER STEHT UND NICHT DREIMAL ─────────────────────────────────
 *
 * Bis zum 17.08.2026 gab es diese vier Zeilen zweimal, als `private fun escHtml`
 * in ProactiveFileBriefing.kt und in ShowLessonsAction.kt. Beide identisch,
 * beide unabhaengig gepflegt. Beim Einbau des Server-Hinweises in
 * ShowBrainHealthAction waere die dritte Kopie entstanden.
 *
 * Zwei Kopien einer Regel sind kein Schoenheitsfehler: Wer eine davon
 * verbessert — etwa um Anfuehrungszeichen zu entschaerfen — verbessert die
 * andere nicht mit, und ab da verhalten sich zwei Fenster derselben
 * Anwendung verschieden. Deshalb eine Stelle.
 *
 * Swing rendert HTML in JLabel, sobald der Text mit <html> beginnt. Ein
 * ungeschuetztes `<` im Inhalt zerlegt dann das Layout, statt sichtbar zu sein.
 */
internal fun escHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
