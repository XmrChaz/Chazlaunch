package com.chazlaunch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chazlaunch.MainViewModel
import com.chazlaunch.proot.InstallManager

/**
 * First-launch wizard: choose agents + SD offloading, then watch the rootfs
 * download, Nix bootstrap and agent installs run to completion.
 */
@Composable
fun SetupWizard(vm: MainViewModel, onFinished: () -> Unit) {
    val install by vm.installState.collectAsStateWithLifecycle()
    val selected by vm.selectedAgents.collectAsStateWithLifecycle()
    val sdEnabled by vm.sdEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(install) {
        if (install is InstallManager.Phase.Done) onFinished()
    }

    Scaffold { padding ->
        when (val phase = install) {
            is InstallManager.Phase.Idle ->
                AgentChooser(vm, selected, sdEnabled, padding)
            is InstallManager.Phase.Failed ->
                FailurePane(phase.reason, onRetry = vm::runInstall, padding)
            else ->
                ProgressPane(phase, padding)
        }
    }
}

@Composable
private fun AgentChooser(
    vm: MainViewModel,
    selected: Set<String>,
    sdEnabled: Boolean,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Welcome to Chazlaunch", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text(
                "This sets up a full NixOS (aarch64) running rootless under PRoot, " +
                    "with its own Wayland compositor so Linux apps open as Android windows.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Use SD card for /tmp & Nix cache", fontWeight = FontWeight.Medium)
                        Text(
                            if (vm.sdCard.isAvailable()) "Removable storage detected"
                            else "No removable storage detected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = sdEnabled,
                        onCheckedChange = { vm.setSdCardEnabled(it) },
                        enabled = vm.sdCard.isAvailable(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("AI agents to install", style = MaterialTheme.typography.titleMedium)
        }

        items(vm.agents, key = { it.id }) { agent ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = agent.id in selected,
                        onCheckedChange = { vm.toggleAgent(agent.id) },
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(agent.displayName, fontWeight = FontWeight.Medium)
                        Text(agent.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(agent.method.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(onClick = vm::runInstall, modifier = Modifier.fillMaxWidth()) {
                Text("Install NixOS + ${selected.size} agents")
            }
        }
    }
}

@Composable
private fun ProgressPane(phase: InstallManager.Phase, padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (title, detail, progress) = describe(phase)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun describe(phase: InstallManager.Phase): Triple<String, String, Float?> = when (phase) {
    is InstallManager.Phase.Downloading ->
        Triple("Downloading NixOS rootfs", "${phase.mbDone} / ${phase.mbTotal} MB", phase.pct / 100f)
    is InstallManager.Phase.Extracting ->
        Triple("Unpacking rootfs", phase.entry, null)
    is InstallManager.Phase.Bootstrapping ->
        Triple("Bootstrapping Nix", phase.line, null)
    is InstallManager.Phase.InstallingAgents ->
        Triple("Installing agents", "${phase.current} (${phase.done}/${phase.total})",
            phase.done.toFloat() / phase.total)
    else -> Triple("Working…", "", null)
}

@Composable
private fun FailurePane(reason: String, onRetry: () -> Unit, padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Install failed", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(reason, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
