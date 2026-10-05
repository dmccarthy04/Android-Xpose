package com.example.androidxpose

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.androidxpose.Profile.ProfileActivity.ProfileActivity
import com.example.androidxpose.data.collectors.*
import com.example.androidxpose.data.db.PermissionAudit
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager
import com.example.androidxpose.report.ReportActivity
import com.example.androidxpose.ui.theme.LocalAppTheme
import com.example.androidxpose.ui.theme.SettingsActivity
import com.example.androidxpose.ui.theme.ThemeManager
import com.example.androidxpose.usage.UsageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PERMISSION_SYSTEM"

enum class RequestSequence {
    FOREGROUND_LOCATION, BACKGROUND_LOCATION, USAGE_STATS, COMPLETE
}

class MainActivity : ComponentActivity() {

    private var currentRequest    = RequestSequence.FOREGROUND_LOCATION
    private var requestInProgress = false

    private val _foregroundGranted = mutableStateOf(false)
    private val _backgroundGranted = mutableStateOf(false)
    private val _usageGranted      = mutableStateOf(false)

    private var foregroundGranted: Boolean get() = _foregroundGranted.value; set(v) { _foregroundGranted.value = v }
    private var backgroundGranted: Boolean get() = _backgroundGranted.value; set(v) { _backgroundGranted.value = v }
    private var usageGranted:      Boolean get() = _usageGranted.value;      set(v) { _usageGranted.value      = v }

    private var usageSettingsOpened = false
    private var persistenceComplete = false

    private val foregroundLocationRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestInProgress = false
            foregroundGranted = PermissionManager.hasForegroundLocation(this)

            currentRequest    = RequestSequence.BACKGROUND_LOCATION
            requestPermission()
        }

    private val backgroundLocationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            requestInProgress = false
            backgroundGranted = granted
            currentRequest    = RequestSequence.USAGE_STATS
            requestPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)

        setContent {
            val theme = ThemeManager.current
            CompositionLocalProvider(LocalAppTheme provides theme) {
                MetadataHomeScreen(
                    onRequestPermissions = { startRequestSequence() },
                    onCollectLocation    = {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { LocationCollector.collectAndPersist(this@MainActivity) }
                        }
                    },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }

        startRequestSequence()

        listOf(DeviceStateService::class.java, NetworkStateService::class.java).forEach {
            try { ContextCompat.startForegroundService(this, Intent(this, it)) }
            catch (e: Exception) { Log.e(TAG, "Service error: ${e.message}") }
        }

        startPermissionGatedServices()
        UsageSyncWorker.enqueue(this)
    }

    override fun onResume() {
        super.onResume()
        usageGranted        = PermissionManager.hasUsageStats(this)
        persistenceComplete = false
        requestPermission()
    }

    private fun startRequestSequence() {
        foregroundGranted     = PermissionManager.hasForegroundLocation(this)
        backgroundGranted     = PermissionManager.hasBackgroundLocation(this)
        usageGranted          = PermissionManager.hasUsageStats(this)
        usageSettingsOpened   = false
        persistenceComplete   = false
        currentRequest        = RequestSequence.FOREGROUND_LOCATION
        requestPermission()
    }

    private fun requestPermission() {
        if (requestInProgress) return
        when (currentRequest) {
            RequestSequence.FOREGROUND_LOCATION -> {
                if (!foregroundGranted) {
                    requestInProgress = true
                    foregroundLocationRequest.launch(PermissionManager.foregroundLocation)
                } else {
                    currentRequest = RequestSequence.BACKGROUND_LOCATION
                    requestPermission()
                }
            }
            RequestSequence.BACKGROUND_LOCATION -> {
                if (!backgroundGranted) {
                    requestInProgress = true
                    backgroundLocationRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    currentRequest = RequestSequence.USAGE_STATS
                    requestPermission()
                }
            }
            RequestSequence.USAGE_STATS -> {
                if (!PermissionManager.hasUsageStats(this)) {
                    if (!usageSettingsOpened) {
                        usageSettingsOpened = true
                        PermissionManager.openUsageSettings(this)
                        return
                    }
                    usageGranted = false
                } else {
                    usageGranted = true
                }
                currentRequest = RequestSequence.COMPLETE
                requestPermission()
            }
            RequestSequence.COMPLETE -> {
                if (!persistenceComplete) {
                    persistenceComplete = true
                    logSummary()
                }
            }
        }
    }

    private fun startPermissionGatedServices() {
        if (foregroundGranted) {
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        }
    }

    private fun logSummary() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    XposeDatabase.getInstance(this@MainActivity).permissionAuditDao().insert(
                        PermissionAudit(
                            foregroundLocation = foregroundGranted,
                            backgroundLocation = backgroundGranted,
                            bluetooth          = false,
                            usageStats         = usageGranted,
                            timestamp          = System.currentTimeMillis()
                        )
                    )
                    if (usageGranted) UsageStatsCollector.collectAndPersist(this@MainActivity)
                } catch (e: Exception) { Log.e(TAG, "Error: ${e.message}") }
            }
        }
    }
}

@Composable
fun MetadataHomeScreen(
    onRequestPermissions: () -> Unit,
    onCollectLocation: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val theme = LocalAppTheme.current

    Box(modifier = Modifier.fillMaxSize().background(theme.backgroundBrush)) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Column {
                Text("Metadata Mirror", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                    color = theme.textPrimary, letterSpacing = (-1).sp)
                Text("System active and monitoring", fontSize = 16.sp, color = theme.textSecondary)
            }

            Surface(
                color    = theme.surfaceColor,
                shape    = RoundedCornerShape(24.dp),
                border   = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier              = Modifier.padding(20.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Live Telemetry", color = theme.textTertiary, fontSize = 12.sp)
                        Text("Data Secured",   color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    QuickActionBtn("📍", theme, onCollectLocation)
                }
            }

            Text("Intelligence Modules", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = theme.textPrimary.copy(alpha = 0.9f))

            Box(modifier = Modifier.weight(1f)) {
                FeatureGrid()
            }

            Button(
                onClick  = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape    = RoundedCornerShape(16.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = theme.surfaceColor)
            ) {
                Text("⚙ System Settings", fontWeight = FontWeight.Bold, color = theme.textPrimary)
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
fun QuickActionBtn(icon: String, theme: com.example.androidxpose.ui.theme.AppTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.surfaceColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 18.sp)
    }
}

@Composable
fun FeatureGrid() {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxHeight()) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureTile("Profile",  "👤", "Manage Identity",  Color(0xFF6366F1), Modifier.weight(1f)) {
                context.startActivity(Intent(context, ProfileActivity::class.java))
            }
            FeatureTile("Reports",  "📜", "Data History",     Color(0xFFF43F5E), Modifier.weight(1f)) {
                context.startActivity(Intent(context, ReportActivity::class.java))
            }
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureTile("Usage",    "📊", "Habit Analysis",   Color(0xFF8B5CF6), Modifier.weight(1f)) {
                context.startActivity(Intent(context, UsageActivity::class.java))
            }
            FeatureTile("Mapping",  "🗺", "Trace Locations",  Color(0xFF10B981), Modifier.weight(1f)) {
                context.startActivity(Intent(context, MappingActivity::class.java))
            }
        }
    }
}

@Composable
fun FeatureTile(
    title: String, icon: String, subtitle: String,
    color: Color, modifier: Modifier, onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue  = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label        = ""
    )

    Card(
        modifier = modifier.fillMaxHeight().graphicsLayer(scaleX = scale, scaleY = scale),
        shape    = RoundedCornerShape(28.dp),
        colors   = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.9f)),
        onClick  = onClick
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier         = Modifier.size(42.dp).background(Color.White.copy(0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 20.sp) }
            Column {
                Text(title,    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    }
}