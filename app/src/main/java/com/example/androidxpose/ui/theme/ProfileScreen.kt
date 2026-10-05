package com.example.androidxpose.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidxpose.data.db.InferredPlace
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

internal fun formatHour(hour: Int): String {
    val ampm = if (hour < 12) "AM" else "PM"
    val h    = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
    return "$h:00 $ampm"
}

internal fun formatMinutes(totalMin: Long): String = when {
    totalMin <= 0 -> "0m"
    totalMin < 60 -> "${totalMin}m"
    else          -> "${totalMin / 60}h ${totalMin % 60}m"
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ms))

private fun circularMeanHour(hours: List<Int>): Int {
    if (hours.isEmpty()) return 0
    val s = hours.map { sin(Math.toRadians(it * 15.0)) }.average()
    val c = hours.map { cos(Math.toRadians(it * 15.0)) }.average()
    return ((Math.toDegrees(atan2(s, c)) / 15.0).roundToInt() + 24) % 24
}

private val BT_UUID_INFRA = setOf("1812","1124","1105","1106","fd6f")
private val BT_INFRA_NAMES = listOf("HP ","HP-","EPSON","CANON","BROTHER","BRAVIA",
    "SAMSUNG TV","LG TV","VIZIO","TCL-","ECHO","ALEXA","ROKU","CHROMECAST",
    "XBOX","PLAYSTATION","PS4","PS5","SOUNDBAR","SONOS","NEST ","RING-",
    "TPLINK","TP-LINK","NETGEAR","LINKSYS","AIRTAG")
private val BT_MAC_REGEX = Regex("[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}")

internal fun isBtInfrastructure(type: String, uuids: String?, name: String?): Boolean {
    if (!uuids.isNullOrBlank()) {
        val lower = uuids.lowercase()
        if (BT_UUID_INFRA.any { lower.contains(it) }) return true
    }
    if (!name.isNullOrBlank()) {
        val upper = name.uppercase()
        if (BT_INFRA_NAMES.any { upper.contains(it) } || BT_MAC_REGEX.containsMatchIn(name)) return true
    }
    return type == "HID" || type == "PROXIMITY_BEACON"
}

private data class ProfileSnapshot(
    val deviceModel: String,
    val androidVersion: String,
    val manufacturer: String,
    val firstSeenMs: Long?,
    val totalLocationFixes: Int,
    val homePlace: InferredPlace?,
    val workPlace: InferredPlace?,
    val venueCount: Int,
    val frequentCount: Int,
    val mobilityRadiusKm: Double,
    val uniqueMobileDevices7d: Int,
    val infraFilteredCount7d: Int,
    val avgDailyMobileDevices: Float,
    val cohabitationCount: Int,
    val topAppName: String?,
    val topAppMinutes: Long,
    val totalLaunches7d: Int,
    val peakHour: Int?,
    val totalScreenTimeMin: Long,
    val screenActivations7d: Int,
    val userPresentEvents7d: Int,
    val avgSessionMin: Long,
    val estimatedSleepHour: Int?,
    val estimatedWakeHour: Int?,
    val sleepSourceLabel: String,
    val wifiCount7d: Int,
    val cellularCount7d: Int,
    val foregroundLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val bluetoothGranted: Boolean,
    val usageGranted: Boolean
)

private fun haverKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R    = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a    = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private suspend fun loadSnapshot(context: Context): ProfileSnapshot =
    withContext(Dispatchers.IO) {
        val db       = XposeDatabase.getInstance(context)
        val now      = System.currentTimeMillis()
        val since7d  = now - TimeUnit.DAYS.toMillis(7)
        val since30d = now - TimeUnit.DAYS.toMillis(30)
        data class Raw(
            val latestAudit: com.example.androidxpose.data.db.PermissionAudit?,
            val allPlaces:   List<com.example.androidxpose.data.db.InferredPlace>,
            val fixes:       List<com.example.androidxpose.data.db.LocationEvent>,
            val firstSeenMs: Long?,
            val btEvents7d:  List<com.example.androidxpose.data.db.BluetoothEvent>,
            val usageEvents: List<com.example.androidxpose.data.db.AppUsageEvent>,
            val launchCounts:List<com.example.androidxpose.data.db.PackageLaunchCount>,
            val usageEvts30d:List<com.example.androidxpose.data.db.AppUsageEvent>,
            val devEvts:     List<com.example.androidxpose.data.db.DeviceStateEvent>,
            val networkEvts: List<com.example.androidxpose.data.db.NetworkEvent>
        )
        val raw = coroutineScope {
            val a = async { try { db.permissionAuditDao().getLatestAudit() } catch (_: Exception) { null } }
            val b = async { try { db.inferredPlaceDao().getAll() } catch (_: Exception) { emptyList() } }
            val c = async { try { db.locationEventDao().getFixesSince(since7d) } catch (_: Exception) { emptyList() } }
            val d = async { try { db.locationEventDao().getFixesSince(since30d).minOfOrNull { it.timestamp } } catch (_: Exception) { null } }
            val e = async { try { db.bluetoothEventDao().getEventsSince(since7d) } catch (_: Exception) { emptyList() } }
            val f = async { try { db.appUsageEventDao().getEventsSince(since7d).filter { it.usageGranted }.sortedBy { it.timestamp } } catch (_: Exception) { emptyList() } }
            val g = async { try { db.appUsageEventDao().getLaunchCountsPerPackage(since7d) } catch (_: Exception) { emptyList() } }
            val h = async { try { db.appUsageEventDao().getEventsSince(since30d).filter { it.usageGranted }.sortedBy { it.timestamp } } catch (_: Exception) { emptyList() } }
            val i = async { try { db.deviceStateEventDao().getEventsSince(since30d).sortedBy { it.timestamp } } catch (_: Exception) { emptyList() } }
            val j = async { try { db.networkEventDao().getEventsSince(since7d) } catch (_: Exception) { emptyList() } }
            Raw(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await(), h.await(), i.await(), j.await())
        }
        val latestAudit  = raw.latestAudit
        val allPlaces    = raw.allPlaces
        val fixes        = raw.fixes
        val firstSeenMs  = raw.firstSeenMs
        val btEvents7d   = raw.btEvents7d
        val usageEvents  = raw.usageEvents
        val launchCounts = raw.launchCounts
        val usageEvts30d = raw.usageEvts30d
        val devEvts      = raw.devEvts
        val networkEvts  = raw.networkEvts

        val activePlaces = allPlaces.filter { it.lastConfirmedMs > since30d && it.label != "TRANSIT" }
        val homePlace    = activePlaces.filter { it.label == "HOME" }.maxByOrNull { it.confidenceScore }
        val workPlace    = activePlaces.filter { it.label == "WORK" }.maxByOrNull { it.confidenceScore }
        val venueCount   = activePlaces.count { it.label == "VENUE" }
        val freqCount    = activePlaces.count { it.label == "FREQUENT" }
        val mobilityRadiusKm = if (homePlace != null) {
            activePlaces.filter { it.id != homePlace.id }
                .maxOfOrNull { haverKm(homePlace.centroidLat, homePlace.centroidLng, it.centroidLat, it.centroidLng) }
                ?: 0.0
        } else 0.0
        val btMobile7d        = btEvents7d.filterNot { isBtInfrastructure(it.deviceType, it.serviceUuids, it.deviceName) }
        val uniqueMobile7d    = btMobile7d.map { it.addressHash }.toSet().size
        val infraEventCount7d = btEvents7d.count { isBtInfrastructure(it.deviceType, it.serviceUuids, it.deviceName) }
        val msPerDay = TimeUnit.DAYS.toMillis(1)
        val avgDailyMobile = if (btMobile7d.isNotEmpty()) {
            btMobile7d.groupBy { it.timestamp / msPerDay }
                .values.map { it.map { e -> e.addressHash }.toSet().size }.average().toFloat()
        } else 0f
        val cohabitationCount = homePlace?.cohabitationCount ?: 0
        val fgMs = mutableMapOf<String, Long>()
        usageEvents.groupBy { it.packageName }.forEach { (pkg, evts) ->
            var lastR: Long? = null; var total = 0L
            evts.forEach { e ->
                when (e.eventType) {
                    1 -> lastR = e.timestamp
                    2 -> lastR?.let { r -> total += (e.timestamp - r).coerceIn(0L, 7_200_000L); lastR = null }
                }
            }
            if (total > 0) fgMs[pkg] = total
        }
        val topPkg     = fgMs.entries.maxByOrNull { it.value }?.key
        val topAppName = topPkg?.let {
            try { context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(it, 0)).toString()
            } catch (_: Exception) { null }
        }
        val hourCounts = IntArray(24)
        usageEvents.filter { it.eventType == 1 }.forEach {
            hourCounts[Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY)]++
        }
        val peakHour = hourCounts.indices.maxByOrNull { hourCounts[it] }?.takeIf { hourCounts[it] > 0 }
        val devEvts7d = devEvts.filter { it.timestamp >= since7d }
        val screenActivations7d = devEvts7d.count { it.eventType == "SCREEN_ON" }
        val userPresent7d       = devEvts7d.count { it.eventType == "USER_PRESENT" }
        val sessions = mutableListOf<Long>(); var lastOn: Long? = null; var totalScreenMs = 0L
        devEvts7d.forEach { evt ->
            when (evt.eventType) {
                "SCREEN_ON"  -> lastOn = evt.timestamp
                "SCREEN_OFF" -> lastOn?.let { on ->
                    val dur = evt.timestamp - on
                    if (dur in 1_000..7_200_000) { sessions.add(dur); totalScreenMs += dur }
                    lastOn = null
                }
            }
        }
        val avgSessionMin = TimeUnit.MILLISECONDS.toMinutes(
            if (sessions.isNotEmpty()) sessions.average().roundToLong() else 0L)

        data class SW(val sleepTs: Long, val wakeTs: Long, val refined: Boolean)
        val offPairs = mutableListOf<Pair<Long, Long>>(); var lastOff: Long? = null
        devEvts.forEach { evt ->
            when (evt.eventType) {
                "SCREEN_OFF" -> lastOff = evt.timestamp
                "SCREEN_ON"  -> lastOff?.let { off -> offPairs.add(off to evt.timestamp); lastOff = null }
            }
        }
        val briefWakeMs = TimeUnit.MINUTES.toMillis(30)
        val mergedPairs = mutableListOf<Pair<Long, Long>>()
        var pi = 0
        while (pi < offPairs.size) {
            var (pStart, pEnd) = offPairs[pi]
            while (pi + 1 < offPairs.size && offPairs[pi + 1].first - pEnd < briefWakeMs) {
                pi++; pEnd = offPairs[pi].second
            }
            mergedPairs.add(pStart to pEnd); pi++
        }
        val sleepWindows = mergedPairs.mapNotNull { (off, on) ->
            val gap = TimeUnit.MILLISECONDS.toMinutes(on - off)
            val h   = Calendar.getInstance().apply { timeInMillis = off }.get(Calendar.HOUR_OF_DAY)
            if (gap < 180 || (h < 19 && h >= 11)) return@mapNotNull null
            val la = usageEvts30d.filter { it.eventType == 1 && it.timestamp in (off - TimeUnit.MINUTES.toMillis(45))..off }.maxByOrNull { it.timestamp }
            val fa = usageEvts30d.filter { it.eventType == 1 && it.timestamp in on..(on + TimeUnit.MINUTES.toMillis(45)) }.minByOrNull { it.timestamp }
            SW(la?.timestamp ?: off, fa?.timestamp ?: on, la != null || fa != null)
        }
        val sleepSource  = when { sleepWindows.isEmpty() -> "no data"; sleepWindows.any { it.refined } -> "screen + app usage"; else -> "screen only" }
        val estSleep     = if (sleepWindows.isNotEmpty()) circularMeanHour(
            sleepWindows.map { Calendar.getInstance().apply { timeInMillis = it.sleepTs }.get(Calendar.HOUR_OF_DAY) }
        ) else null
        val estWake  = if (sleepWindows.isNotEmpty())
            sleepWindows.map { Calendar.getInstance().apply { timeInMillis = it.wakeTs }.get(Calendar.HOUR_OF_DAY) }.average().roundToInt()
        else null
        val wifiCount     = networkEvts.count { it.networkType == "WIFI" && it.isConnected }
        val cellularCount = networkEvts.count { it.networkType == "CELLULAR" && it.isConnected }

        ProfileSnapshot(
            deviceModel = Build.MODEL, androidVersion = Build.VERSION.RELEASE, manufacturer = Build.MANUFACTURER,
            firstSeenMs = firstSeenMs, totalLocationFixes = fixes.size, homePlace = homePlace, workPlace = workPlace,
            venueCount = venueCount, frequentCount = freqCount, mobilityRadiusKm = mobilityRadiusKm,
            uniqueMobileDevices7d = uniqueMobile7d, infraFilteredCount7d = infraEventCount7d,
            avgDailyMobileDevices = avgDailyMobile, cohabitationCount = cohabitationCount,
            topAppName = topAppName, topAppMinutes = TimeUnit.MILLISECONDS.toMinutes(fgMs[topPkg] ?: 0L),
            totalLaunches7d = launchCounts.sumOf { it.launchCount }, peakHour = peakHour,
            totalScreenTimeMin = TimeUnit.MILLISECONDS.toMinutes(totalScreenMs), screenActivations7d = screenActivations7d,
            userPresentEvents7d = userPresent7d, avgSessionMin = avgSessionMin, estimatedSleepHour = estSleep,
            estimatedWakeHour = estWake, sleepSourceLabel = sleepSource, wifiCount7d = wifiCount, cellularCount7d = cellularCount,
            foregroundLocationGranted = PermissionManager.hasForegroundLocation(context),
            backgroundLocationGranted = PermissionManager.hasBackgroundLocation(context),
            bluetoothGranted = PermissionManager.hasBluetooth(context),
            usageGranted = PermissionManager.hasUsageStats(context)
        )
    }

@Composable
fun ProfileScreen(onGoHome: () -> Unit) {
    val context  = LocalContext.current
    val theme    = LocalAppTheme.current
    var snapshot by remember { mutableStateOf<ProfileSnapshot?>(null) }
    var loading  by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    suspend fun reload(showLoading: Boolean = true) {
        try {
            if (showLoading) loading = true
            snapshot = loadSnapshot(context)
        } catch (e: Exception) {
            android.util.Log.e("ProfileScreen", "reload error: ${e.message}")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload(showLoading = true) }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && snapshot != null) {
                scope.launch { reload(showLoading = false) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        bottomBar = { AppBottomNav(currentScreen = "Profile") },
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
            Text(
                "Digital Profile",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = theme.textPrimary,
                letterSpacing = (-1).sp
            )
            Text(
                "Inferred from your metadata",
                fontSize = 16.sp,
                color = theme.textSecondary
            )
            Spacer(Modifier.height(4.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF4ADE80), strokeWidth = 2.dp)
                }
            } else {
                val s = snapshot ?: return@Column

                ProfileCard("Device Identity", "📱", Color(0xFF6366F1), theme) {
                    ProfileRow("Manufacturer", s.manufacturer.replaceFirstChar { it.uppercase() }, theme)
                    ProfileRow("Model", s.deviceModel, theme)
                    ProfileRow("Android", s.androidVersion, theme)
                    s.firstSeenMs?.let { ProfileRow("Data since", formatDate(it), theme) }
                    Spacer(Modifier.height(8.dp))
                    PermissionDots(s.foregroundLocationGranted, s.backgroundLocationGranted,
                        s.bluetoothGranted, s.usageGranted, theme)
                }

                ProfileCard("Location Profile", "📍", Color(0xFF10B981), theme) {
                    ProfileRow("Fixes recorded (7d)", "${s.totalLocationFixes}", theme)
                    if (s.homePlace != null) {
                        ProfileRow("Home", "${s.homePlace.confidenceLabel()} · ${s.homePlace.confidenceScore}% confidence", theme)
                        ProfileRow("Home last confirmed", relativeTimeProfile(s.homePlace.lastConfirmedMs), theme)
                    } else {
                        ProfileRow("Home", "Not yet inferred — needs ≥3 overnight visits", theme)
                    }
                    if (s.workPlace != null) {
                        ProfileRow("Workplace", "${s.workPlace.confidenceLabel()} · ${s.workPlace.confidenceScore}% confidence", theme)
                    } else {
                        ProfileRow("Workplace", "Not yet inferred", theme)
                    }
                    if (s.venueCount > 0)   ProfileRow("Recurring venues", "${s.venueCount}", theme)
                    if (s.frequentCount > 0) ProfileRow("Frequent locations", "${s.frequentCount}", theme)
                    if (s.mobilityRadiusKm > 0.1) ProfileRow("Mobility radius", String.format("%.1f km", s.mobilityRadiusKm), theme)
                    Spacer(Modifier.height(4.dp))
                    InferenceTag(when {
                        s.mobilityRadiusKm < 2  -> "Predominantly local · stays close to home"
                        s.mobilityRadiusKm < 10 -> "Moderate range · regular nearby travel"
                        s.mobilityRadiusKm < 30 -> "Wide range · frequent travel"
                        else                    -> "High mobility · long-distance movement"
                    }, Color(0xFF10B981))
                }

                ProfileCard("Social Exposure", "📡", Color(0xFF60A5FA), theme) {
                    ProfileRow("Unique devices nearby (7d)", "${s.uniqueMobileDevices7d}", theme)
                    ProfileRow("Avg per day (7d)", String.format("%.1f", s.avgDailyMobileDevices), theme)
                    ProfileRow("Infrastructure scans filtered (7d)", "${s.infraFilteredCount7d}", theme)
                    ProfileRow("Frequent home contacts",
                        if (s.cohabitationCount > 0) "${s.cohabitationCount} device(s)" else "None detected", theme)
                    Spacer(Modifier.height(4.dp))
                    InferenceTag(when {
                        s.avgDailyMobileDevices < 3f  -> "Primarily home and isolated environments"
                        s.avgDailyMobileDevices < 10f -> "Regular contact with a small social circle"
                        s.avgDailyMobileDevices < 25f -> "Frequent shared spaces — office, transit, or social venues"
                        else                          -> "High-density environments — crowded public or work spaces"
                    }, Color(0xFF60A5FA))
                }

                ProfileCard("App Usage", "📊", Color(0xFF8B5CF6), theme) {
                    ProfileRow("Total launches (7d)", "${s.totalLaunches7d}", theme)
                    s.topAppName?.let {
                        ProfileRow("Most used app", it, theme)
                        if (s.topAppMinutes > 0) ProfileRow("Time in top app", formatMinutes(s.topAppMinutes), theme)
                    }
                    s.peakHour?.let { ProfileRow("Peak usage hour", formatHour(it), theme) }
                    ProfileRow("Total screen time (7d)", formatMinutes(s.totalScreenTimeMin), theme)
                    if (s.totalLaunches7d > 0) {
                        Spacer(Modifier.height(4.dp))
                        InferenceTag("~${s.totalLaunches7d / 7} app launches per day", Color(0xFF8B5CF6))
                    }
                }

                ProfileCard("Device Behavior", "📲", Color(0xFFF43F5E), theme) {
                    ProfileRow("Screen activations (7d)", "${s.screenActivations7d}", theme)
                    if (s.screenActivations7d > 0) ProfileRow("Activations/day", "${s.screenActivations7d / 7}", theme)
                    if (s.userPresentEvents7d > 0) ProfileRow("Lock screen unlocks (7d)", "${s.userPresentEvents7d}", theme)
                    if (s.avgSessionMin > 0) ProfileRow("Avg session length", "${s.avgSessionMin} min", theme)
                    s.estimatedSleepHour?.let { ProfileRow("Est. sleep time", formatHour(it), theme) }
                    s.estimatedWakeHour?.let  { ProfileRow("Est. wake time",  formatHour(it), theme) }
                    Spacer(Modifier.height(4.dp))
                    if (s.estimatedSleepHour != null && s.estimatedWakeHour != null) {
                        val dur = ((s.estimatedWakeHour - s.estimatedSleepHour + 24) % 24)
                        InferenceTag("~$dur hr sleep window · ${formatHour(s.estimatedSleepHour)} to ${formatHour(s.estimatedWakeHour)}", Color(0xFFF43F5E))
                    } else {
                        InferenceTag("Sleep window building — needs a few more nights of data", Color(0xFFF43F5E))
                    }
                }

                ProfileCard("Connectivity", "🌐", Color(0xFFFB923C), theme) {
                    val total   = (s.wifiCount7d + s.cellularCount7d).coerceAtLeast(1)
                    val wifiPct = s.wifiCount7d * 100 / total
                    ProfileRow("Wi-Fi transitions (7d)", "${s.wifiCount7d}", theme)
                    ProfileRow("Cellular transitions (7d)", "${s.cellularCount7d}", theme)
                    ProfileRow("Wi-Fi reliance", "$wifiPct%", theme)
                    Spacer(Modifier.height(8.dp))
                    NetworkRatioBar(s.wifiCount7d.toFloat() / total, theme)
                    Spacer(Modifier.height(8.dp))
                    InferenceTag(when {
                        wifiPct >= 75 -> "Predominantly indoors · heavy Wi-Fi use"
                        wifiPct >= 40 -> "Mixed environment · regular outdoor use"
                        else          -> "Frequently mobile · majority cellular"
                    }, Color(0xFFFB923C))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun InferredPlace.confidenceLabel(): String = when (label) {
    "HOME" -> when { confidenceScore >= 65 -> "Home"; confidenceScore >= 40 -> "Likely Home"; else -> "Possible Home" }
    "WORK" -> when { confidenceScore >= 65 -> "Workplace"; confidenceScore >= 40 -> "Likely Workplace"; else -> "Possible Workplace" }
    "VENUE" -> "Recurring Venue"
    "FREQUENT" -> "Frequent Location"
    else -> label
}

private fun relativeTimeProfile(ts: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ts)
    return when { days == 0L -> "today"; days == 1L -> "yesterday"; else -> "${days}d ago" }
}

@Composable
private fun ProfileCard(title: String, icon: String, accentColor: Color, theme: AppTheme, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = theme.surfaceColor,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, theme.borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(36.dp).background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) { Text(icon, fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 16.sp)
            }
            HorizontalDivider(color = theme.borderColor.copy(alpha = 0.5f), thickness = 1.dp)
            content()
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String, theme: AppTheme) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top) {
        Text(label, color = theme.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun InferenceTag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PermissionDots(fg: Boolean, bg: Boolean, bt: Boolean, usage: Boolean, theme: AppTheme) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Loc" to fg, "BG Loc" to bg, "BT" to bt, "Usage" to usage).forEach { (label, granted) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(modifier = Modifier.size(8.dp).background(
                    if (granted) Color(0xFF4ADE80) else Color(0xFFEF4444), RoundedCornerShape(4.dp)))
                Text(label, fontSize = 9.sp, color = theme.textTertiary)
            }
        }
    }
}

@Composable
private fun NetworkRatioBar(wifiRatio: Float, theme: AppTheme) {
    val clamped = wifiRatio.coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            drawRect(Color(0xFFFB923C))
            if (clamped > 0f) drawRoundRect(Color(0xFF4ADE80),
                size = Size(size.width * clamped, size.height), cornerRadius = CornerRadius(5.dp.toPx()))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Color(0xFF4ADE80), RoundedCornerShape(2.dp)))
                Text("Wi-Fi", fontSize = 10.sp, color = theme.textTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Color(0xFFFB923C), RoundedCornerShape(2.dp)))
                Text("Cellular", fontSize = 10.sp, color = theme.textTertiary)
            }
        }
    }
}