package dev.cachly.brain

/**
 * Ersatz fuer den Passwortspeicher der IDE in den Tests: haelt den Wert im
 * Speicher und kann gezielt versagen, so wie ein gesperrter Schluesselbund.
 */
class SpeicherAblage(var wert: String? = null) : Ablage {

    /** Das Zuruecklesen liefert `null`, obwohl geschrieben wurde. */
    var lesenGibtNull = false

    /** Das Lesen wirft, wie bei einem gesperrten oder verweigerten Speicher. */
    var lesenWirft = false

    /** Das Schreiben wirft. */
    var schreibenWirft = false

    /** Falsch = "Passwoerter nach Neustart vergessen". */
    var dauerhaftEin = true

    /** Jeder Schreibaufruf in der Reihenfolge, `null` = geloescht. */
    val schreibungen = mutableListOf<String?>()

    override fun lesen(): String? {
        if (lesenWirft) throw IllegalStateException("gesperrt")
        return if (lesenGibtNull) null else wert
    }

    override fun schreiben(wert: String?) {
        if (schreibenWirft) throw IllegalStateException("nicht beschreibbar")
        schreibungen += wert
        this.wert = wert?.takeIf { it.isNotEmpty() }
    }

    override val dauerhaft: Boolean get() = dauerhaftEin
}
