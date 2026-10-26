package com.chazlaunch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chazlaunch.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val sdEnabled by vm.sdEnabled.collectAsStateWithLifecycle()
    val disableKernel by vm.disableKernelPkgs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Options") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Storage")
            ToggleRow(
                title = "Mount SD card for /tmp & Nix cache",
                subtitle = vm.sdCard.removableVolume()?.let {
                    "${it.label} · ${it.freeBytes shr 20} MB free"
                } ?: "No removable SD card detected",
                checked = sdEnabled,
                enabled = vm.sdCard.isAvailable(),
                onCheckedChange = { vm.setSdCardEnabled(it) },
            )
            vm.sdCard.volumes().forEach { vol ->
                Text(
                    "• ${vol.label} ${if (vol.isRemovable) "(removable)" else "(internal)"} — " +
                        "${vol.freeBytes shr 20} MB free",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionTitle("NixOS tweaks")
            ToggleRow(
                title = "Disable kernel packages & module updates",
                subtitle = "Sets boot.isContainer, removes kernel/initrd/modules from the " +
                    "system closure. Recommended under PRoot (there is no real kernel).",
                checked = disableKernel,
                enabled = true,
                onCheckedChange = { vm.setDisableKernelPackages(it) },
            )
            Text(
                "Other tweaks applied automatically: documentation off, sandboxed builds " +
                    "off (PRoot can't sandbox), nix-command + flakes on, zram/swap off, " +
                    "systemd units trimmed. See /etc/nixos/chaz-tweaks.nix.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Compositor")
            Text(
                "Wayland display: wayland-0 · one Android task per Linux window.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
