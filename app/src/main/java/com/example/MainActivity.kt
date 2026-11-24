package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.BetManagerViewModel
import com.example.ui.screens.BetManagerRoot
import com.example.ui.theme.BetManagerTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_EXIT_APP = "com.example.action.EXIT_APP"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private fun handleExitIntent(incoming: Intent?): Boolean {
        if (incoming?.action == ACTION_EXIT_APP) {
            finishAllAndRemoveTask()
            return true
        }
        return false
    }

    private fun finishAllAndRemoveTask() {
        runCatching { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        runCatching { finishAffinity() }
        runCatching { finishAndRemoveTask() }.onFailure { finish() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExitIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handleExitIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleExitIntent(intent)) return

        val lightBars = SystemBarStyle.light(0xFFEBECEE.toInt(), 0xFFEBECEE.toInt())
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)

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
                LaunchedEffect(keepScreenAwake) {
                    if (keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                BetManagerRoot(
                    viewModel = vm,
                    onExitActivity = { finishAllAndRemoveTask() }
                )
            }
        }
    }
}
