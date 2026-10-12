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

/**
 * Macht aus einem HTML-Rumpf den Text fuer ein JLabel.
 *
 * ─── WARUM NICHT `"""<html>...""".trimIndent()` ─────────────────────────────
 *
 * Swing erkennt HTML nur, wenn der Text mit `<html>` BEGINNT (BasicHTML.
 * isHTMLString: erstes Zeichen `<`, Zeichen 5 `>`). `trimIndent()` rechnet die
 * gemeinsame Einrueckung NACH dem Einsetzen der `$werte` aus. Steht in einem
 * eingesetzten Wert eine Zeile ohne Einrueckung — ein mehrzeiliger Text, ein
 * selbst getrimmter Baustein — ist die gemeinsame Einrueckung 0. Dann bleiben
 * die 12 Leerzeichen vor `<html>` stehen, der Text beginnt nicht mehr mit `<`,
 * und das Fenster zeigt den Quelltext (belegt am 12.10.2026 im Fenster "Brain
 * Health": `<html>  <h2>Brain Overview</h2>  <table ...`).
 *
 * Hier wird erst getrimmt und dann umhuellt: Der Text beginnt immer mit
 * `<html>`, egal was in [body] steht. Der Rumpf enthaelt selbst kein `<html>`.
 */
internal fun htmlText(body: String): String = "<html>" + body.trim() + "</html>"
