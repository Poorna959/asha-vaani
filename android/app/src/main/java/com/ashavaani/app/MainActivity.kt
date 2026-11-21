package com.ashavaani.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ashavaani.app.ui.navigation.AshaVaaniAppShell
import com.ashavaani.app.ui.theme.AshaVaaniTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AshaVaaniTheme {
                AshaVaaniAppShell()
            }
        }
    }
}
