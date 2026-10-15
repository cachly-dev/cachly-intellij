package dev.cachly.brain

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.HyperlinkLabel
import javax.swing.*

class CachlySettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var apiKeyField: JPasswordField? = null
    private var instanceIdField: JTextField? = null
    private var apiUrlField: JTextField? = null
    private var intervalField: JSpinner? = null
    private var showCostSavedBox: JCheckBox? = null
    private var ambientLearningBox: JCheckBox? = null
    private var proactiveBriefingBox: JCheckBox? = null
    private var quietModeBox: JCheckBox? = null
    private var funktionBox: JCheckBox? = null
    private var statistikBox: JCheckBox? = null

    override fun getDisplayName(): String = "Cachly Brain"

    override fun createComponent(): JComponent {
        val settings = CachlySettings.getInstance().state

        // Masked input — the key is a credential and must not be shoulder-surfable.
        apiKeyField = JPasswordField(settings.apiKey, 40)
        instanceIdField = JTextField(settings.instanceId, 40)
        apiUrlField = JTextField(settings.apiUrl, 40)
        intervalField = JSpinner(SpinnerNumberModel(settings.refreshIntervalSec, 30, 3600, 30))
        showCostSavedBox = JCheckBox("Show estimated cost saved in status bar", settings.showCostSaved)
        ambientLearningBox = JCheckBox("Ambient learning (suggest saving repeated patterns)", settings.ambientLearning)
        proactiveBriefingBox = JCheckBox("Proactive briefing (top lessons on project open, warnings on risky file open)", settings.proactiveBriefing)
        quietModeBox = JCheckBox("Quiet mode (no proactive popups — status bar & Brain panel still update)", settings.quietMode)
        funktionBox = JCheckBox(
            "Use the Brain: file briefings, lessons, health checks (sends file paths and lessons to api.cachly.dev, never file contents)",
            settings.funktionErlaubt,
        )
        statistikBox = JCheckBox(
            "Share usage statistics (event names with plugin version, instance ID and API key — never file names or contents)",
            settings.statistikErlaubt,
        )
        val antwortHinweis = JLabel(
            if (settings.einwilligungBeantwortet) "Your choice is saved. You can change it here at any time."
            else "Not answered yet — nothing is sent until you choose (a question appears when a project opens).",
        )
        val datenschutz = HyperlinkLabel("Privacy policy").apply { setHyperlinkTarget(DATENSCHUTZ_URL) }

        panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(labeledRow("API Key:", apiKeyField!!))
            add(labeledRow("Instance ID:", instanceIdField!!))
            add(labeledRow("API URL:", apiUrlField!!))
            add(labeledRow("Refresh Interval (sec):", intervalField!!))
            add(showCostSavedBox!!)
            add(ambientLearningBox!!)
            add(proactiveBriefingBox!!)
            add(quietModeBox!!)
            add(Box.createVerticalStrut(12))
            add(JLabel("Data sent to cachly").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(funktionBox!!)
            add(statistikBox!!)
            add(antwortHinweis)
            add(datenschutz)
        }
        return panel!!
    }

    private fun labeledRow(label: String, field: JComponent): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel(label))
            add(Box.createHorizontalStrut(8))
            add(field)
        }
    }

    private fun apiKeyText(): String = apiKeyField?.password?.let { String(it) } ?: ""

    override fun isModified(): Boolean {
        val s = CachlySettings.getInstance().state
        return apiKeyText() != s.apiKey ||
                instanceIdField?.text != s.instanceId ||
                apiUrlField?.text != s.apiUrl ||
                (intervalField?.value as? Int) != s.refreshIntervalSec ||
                showCostSavedBox?.isSelected != s.showCostSaved ||
                ambientLearningBox?.isSelected != s.ambientLearning ||
                proactiveBriefingBox?.isSelected != s.proactiveBriefing ||
                quietModeBox?.isSelected != s.quietMode ||
                funktionBox?.isSelected != s.funktionErlaubt ||
                statistikBox?.isSelected != s.statistikErlaubt
    }

    override fun apply() {
        val existing = CachlySettings.getInstance().state
        val funktion = funktionBox?.isSelected ?: existing.funktionErlaubt
        val statistik = statistikBox?.isSelected ?: existing.statistikErlaubt
        val geaendert = funktion != existing.funktionErlaubt || statistik != existing.statistikErlaubt
        // uebernehmen statt loadState: ein geleertes Schluesselfeld soll den Schluessel loeschen.
        // copy statt neuem State: Felder, die das Formular nicht kennt (trialEndsAt,
        // onboardingAttempts, onboardingDeclined, Hinweis-Schalter, letzter guter
        // Stand), gingen sonst bei jedem Speichern verloren.
        val schluessel = apiKeyText()
        CachlySettings.getInstance().uebernehmen(existing.copy(
            apiKey = schluessel,
            // Wer das Feld leert, will den Schluessel los sein — auch den Rueckfall in der Datei.
            apiKeyAlt = if (schluessel.isBlank()) "" else existing.apiKeyAlt,
            instanceId = instanceIdField?.text ?: "",
            apiUrl = apiUrlField?.text ?: "https://api.cachly.dev",
            refreshIntervalSec = (intervalField?.value as? Int) ?: 300,
            showCostSaved = showCostSavedBox?.isSelected ?: true,
            ambientLearning = ambientLearningBox?.isSelected ?: true,
            proactiveBriefing = proactiveBriefingBox?.isSelected ?: true,
            quietMode = quietModeBox?.isSelected ?: false,
            // Eine Aenderung an den beiden Haekchen ist eine Antwort.
            einwilligungBeantwortet = existing.einwilligungBeantwortet || geaendert,
            funktionErlaubt = funktion,
            statistikErlaubt = statistik,
        ))
        // Wer die Funktion hier einschaltet, bekommt das Onboarding wie nach einem
        // Ja im Dialog — in den Projekten, die schon offen sind.
        if (funktion && !existing.funktionErlaubt) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) ProactiveRecallStartup.verbinden(project)
            }
            CachlyStatusBarWidget.jetztAuffrischen()
        }
    }

    override fun reset() {
        val s = CachlySettings.getInstance().state
        apiKeyField?.text = s.apiKey
        instanceIdField?.text = s.instanceId
        apiUrlField?.text = s.apiUrl
        intervalField?.value = s.refreshIntervalSec
        showCostSavedBox?.isSelected = s.showCostSaved
        ambientLearningBox?.isSelected = s.ambientLearning
        proactiveBriefingBox?.isSelected = s.proactiveBriefing
        quietModeBox?.isSelected = s.quietMode
        funktionBox?.isSelected = s.funktionErlaubt
        statistikBox?.isSelected = s.statistikErlaubt
    }
}
