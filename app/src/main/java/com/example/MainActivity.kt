package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.BetManagerViewModel
import com.example.ui.screens.BetManagerRoot
import com.example.ui.theme.BetManagerTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_EXIT_APP = "com.example.action.EXIT_APP"
        const val EXTRA_FULL_SCREEN = "extra_full_screen"
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val EXTRA_NOTIFICATION_HISTORY_ID = "extra_notification_history_id"
        const val EXTRA_GAME_ID = "extra_game_id"

        const val TAB_LIVE_MONITOR = 0
        const val TAB_STRATEGY = 1
        const val TAB_HOURLY_ANALYSIS = 2
    }

    private val requestedTabState = mutableIntStateOf(-1)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private fun handleExitIntent(incoming: Intent?): Boolean {
        if (incoming?.action == ACTION_EXIT_APP) {
            finishAllAndRemoveTask()
            return true
        }
        return false
    }

    private fun captureDeepLinkExtras(incoming: Intent?) {
        val openTab = incoming?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (openTab >= 0) {
            requestedTabState.intValue = openTab
        }
    }

    private fun finishAllAndRemoveTask() {
        runCatching { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        runCatching { finishAffinity() }
        runCatching { finishAndRemoveTask() }.onFailure { finish() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleExitIntent(intent)) return
        captureDeepLinkExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        handleExitIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleExitIntent(intent)) return
        captureDeepLinkExtras(intent)

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            BetManagerTheme {
                val vm: BetManagerViewModel = viewModel()
                val keepScreenAwake by vm.keepScreenAwake.collectAsStateWithLifecycle()
                val requestedTab = requestedTabState.intValue
                LaunchedEffect(keepScreenAwake) {
                    if (keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                BetManagerRoot(
                    viewModel = vm,
                    requestedTab = requestedTab,
                    onRequestedTabConsumed = { requestedTabState.intValue = -1 },
                    onSwitchToOverlay = { moveTaskToBack(true) },
                    onExitActivity = { finishAllAndRemoveTask() }
                )
            }
        }
    }
}
