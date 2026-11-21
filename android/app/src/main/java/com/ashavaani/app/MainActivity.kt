package com.ashavaani.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ashavaani.app.ui.navigation.AshaVaaniAppShell
import com.ashavaani.app.ui.theme.AshaVaaniTheme

class MainActivity : ComponentActivity() {
    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer = AppContainer(applicationContext)
        setContent {
            AshaVaaniTheme {
                AshaVaaniAppShell(container = appContainer)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && ::appContainer.isInitialized) {
            appContainer.release()
        }
        super.onDestroy()
    }
}
