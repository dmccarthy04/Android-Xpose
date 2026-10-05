package com.example.androidxpose.Profile.ProfileActivity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.androidxpose.ui.theme.LocalAppTheme
import com.example.androidxpose.ui.theme.ProfileScreen
import com.example.androidxpose.ui.theme.ThemeManager

class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager.init(this)

        setContent {
            val currentTheme by remember { mutableStateOf(ThemeManager.current) }

            CompositionLocalProvider(LocalAppTheme provides currentTheme) {
                ProfileScreen(onGoHome = { finish() })
            }
        }
    }
}