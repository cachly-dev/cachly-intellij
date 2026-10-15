package dev.cachly.brain

import com.intellij.credentialStore.ACCESS_TO_KEY_CHAIN_DENIED
import com.intellij.credentialStore.CANNOT_UNLOCK_KEYCHAIN
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Der Ort, an dem der API-Schluessel liegt.
 *
 * WARUM (GROW-035, Pruefung 13.08.2026): Der Schluessel stand im Klartext in
 * `cachly-brain.xml` (Teil der gespeicherten Einstellungen). Das
 * JPasswordField in den Einstellungen maskierte nur die Anzeige. Der
 * JetBrains-Marktplatz verlangt fuer Zugangsdaten den Passwortspeicher der IDE.
 *
 * Eine Schnittstelle, damit die Tests ohne IDE laufen: dort steht statt des
 * Passwortspeichers eine Speicher-Ablage (siehe SpeicherAblage in den Tests).
 */
interface Ablage {
    /**
     * Der gespeicherte Schluessel; `null` heisst: es ist keiner gespeichert.
     * Wirft, wenn der Speicher nicht lesbar ist (gesperrt, verweigert). Das ist
     * etwas anderes als "leer": ein leerer Speicher darf zu einem neuen
     * Test-Schluessel fuehren, ein nicht lesbarer nicht.
     */
    fun lesen(): String?

    /** Speichert den Schluessel. `null` oder leer loescht ihn. */
    fun schreiben(wert: String?)

    /**
     * Ob geschriebene Werte einen Neustart der IDE ueberleben. Im Modus
     * "Passwoerter nach Neustart vergessen" ist das nicht so; dann darf die
     * Uebernahme den alten Klartext-Wert NICHT loeschen (Bestandsschutz).
     */
    val dauerhaft: Boolean get() = true
}

/** Der Passwortspeicher der IDE (Schluesselbund, KeePass oder Master-Passwort — je nach Einstellung des Nutzers). */
object PasswortSpeicher : Ablage {

    private val attribute: CredentialAttributes
        get() = CredentialAttributes(generateServiceName("cachly", "apiKey"))

    override fun lesen(): String? {
        val anmeldedaten = PasswordSafe.instance.get(attribute) ?: return null
        // Diese beiden sind keine "leer"-Antworten, sondern: der Speicher ist
        // zu, der Nutzer hat den Zugriff verweigert. Als "leer" gelesen wuerde
        // das Plugin einen neuen Test-Schluessel holen und den echten ueberschreiben.
        if (anmeldedaten === ACCESS_TO_KEY_CHAIN_DENIED || anmeldedaten === CANNOT_UNLOCK_KEYCHAIN) {
            throw IllegalStateException("Der Passwortspeicher der IDE ist nicht lesbar (gesperrt oder verweigert).")
        }
        return anmeldedaten.getPasswordAsString()?.takeIf { it.isNotEmpty() }
    }

    override fun schreiben(wert: String?) {
        PasswordSafe.instance.setPassword(attribute, wert?.takeIf { it.isNotEmpty() })
    }

    override val dauerhaft: Boolean
        get() = !PasswordSafe.instance.isMemoryOnly
}
