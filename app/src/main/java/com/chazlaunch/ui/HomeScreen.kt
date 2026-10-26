package com.chazlaunch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chazlaunch.MainViewModel
import com.chazlaunch.data.Prefs
import com.chazlaunch.owl.OwlEngine
import com.chazlaunch.owl.OwlLoader
import com.chazlaunch.owl.OwlWidgetView

/**
 * The home screen is composed of OWL widgets (see assets/widgets): a clock face
 * and the app drawer. The drawer's app list is fed to the widgets' `apps`
 * source, and tapping an entry launches it in the NixOS guest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpenOptions: () -> Unit) {
    val context = LocalContext.current

    // Start the guest/compositor as soon as the home screen is shown.
    LaunchedEffect(Unit) { vm.startGuest() }

    // App list exposed to widgets via the `apps` source.
    val apps = remember {
        buildList {
            add(mapOf("name" to "Terminal", "icon" to "▶", "command" to "foot"))
            vm.agents
                .filter { it.id in Prefs.installedAgents }
                .forEach { add(mapOf("name" to it.displayName, "icon" to "🤖", "command" to "foot -e ${it.binary}")) }
        }
    }

    val clock = remember { OwlEngine(OwlLoader.load(context, "clock.owl"), apps, vm::launchApp) }
    val drawer = remember(apps) { OwlEngine(OwlLoader.load(context, "drawer.owl"), apps, vm::launchApp) }

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
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OwlWidgetView(clock, Modifier.fillMaxWidth())
            OwlWidgetView(drawer, Modifier.fillMaxWidth())
        }
    }
}
