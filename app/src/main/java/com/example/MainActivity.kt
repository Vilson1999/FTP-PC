package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.ui.BeamFtpApp
import com.example.ui.ServerViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val serverViewModel: ServerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as BeamFtpApplication
        lifecycleScope.launch {
            app.closeAppRequests.collect {
                finishAndRemoveTask()
            }
        }

        setContent {
            BeamFtpApp(viewModel = serverViewModel)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            serverViewModel.onAppBackgrounded()
        }
    }
}
