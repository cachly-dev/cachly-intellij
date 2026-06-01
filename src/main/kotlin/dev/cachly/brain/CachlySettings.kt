package dev.cachly.brain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CachlySettings", storages = [Storage("cachly-brain.xml")])
class CachlySettings : PersistentStateComponent<CachlySettings.State> {

    data class State(
        var apiKey: String = "",
        var instanceId: String = "",
        var apiUrl: String = "https://api.cachly.dev",
        var refreshIntervalSec: Int = 300,
        var showCostSaved: Boolean = true,
        var ambientLearning: Boolean = true,
        var firstHitShown: Boolean = false,
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(): CachlySettings =
            ApplicationManager.getApplication().getService(CachlySettings::class.java)
    }
}
