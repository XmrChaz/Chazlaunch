package com.chazlaunch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chazlaunch.agents.Agent
import com.chazlaunch.agents.AgentCatalog
import com.chazlaunch.compositor.CompositorService
import com.chazlaunch.data.Prefs
import com.chazlaunch.proot.InstallManager
import com.chazlaunch.storage.SdCardManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val installManager = InstallManager(app)
    val sdCard = SdCardManager(app)

    val installState get() = installManager.state

    private val _setupComplete = MutableStateFlow(Prefs.setupComplete)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    private val _selectedAgents = MutableStateFlow(
        Prefs.selectedAgents.ifEmpty { AgentCatalog.defaultSelection(app) },
    )
    val selectedAgents: StateFlow<Set<String>> = _selectedAgents.asStateFlow()

    val agents: List<Agent> = AgentCatalog.load(app)

    fun toggleAgent(id: String) {
        val next = _selectedAgents.value.toMutableSet()
        if (!next.add(id)) next.remove(id)
        _selectedAgents.value = next
        Prefs.selectedAgents = next
    }

    /** Kick off (or resume) the first-launch install with the chosen agents. */
    fun runInstall() {
        viewModelScope.launch {
            installManager.run(_selectedAgents.value)
            _setupComplete.value = Prefs.setupComplete
        }
    }

    /** Boot the compositor + guest login session so Linux apps can be launched. */
    fun startGuest() = CompositorService.startSession(getApplication())

    /** Launch a Linux GUI command; its windows arrive as Android tasks. */
    fun launchApp(command: String) = CompositorService.launch(getApplication(), command)

    // --- Options ------------------------------------------------------------

    private val _sdEnabled = MutableStateFlow(Prefs.sdCardEnabled)
    val sdEnabled: StateFlow<Boolean> = _sdEnabled.asStateFlow()

    fun setSdCardEnabled(enabled: Boolean): Boolean {
        val ok = sdCard.setEnabled(enabled)
        if (ok) _sdEnabled.value = enabled
        return ok
    }

    private val _disableKernelPkgs = MutableStateFlow(Prefs.disableKernelPackages)
    val disableKernelPkgs: StateFlow<Boolean> = _disableKernelPkgs.asStateFlow()

    fun setDisableKernelPackages(disabled: Boolean) {
        Prefs.disableKernelPackages = disabled
        _disableKernelPkgs.value = disabled
        // Re-apply the tweak file so the next nixos-rebuild picks it up.
        viewModelScope.launch { applyNixTweaks() }
    }

    private fun applyNixTweaks() {
        CompositorService.launch(
            getApplication(),
            "nixos-rebuild switch --flake /etc/nixos#chaz || true",
        )
    }
}
