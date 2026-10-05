package com.example.androidxpose.ui.theme

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class AppTheme(
    val isDark: Boolean,

    val backgroundBrush: Brush,
    val cardColor: Color,
    val borderColor: Color,
    val navBarColor: Color,
    val cardBackground: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    val surfaceColor: Color,

)

val DarkTheme = AppTheme(
    isDark          = true,
    backgroundBrush = Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B))),
    cardColor       = Color(0xFF1E293B),
    borderColor     = Color.White.copy(alpha = 0.10f),
    navBarColor     = Color(0xFF1E293B).copy(alpha = 0.98f),
    textPrimary     = Color.White,
    textSecondary   = Color(0xFF94A3B8),
    textTertiary    = Color.White.copy(alpha = 0.50f),
    surfaceColor    = Color.White.copy(alpha = 0.05f),
    cardBackground = Color(0xFF1E293B).copy(0.95f)
)

val LightTheme = AppTheme(
    isDark          = false,
    backgroundBrush = Brush.verticalGradient(listOf(Color(0xFFF8FAFC), Color(0xFFF1F5F9))),
    cardColor       = Color(0xFFFFFFFF),
    borderColor     = Color(0xFFE2E8F0),
    navBarColor     = Color(0xFFFFFFFF).copy(alpha = 0.98f),
    textPrimary     = Color(0xFF1E293B),
    textSecondary   = Color(0xFF64748B),
    textTertiary    = Color(0xFF94A3B8),
    surfaceColor    = Color.White,
    cardBackground = Color.White

)

val LocalAppTheme = compositionLocalOf { DarkTheme }

object ThemeManager {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_IS_DARK = "is_dark"

    var isDark by mutableStateOf(true)
        private set

    val current: AppTheme
        get() = if (isDark) DarkTheme else LightTheme

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isDark = prefs.getBoolean(KEY_IS_DARK, true)
    }

    fun setDark(context: Context, dark: Boolean) {
        isDark = dark
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IS_DARK, dark).apply()
    }
}