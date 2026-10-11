package com.txdost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.txdost.ui.screens.ChatScreen
import com.txdost.ui.screens.SettingsScreen
import com.txdost.ui.screens.FileExplorerScreen
import com.txdost.ui.screens.SkillsScreen
import com.txdost.ui.screens.TerminalScreen
import com.txdost.ui.screens.TermuxSetupScreen
import com.txdost.ui.theme.FeaturesticTheme
import com.txdost.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — continue */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(applicationContext))
        super.onCreate(savedInstanceState)

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge()
        // Notification se convId nikalo
        val convIdFromNotif = intent?.getStringExtra(TxDostService.EXTRA_CONV_ID)

        setContent {
            val prefs = remember { com.txdost.data.AppPreferences(applicationContext) }
            val themeMode by prefs.themeMode.collectAsState(initial = "system")
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark"  -> true
                else    -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            FeaturesticTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Update gate: naya version ho to app khulne se pehle update screen
                    UpdateHost {
                        AppNavigation(initialConvId = convIdFromNotif)
                    }
                }
            }
        }
    }

    // Notification tap — app already open hai
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ViewModel ko batao kaunsa conv open karna hai
        val convId = intent.getStringExtra(TxDostService.EXTRA_CONV_ID)
        if (convId != null) {
            pendingConvId = convId
        }
        // NOTE: pendingConvId ek Compose state hai, isliye AppNavigation
        // khud re-run hoga aur conversation switch ho jayegi.
    }

    override fun onDestroy() {
        // Service ko band mat karo — background mein chalne do
        super.onDestroy()
    }

    companion object {
        // onNewIntent se convId pass karne ke liye (Compose state — recompose trigger karta hai)
        var pendingConvId by mutableStateOf<String?>(null)
    }
}

@Composable
fun AppNavigation(initialConvId: String? = null) {
    val navController = rememberNavController()
    val chatViewModel: ChatViewModel = viewModel()

    // Notification se aaya convId — switch karo
    LaunchedEffect(initialConvId) {
        if (initialConvId != null) {
            chatViewModel.switchConversation(initialConvId)
        }
    }

    // onNewIntent se aaya convId handle karo (state change pe dobara chalta hai)
    LaunchedEffect(MainActivity.pendingConvId) {
        val pending = MainActivity.pendingConvId
        if (pending != null) {
            chatViewModel.switchConversation(pending)
            MainActivity.pendingConvId = null
        }
    }

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                onNavigateToSettings  = { navController.navigate("settings") },
                onNavigateToFiles     = { navController.navigate("file_explorer") },
                onNavigateToSkills    = { navController.navigate("skills") },
                onNavigateToTerminal  = { navController.navigate("terminal") },
                chatViewModel         = chatViewModel
            )
        }
        composable("skills") {
            SkillsScreen(
                onBack = { navController.popBackStack() },
                chatViewModel = chatViewModel
            )
        }
        composable("terminal") {
            TerminalScreen(onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToTermuxSetup = { navController.navigate("termux_setup") },
                chatViewModel = chatViewModel
            )
        }
        composable("termux_setup") {
            TermuxSetupScreen(onBack = { navController.popBackStack() })
        }
        composable("file_explorer") {
            FileExplorerScreen(onBack = { navController.popBackStack() }, viewModel = chatViewModel)
        }
    }
}
