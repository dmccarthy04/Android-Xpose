package com.example.androidxpose.ui.theme

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.androidxpose.data.collectors.UsageStatsCollector
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.data.collectors.ACTION_MANUAL_SCAN
import com.example.androidxpose.data.collectors.BluetoothScanService
import com.example.androidxpose.data.collectors.LocationCollector
import com.example.androidxpose.permissions.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    private val _foregroundGranted = mutableStateOf(false)
    private val _bluetoothGranted  = mutableStateOf(false)
    private val _backgroundGranted = mutableStateOf(false)
    private val _usageGranted      = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        ThemeManager.init(this)

        setContent {

            CompositionLocalProvider(LocalAppTheme provides ThemeManager.current) {
                SettingsScreen(
                    foregroundGranted = _foregroundGranted.value,
                    bluetoothGranted  = _bluetoothGranted.value,
                    backgroundGranted = _backgroundGranted.value,
                    usageGranted      = _usageGranted.value,
                    onManualLocationFix = {
                        lifecycleScope.launch {
                            LocationCollector.collectAndPersist(this@SettingsActivity)
                        }
                    },
                    onManualBtScan = {
                        if (PermissionManager.hasBluetooth(this@SettingsActivity)) {
                            startService(
                                android.content.Intent(this@SettingsActivity, BluetoothScanService::class.java).apply {
                                    action = ACTION_MANUAL_SCAN
                                }
                            )
                        } else {
                            android.util.Log.w("SettingsActivity", "Manual BT scan blocked — permission not granted")
                        }
                    },
                    onClearData       = {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                val db = XposeDatabase.getInstance(this@SettingsActivity)
                                db.permissionAuditDao().clearAll()
                                db.locationEventDao().clearAll()
                                db.bluetoothEventDao().clearAll()
                                db.appUsageEventDao().clearAll()
                                db.deviceStateEventDao().clearAll()
                                db.networkEventDao().clearAll()
                                db.inferredPlaceDao().clearAll()
                            }
                        }
                    },
                    onForceUsageSync  = {
                        lifecycleScope.launch { UsageStatsCollector.collectAndPersist(this@SettingsActivity) }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        _foregroundGranted.value = PermissionManager.hasForegroundLocation(this)
        _bluetoothGranted.value  = PermissionManager.hasBluetooth(this)
        _backgroundGranted.value = PermissionManager.hasBackgroundLocation(this)
        _usageGranted.value      = PermissionManager.hasUsageStats(this)
    }
}

@Composable
fun SettingsScreen(
    foregroundGranted: Boolean,
    bluetoothGranted: Boolean,
    backgroundGranted: Boolean,
    usageGranted: Boolean,
    onManualLocationFix: () -> Unit,
    onManualBtScan: () -> Unit,
    onForceUsageSync: () -> Unit,
    onClearData: () -> Unit
) {
    val context = LocalContext.current
    val theme   = LocalAppTheme.current

    Scaffold(
        bottomBar      = { AppBottomNav(currentScreen = "Settings") },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.backgroundBrush)
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(24.dp))


            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(
                            if (theme.isDark) Color.White.copy(0.1f) else Color(0xFF6366F1).copy(0.1f),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { Text("⚙️", fontSize = 24.sp) }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = theme.textPrimary)
                    Text("Manage data engine & interface", fontSize = 14.sp, color = theme.textSecondary)
                }
            }


            SettingsGroup("Appearance", theme.textPrimary) {
                ThemeToggleRow(theme, context)
            }


            SettingsGroup("Manual Collection", theme.textPrimary) {
                SettingsActionRow("📍", "Manual Location Fix", "Capture a location fix now",   theme, false, onManualLocationFix)
                SettingsActionRow("📡", "Manual Bluetooth Scan", "Trigger a Bluetooth scan now", theme, false, onManualBtScan)
            }

            SettingsGroup("Data Management", theme.textPrimary) {
                SettingsActionRow("⚡", "Force Usage Sync",  "Capture metadata now",       theme, false, onForceUsageSync)
                SettingsActionRow("🗑️", "Clear Data",        "Wipe local storage",         theme, true,  onClearData)
            }


            SettingsGroup("Permission Integrity", theme.textPrimary) {
                Surface(
                    color     = theme.surfaceColor,
                    shape     = RoundedCornerShape(24.dp),
                    border    = BorderStroke(1.dp, theme.borderColor),
                    modifier  = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PermissionRow("📍 Location",    foregroundGranted, theme.textPrimary)
                        PermissionRow("🔵 Bluetooth",   bluetoothGranted,  theme.textPrimary)
                        PermissionRow("📊 Usage Stats", usageGranted,      theme.textPrimary)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, textColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = textColor.copy(0.4f), modifier = Modifier.padding(start = 8.dp)
        )
        content()
    }
}

@Composable
private fun ThemeToggleRow(theme: AppTheme, context: android.content.Context) {
    Surface(
        color    = theme.surfaceColor,
        shape    = RoundedCornerShape(20.dp),
        border   = BorderStroke(1.dp, theme.borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (theme.isDark) "🌙" else "☀️")
                Spacer(Modifier.width(12.dp))
                Text("Dark & Light Mode", fontWeight = FontWeight.Bold, color = theme.textPrimary)
            }
            Switch(
                checked         = theme.isDark,
                onCheckedChange = { ThemeManager.setDark(context, it) }
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: String, title: String, subtitle: String,
    theme: AppTheme, isDestructive: Boolean, onClick: () -> Unit
) {
    Surface(
        color    = theme.surfaceColor,
        shape    = RoundedCornerShape(20.dp),
        border   = BorderStroke(1.dp, theme.borderColor),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title, fontWeight = FontWeight.Bold,
                    color = if (isDestructive) Color(0xFFF43F5E) else theme.textPrimary
                )
                Text(subtitle, fontSize = 12.sp, color = theme.textSecondary)
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, textColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = textColor.copy(0.7f), fontSize = 14.sp)
        Text(
            if (granted) "ACTIVE" else "DENIED",
            color          = if (granted) Color(0xFF4ADE80) else Color(0xFFF43F5E),
            fontWeight     = FontWeight.Bold,
            fontSize       = 12.sp
        )
    }
}