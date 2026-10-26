package com.chazlaunch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chazlaunch.ui.HomeScreen
import com.chazlaunch.ui.OptionsScreen
import com.chazlaunch.ui.SetupWizard
import com.chazlaunch.ui.theme.ChazTheme

/**
 * The home launcher. Declared with CATEGORY_HOME, so this is what the device
 * shows instead of the stock launcher once Chazlaunch is the default. It runs
 * the setup wizard on first launch, then the menu / app grid.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChazTheme {
                val vm: MainViewModel = viewModel()
                val setupComplete by vm.setupComplete.collectAsStateWithLifecycle()
                val nav = rememberNavController()

                val start = if (setupComplete) Routes.HOME else Routes.SETUP
                NavHost(navController = nav, startDestination = start) {
                    composable(Routes.SETUP) {
                        SetupWizard(
                            vm = vm,
                            onFinished = {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(Routes.SETUP) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable(Routes.HOME) {
                        HomeScreen(
                            vm = vm,
                            onOpenOptions = { nav.navigate(Routes.OPTIONS) },
                        )
                    }
                    composable(Routes.OPTIONS) {
                        OptionsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    object Routes {
        const val SETUP = "setup"
        const val HOME = "home"
        const val OPTIONS = "options"
    }
}
