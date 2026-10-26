package com.chazlaunch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.chazlaunch.MainViewModel
import com.chazlaunch.data.Prefs
import com.chazlaunch.owl.OwlEngine
import com.chazlaunch.owl.OwlLoader
import com.chazlaunch.owl.OwlWidgetView

/**
 * The home screen is a single OWL widget — the desktop (assets/widgets/
 * desktop.owl): a status bar, app cards and a dock, all editable on-device.
 * Its `apps` source is the launcher's app list; tapping a card launches it in
 * the guest. The dock's settings button runs the pseudo-command `@options`,
 * routed here to the Options screen instead of the guest.
 */
@Composable
fun HomeScreen(vm: MainViewModel, onOpenOptions: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.startGuest() }

    val apps = remember {
        buildList {
            add(mapOf("name" to "Terminal", "icon" to "▸", "command" to "foot"))
            add(mapOf("name" to "Package manager", "icon" to "⬇", "command" to "foot -e /root/.local/bin/chaz-pkg"))
            vm.agents
                .filter { it.id in Prefs.installedAgents }
                .forEach { add(mapOf("name" to it.displayName, "icon" to "🤖", "command" to "foot -e ${it.binary}")) }
        }
    }

    val onAction: (String) -> Unit = { cmd ->
        if (cmd == "@options") onOpenOptions() else vm.launchApp(cmd)
    }

    val desktop = remember(apps) { OwlEngine(OwlLoader.load(context, "desktop.owl"), apps, onAction) }

    OwlWidgetView(desktop, Modifier.fillMaxSize())
}
