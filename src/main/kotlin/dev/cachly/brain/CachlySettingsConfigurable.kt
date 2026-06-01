package dev.cachly.brain

import com.intellij.openapi.options.Configurable
import javax.swing.*

class CachlySettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var apiKeyField: JTextField? = null
    private var instanceIdField: JTextField? = null
    private var apiUrlField: JTextField? = null
    private var intervalField: JSpinner? = null
    private var showCostSavedBox: JCheckBox? = null
    private var ambientLearningBox: JCheckBox? = null

    override fun getDisplayName(): String = "Cachly Brain"

    override fun createComponent(): JComponent {
        val settings = CachlySettings.getInstance().state

        apiKeyField = JTextField(settings.apiKey, 40)
        instanceIdField = JTextField(settings.instanceId, 40)
        apiUrlField = JTextField(settings.apiUrl, 40)
        intervalField = JSpinner(SpinnerNumberModel(settings.refreshIntervalSec, 30, 3600, 30))
        showCostSavedBox = JCheckBox("Show estimated cost saved in status bar", settings.showCostSaved)
        ambientLearningBox = JCheckBox("Ambient learning (suggest saving repeated patterns)", settings.ambientLearning)

        panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(labeledRow("API Key:", apiKeyField!!))
            add(labeledRow("Instance ID:", instanceIdField!!))
            add(labeledRow("API URL:", apiUrlField!!))
            add(labeledRow("Refresh Interval (sec):", intervalField!!))
            add(showCostSavedBox!!)
            add(ambientLearningBox!!)
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

    override fun isModified(): Boolean {
        val s = CachlySettings.getInstance().state
        return apiKeyField?.text != s.apiKey ||
                instanceIdField?.text != s.instanceId ||
                apiUrlField?.text != s.apiUrl ||
                (intervalField?.value as? Int) != s.refreshIntervalSec ||
                showCostSavedBox?.isSelected != s.showCostSaved ||
                ambientLearningBox?.isSelected != s.ambientLearning
    }

    override fun apply() {
        val existing = CachlySettings.getInstance().state
        CachlySettings.getInstance().loadState(CachlySettings.State(
            apiKey = apiKeyField?.text ?: "",
            instanceId = instanceIdField?.text ?: "",
            apiUrl = apiUrlField?.text ?: "https://api.cachly.dev",
            refreshIntervalSec = (intervalField?.value as? Int) ?: 300,
            showCostSaved = showCostSavedBox?.isSelected ?: true,
            ambientLearning = ambientLearningBox?.isSelected ?: true,
            firstHitShown = existing.firstHitShown,
        ))
    }

    override fun reset() {
        val s = CachlySettings.getInstance().state
        apiKeyField?.text = s.apiKey
        instanceIdField?.text = s.instanceId
        apiUrlField?.text = s.apiUrl
        intervalField?.value = s.refreshIntervalSec
        showCostSavedBox?.isSelected = s.showCostSaved
        ambientLearningBox?.isSelected = s.ambientLearning
    }
}
