package dev.cachly.brain

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Die einmalige Frage vor der ersten Datenuebertragung (GROW-035).
 *
 * Zwei Haekchen: Funktion (vorausgewaehlt) und Nutzungsstatistik (nicht
 * vorausgewaehlt). OK speichert beide Haekchen. Schliessen oder Abbrechen heisst
 * "nein" auf beide — nichts wird gesendet, die Frage kommt nicht wieder. Man
 * aendert es unter Settings → Tools → Cachly Brain.
 *
 * Der Text ist absichtlich kurz und nennt, WAS gesendet wird, WOZU und WIE man es
 * aendert. Die Aufzaehlung muss stimmen: Wer hier etwas an den Netzwegen aendert
 * (CachlyApiClient), aendert auch diesen Text.
 */
class EinwilligungDialog(project: Project?) : DialogWrapper(project, false) {

    private val funktionBox = JBCheckBox("Use the Brain (file briefings, lessons, health checks)", true)
    private val statistikBox = JBCheckBox("Share usage statistics", false)

    /** Haekchen "Funktion" — nur nach [showAndGet] sinnvoll. */
    val funktion: Boolean get() = funktionBox.isSelected

    /** Haekchen "Statistik" — nur nach [showAndGet] sinnvoll. */
    val statistik: Boolean get() = statistikBox.isSelected

    init {
        title = "Cachly Brain — what may it send?"
        setOKButtonText("Save my choice")
        setCancelButtonText("Send nothing")
        // KEIN Standard-Knopf: Der Dialog erscheint beim Projektstart und nimmt sich
        // den Fokus. In der echten IDE (15.10.2026) war er nach wenigen Sekunden mit
        // "OK" beantwortet, ohne dass jemand ihn gelesen hatte — ein Enter, das fuer
        // ein anderes Fenster gedacht war. Eine Einwilligung braucht einen Klick.
        okAction.putValue(DEFAULT_ACTION, null)
        init()
    }

    /** Der Fokus liegt auf dem ersten Haekchen: Leertaste schaltet, Enter tut nichts. */
    override fun getPreferredFocusedComponent(): JComponent = funktionBox

    override fun createCenterPanel(): JComponent {
        val kopf = JBLabel(
            htmlText(
                "<body style='width: 460px'>" +
                    "Cachly Brain connects your IDE to your Brain on <b>api.cachly.dev</b> (EU servers). " +
                    "<b>Nothing is sent until you answer.</b> Closing this window counts as <b>no</b> to both questions.</body>",
            ),
        )

        val funktionText = JBLabel(
            htmlText(
                "<body style='width: 440px'>" +
                    "<b>What is sent:</b> the path of each file you open, relative to the project (for example " +
                    "<code>src/app/main.ts</code>), to look up lessons that match it; the lessons you save or " +
                    "recall; health checks every few minutes while the IDE is in use; and the request for a free " +
                    "trial Brain on first start. <b>File contents are never sent.</b><br>" +
                    "<b>Why:</b> so the Brain can warn you about a known failure pattern in a file, and keep your lessons.<br>" +
                    "Your API key is kept in the password safe of your IDE, not in a settings file." +
                    "</body>",
            ),
        ).apply { border = JBUI.Borders.emptyLeft(24) }

        val statistikText = JBLabel(
            htmlText(
                "<body style='width: 440px'>" +
                    "<b>What is sent:</b> the name of an event (for example <code>session_start</code> or " +
                    "<code>learn_from_attempts</code>), the plugin version, your instance ID and your API key, so the " +
                    "event is counted for your account. <b>Never</b> file names, file contents or code.<br>" +
                    "<b>Why:</b> so cachly can see whether the plugin is used and fix what is not." +
                    "</body>",
            ),
        ).apply { border = JBUI.Borders.emptyLeft(24) }

        val fuss = JBLabel(
            htmlText(
                "<body style='width: 460px'>You can change both at any time: <b>Settings → Tools → Cachly Brain</b>.</body>",
            ),
        )

        val datenschutz = HyperlinkLabel("Privacy policy").apply { setHyperlinkTarget(DATENSCHUTZ_URL) }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
            fun zeile(c: JComponent, oben: Int) {
                if (oben > 0) add(Box.createVerticalStrut(oben))
                c.alignmentX = 0f
                add(c)
            }
            zeile(kopf, 0)
            zeile(funktionBox, 12)
            zeile(funktionText, 2)
            zeile(statistikBox, 12)
            zeile(statistikText, 2)
            zeile(fuss, 12)
            zeile(JPanel(BorderLayout()).apply { add(datenschutz, BorderLayout.WEST); isOpaque = false }, 2)
        }
    }
}

/**
 * Wo und wann die Frage gestellt wird. Genau eine Frage gleichzeitig, auch wenn
 * mehrere Projekte oder Aktionen zugleich danach fragen.
 */
object EinwilligungFrage {

    private val laeuft = AtomicBoolean(false)

    /**
     * Zeigt den Dialog, wenn noch nicht beantwortet, und haelt die Antwort fest.
     * Muss auf dem EDT laufen. Ist schon ein Dialog offen, passiert nichts: der
     * Nutzer antwortet dort.
     */
    private fun zeigen(project: Project?) {
        val state = CachlySettings.getInstance().state
        if (state.einwilligungBeantwortet) return
        if (!laeuft.compareAndSet(false, true)) return
        try {
            val dialog = EinwilligungDialog(project?.takeUnless { it.isDisposed })
            val ok = dialog.showAndGet()
            einwilligungBeantworten(state, ok, dialog.funktion, dialog.statistik)
            // Die Statusleiste fragte beim Start, als noch nichts erlaubt war, und
            // zeigte "off" — sonst bis zum naechsten 300-s-Takt (in der echten IDE
            // gesehen, 15.10.2026). Nach einem Ja fragt sie sofort.
            if (state.funktionErlaubt) CachlyStatusBarWidget.jetztAuffrischen()
        } finally {
            laeuft.set(false)
        }
    }

    /**
     * Beim ersten Projektstart: fragt (auf dem EDT, wenn gerade kein anderer
     * Dialog offen ist). Gibt es danach ein Ja zur Funktion, laeuft [danach]
     * — das Onboarding fuer dieses Projekt.
     */
    fun beimStartFragen(project: Project, danach: (Project) -> Unit) {
        if (CachlySettings.getInstance().state.einwilligungBeantwortet) return
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater
                zeigen(project)
                if (funktionJetzt()) danach(project)
            },
            ModalityState.nonModal(),
        )
    }

    /**
     * Die Sperre fuer ausdrueckliche Nutzeraktionen. Von jedem Thread
     * aufrufbar; vom Hintergrund wartet der Aufruf auf die Antwort. Gibt zurueck,
     * ob die Aktion senden darf.
     */
    fun fuerAktion(project: Project?): Boolean {
        val app = ApplicationManager.getApplication()
        return aktionErlaubt(
            CachlySettings.getInstance().state,
            fragen = {
                if (app.isDispatchThread) zeigen(project)
                else app.invokeAndWait { zeigen(project) }
            },
            hinweis = { hinweisAus(project) },
        )
    }

    /** "Aus" ist eine Antwort. Der Hinweis sagt nur, wo man es wieder einschaltet — er fragt nicht noch einmal. */
    private fun hinweisAus(project: Project?) {
        val gruppe = NotificationGroupManager.getInstance().getNotificationGroup("Cachly Brain Ambient") ?: return
        gruppe.createNotification(
            "Cachly Brain is switched off",
            "You chose not to let Cachly Brain talk to the Brain, so nothing was sent. " +
                "You can switch it on in Settings → Tools → Cachly Brain.",
            NotificationType.INFORMATION,
        ).addAction(
            NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CachlySettingsConfigurable::class.java)
            },
        ).notify(project)
    }
}

/**
 * Stellt beim ersten Projektstart die Frage — einmal je Installation. Die
 * Antwort steht in den Einstellungen; ab dann fragt dieser Start nichts mehr.
 *
 * Laeuft neben ProactiveRecallStartup. Das Onboarding dort wartet nicht auf
 * diese Frage, es unterbleibt ohne Einwilligung (ProactiveRecallStartup.verbinden)
 * und startet nach einem Ja von hier aus.
 */
class EinwilligungStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        EinwilligungFrage.beimStartFragen(project) { ProactiveRecallStartup.verbinden(it) }
    }
}
