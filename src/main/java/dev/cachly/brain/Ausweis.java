package dev.cachly.brain;

import com.intellij.credentialStore.CredentialAttributes;

/**
 * Baut die Kennung, unter der der API-Schluessel im Passwortspeicher liegt.
 *
 * <p>WARUM IN JAVA (0.9.11, 09.11.2026): Der Kotlin-Aufruf
 * {@code CredentialAttributes(name)} wird gegen IntelliJ 2024.1 in den
 * Konstruktor mit Standardwerten uebersetzt:
 * {@code (String, String, Class, boolean, int, DefaultConstructorMarker)}.
 * IntelliJ 2026.3 markiert ihn als veraltet; der Plugin-Pruefer meldet ihn
 * ("1 usage of deprecated API"), und 0.9.10 kam damit nicht durch den Marktplatz.
 *
 * <p>Java bindet genau an {@code CredentialAttributes(String)}. Den gibt es in
 * 2024.1 (ueber {@code @JvmOverloads}) und in 2026.3 ohne Abwertung. Geprueft
 * mit javap gegen beide Fassungen.
 */
final class Ausweis {
    private Ausweis() {
    }

    static CredentialAttributes fuer(String dienstName) {
        return new CredentialAttributes(dienstName);
    }
}
