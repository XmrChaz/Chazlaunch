package com.chazlaunch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chazlaunch.MainViewModel

/** A launchable entry in the menu. [command] runs inside the NixOS guest. */
data class LauncherItem(val label: String, val emoji: String, val command: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpenOptions: () -> Unit) {
    val sdEnabled by vm.sdEnabled.collectAsStateWithLifecycle()

    // Start the guest/compositor as soon as the home screen is shown.
    LaunchedEffect(Unit) { vm.startGuest() }

    val installed = androidx.compose.runtime.remember {
        val base = listOf(
            LauncherItem("Terminal", "▶", "foot"),
            LauncherItem("Files", "📁", "nautilus"),
            LauncherItem("Firefox", "🦊", "firefox"),
            LauncherItem("Text Editor", "📝", "gnome-text-editor"),
        )
        val agentItems = vm.agents
            .filter { it.id in com.chazlaunch.data.Prefs.installedAgents }
            .map { LauncherItem(it.displayName, "🤖", "foot -e ${it.binary}") }
        base + agentItems
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chazlaunch") },
                actions = {
                    IconButton(onClick = onOpenOptions) {
                        Icon(Icons.Default.Settings, contentDescription = "Options")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.launchApp("foot") },
                icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                text = { Text("Open terminal") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sdEnabled) {
                Text(
                    "SD card offload: on",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(installed, key = { it.label }) { item ->
                    AppTile(item) { vm.launchApp(item.command) }
                }
            }
        }
    }
}

@Composable
private fun AppTile(item: LauncherItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.aspectRatio(1f).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.emoji, style = MaterialTheme.typography.headlineMedium)
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
