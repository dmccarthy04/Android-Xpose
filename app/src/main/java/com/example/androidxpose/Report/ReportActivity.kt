package com.example.androidxpose.report

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidxpose.data.db.InferredPlace
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.ui.theme.AppBottomNav
import com.example.androidxpose.ui.theme.LocalAppTheme
import com.example.androidxpose.ui.theme.ThemeManager
import com.example.androidxpose.ui.theme.formatHour
import com.example.androidxpose.ui.theme.formatMinutes
import com.example.androidxpose.ui.theme.isBtInfrastructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

class ReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        setContent {
            CompositionLocalProvider(LocalAppTheme provides ThemeManager.current) {
                ReportScreen()
            }
        }
    }
}

private data class InferenceBlock(
    val category: String, val icon: String, val accentColor: Color,
    val headline: String, val body: String, val supportingFact: String
)
private data class PatternBlock(
    val title: String, val icon: String, val accentColor: Color,
    val score: String, val detail: String
)
private data class LogEntry(
    val timestamp: Long, val category: String, val categoryColor: Color,
    val summary: String, val detail: String
)
private data class ReportData(
    val inferences: List<InferenceBlock>,
    val patterns: List<PatternBlock>,
    val logs: List<LogEntry>
)

private fun Long.toRelative(): String {
    val mins = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - this)
    val hrs  = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - this)
    val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - this)
    return when { mins < 2 -> "just now"; mins < 60 -> "${mins}m ago"; hrs < 24 -> "${hrs}h ago"; else -> "${days}d ago" }
}

private fun circularMeanR(hours: List<Int>): Int {
    if (hours.isEmpty()) return 0
    val s = hours.map { sin(Math.toRadians(it * 15.0)) }.average()
    val c = hours.map { cos(Math.toRadians(it * 15.0)) }.average()
    return ((Math.toDegrees(atan2(s, c)) / 15.0).roundToInt() + 24) % 24
}

private fun InferredPlace.confidenceLabelR(): String = when (label) {
    "HOME"     -> when { confidenceScore >= 65 -> "Home"; confidenceScore >= 40 -> "Likely Home"; else -> "Possible Home" }
    "WORK"     -> when { confidenceScore >= 65 -> "Workplace"; confidenceScore >= 40 -> "Likely Workplace"; else -> "Possible Workplace" }
    "VENUE"    -> "Recurring Venue"
    "FREQUENT" -> "Frequent Location"
    else       -> label
}

private suspend fun loadReport(context: Context): ReportData = withContext(Dispatchers.IO) {
    val db       = XposeDatabase.getInstance(context)
    val now      = System.currentTimeMillis()
    val since7d  = now - TimeUnit.DAYS.toMillis(7)
    val since30d = now - TimeUnit.DAYS.toMillis(30)

    data class RawR(
        val allPlaces:    List<InferredPlace>,
        val locationEvts: List<com.example.androidxpose.data.db.LocationEvent>,
        val btEvts7d:     List<com.example.androidxpose.data.db.BluetoothEvent>,
        val usageEvts7d:  List<com.example.androidxpose.data.db.AppUsageEvent>,
        val usageEvts30d: List<com.example.androidxpose.data.db.AppUsageEvent>,
        val deviceEvts:   List<com.example.androidxpose.data.db.DeviceStateEvent>,
        val networkEvts:  List<com.example.androidxpose.data.db.NetworkEvent>
    )
    val raw = coroutineScope {
        val a = async { try { db.inferredPlaceDao().getAll() } catch (_: Exception) { emptyList() } }
        val b = async { try { db.locationEventDao().getFixesSince(since7d) } catch (_: Exception) { emptyList() } }
        val c = async { try { db.bluetoothEventDao().getEventsSince(since7d) } catch (_: Exception) { emptyList() } }
        val d = async { try { db.appUsageEventDao().getEventsSince(since7d).filter { it.usageGranted } } catch (_: Exception) { emptyList() } }
        val e = async { try { db.appUsageEventDao().getEventsSince(since30d).filter { it.usageGranted }.sortedBy { it.timestamp } } catch (_: Exception) { emptyList() } }
        val f = async { try { db.deviceStateEventDao().getEventsSince(since30d).sortedBy { it.timestamp } } catch (_: Exception) { emptyList() } }
        val g = async { try { db.networkEventDao().getEventsSince(since7d) } catch (_: Exception) { emptyList() } }
        RawR(a.await(), b.await(), c.await(), d.await(), e.await(), f.await(), g.await())
    }
    val allPlaces      = raw.allPlaces
    val locationEvents = raw.locationEvts
    val btEvents7d     = raw.btEvts7d
    val usageEvents    = raw.usageEvts7d
    val usageEvents30d = raw.usageEvts30d
    val deviceEvents   = raw.deviceEvts
    val networkEvents  = raw.networkEvts

    val activePlaces = allPlaces.filter { it.lastConfirmedMs > since30d && it.label != "TRANSIT" }
    val homePlace    = activePlaces.filter { it.label == "HOME" }.maxByOrNull { it.confidenceScore }
    val workPlace    = activePlaces.filter { it.label == "WORK" }.maxByOrNull { it.confidenceScore }
    val venueCount   = activePlaces.count { it.label == "VENUE" }

    val mobilityRadius = if (homePlace != null) {
        activePlaces.filter { it.id != homePlace.id }.maxOfOrNull {
            val R = 6371.0; val dLat = Math.toRadians(it.centroidLat - homePlace.centroidLat)
            val dLng = Math.toRadians(it.centroidLng - homePlace.centroidLng)
            val a = sin(dLat/2).pow(2) + cos(Math.toRadians(homePlace.centroidLat)) * cos(Math.toRadians(it.centroidLat)) * sin(dLng/2).pow(2)
            R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        } ?: 0.0
    } else 0.0

    val deviceEvents7d = deviceEvents.filter { it.timestamp >= since7d }

    val mobileBt7d   = btEvents7d.filterNot { isBtInfrastructure(it.deviceType, it.serviceUuids, it.deviceName) }
    val uniqueMobile = mobileBt7d.map { it.addressHash }.toSet().size
    val msPerDay     = TimeUnit.DAYS.toMillis(1)
    val avgDailyMobile = if (mobileBt7d.isNotEmpty()) {
        mobileBt7d.groupBy { it.timestamp / msPerDay }
            .values.map { it.map { e -> e.addressHash }.toSet().size }.average().toFloat()
    } else 0f
    val cohabCount = homePlace?.cohabitationCount ?: 0

    val resumeEvents = usageEvents.filter { it.eventType == 1 }
    val hourCounts   = IntArray(24)
    resumeEvents.forEach { hourCounts[Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY)]++ }
    val peakHour = hourCounts.indices.maxByOrNull { hourCounts[it] }?.takeIf { hourCounts[it] > 0 }
    val systemPkgPrefixes = listOf("com.android.", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.packageinstaller", "com.sec.android.app.launcher", "com.miui.launcher",
        "com.huawei.android.launcher", "com.oppo.launcher", "com.oneplus.launcher")
    val systemPkgKeywords = listOf("launcher", "systemui", "inputmethod", "keyboard", "ime.", ".phone", ".dialer")
    fun isSystemPkg(pkg: String) = pkg == "android" ||
            systemPkgPrefixes.any { pkg.startsWith(it) } || systemPkgKeywords.any { pkg.contains(it) }

    val launchCounts = db.appUsageEventDao().getLaunchCountsPerPackage(since7d)
        .filter { !isSystemPkg(it.packageName) }
    val fgMsReport = mutableMapOf<String, Long>()
    usageEvents.filter { !isSystemPkg(it.packageName) && it.packageName != context.packageName }
        .groupBy { it.packageName }.forEach { (pkg, evts) ->
            var lastR: Long? = null; var total = 0L
            evts.forEach { e ->
                when (e.eventType) {
                    1 -> lastR = e.timestamp
                    2 -> lastR?.let { r -> total += (e.timestamp - r).coerceIn(0L, 7_200_000L); lastR = null }
                }
            }
            if (total > 0) fgMsReport[pkg] = total
        }
    val topPkg = fgMsReport.entries.maxByOrNull { it.value }?.key
    val topApp = topPkg?.let {
        try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }
        catch (_: Exception) { null }
    }

    val screenActivations7d = deviceEvents7d.count { it.eventType == "SCREEN_ON" }
    val userPresent7d       = deviceEvents7d.count { it.eventType == "USER_PRESENT" }
    val sessionLengths = mutableListOf<Long>(); var lastOn: Long? = null; var totalScreenMs = 0L
    deviceEvents7d.forEach { evt ->
        when (evt.eventType) {
            "SCREEN_ON"  -> lastOn = evt.timestamp
            "SCREEN_OFF" -> lastOn?.let { on ->
                val dur = evt.timestamp - on
                if (dur in 1_000..7_200_000) { sessionLengths.add(dur); totalScreenMs += dur }
                lastOn = null
            }
        }
    }

    val firstActivationByDay = (0..29).mapNotNull { daysAgo ->
        val cal   = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
        val start = cal.apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }.timeInMillis
        val end   = start + TimeUnit.DAYS.toMillis(1)
        deviceEvents
            .filter { it.eventType == "SCREEN_ON" && it.timestamp in start..end }
            .filter { Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY) >= 5 }
            .minByOrNull { it.timestamp }
            ?.let { Calendar.getInstance().apply { timeInMillis = it.timestamp }.let { c -> c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) } }
    }
    val deviceDaysOfData = firstActivationByDay.size
    val routineStdDev = if (deviceDaysOfData >= 3) {
        val mean = firstActivationByDay.average()
        sqrt(firstActivationByDay.map { (it - mean).pow(2) }.average())
    } else null

    data class SW(val sleepTs: Long, val wakeTs: Long, val refined: Boolean)
    val offPairs = mutableListOf<Pair<Long, Long>>(); var lastOff: Long? = null
    deviceEvents.forEach { evt ->
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
        val la = usageEvents30d.filter { it.eventType == 1 && it.timestamp in (off - TimeUnit.MINUTES.toMillis(45))..off }.maxByOrNull { it.timestamp }
        val fa = usageEvents30d.filter { it.eventType == 1 && it.timestamp in on..(on + TimeUnit.MINUTES.toMillis(45)) }.minByOrNull { it.timestamp }
        SW(la?.timestamp ?: off, fa?.timestamp ?: on, la != null || fa != null)
    }
    val estSleepHour = if (sleepWindows.isNotEmpty()) circularMeanR(sleepWindows.map { Calendar.getInstance().apply { timeInMillis = it.sleepTs }.get(Calendar.HOUR_OF_DAY) }) else null
    val estWakeHour  = if (sleepWindows.isNotEmpty()) sleepWindows.map { Calendar.getInstance().apply { timeInMillis = it.wakeTs }.get(Calendar.HOUR_OF_DAY) }.average().roundToInt() else null

    val wifiCount  = networkEvents.count { it.networkType == "WIFI" && it.isConnected }
    val cellCount  = networkEvents.count { it.networkType == "CELLULAR" && it.isConnected }
    val wifiPct    = if (wifiCount + cellCount > 0) wifiCount * 100 / (wifiCount + cellCount) else 0


    val inferences = buildList<InferenceBlock> {
        add(InferenceBlock("Location", "📍", Color(0xFF10B981),
            headline = when {
                homePlace != null && workPlace != null -> "${homePlace.confidenceLabelR()} and ${workPlace.confidenceLabelR().lowercase()} identified"
                homePlace != null -> "${homePlace.confidenceLabelR()} identified (${homePlace.confidenceScore}% confidence)"
                locationEvents.isEmpty() -> "No location data yet"
                else -> "Movement data collected — place patterns building"
            },
            body = when {
                homePlace != null && workPlace != null ->
                    "Your device has mapped a home location visited on ${homePlace.distinctNightDays} distinct nights and a workplace visited on ${workPlace.distinctWeekdayDays} weekday daytime sessions." +
                            (if (venueCount > 0) " $venueCount recurring venue(s) also identified." else "") +
                            (if (mobilityRadius > 0) String.format(" Your regular activity spans %.1f km.", mobilityRadius) else "")
                homePlace != null -> "Your device has mapped a home location visited on ${homePlace.distinctNightDays} distinct nights. No consistent weekday destination has emerged yet."
                locationEvents.isNotEmpty() -> "Your movement has been recorded. Place patterns take shape after visiting the same location on 3 or more separate days."
                else -> "Enable location permissions to begin building your location profile."
            },
            supportingFact = "${locationEvents.size} fixes · ${activePlaces.size} place(s) mapped" +
                    (if (mobilityRadius > 0) String.format(" · %.1f km range", mobilityRadius) else "")
        ))
        add(InferenceBlock("Bluetooth", "📡", Color(0xFF60A5FA),
            headline = when {
                cohabCount > 0    -> "$cohabCount device(s) regularly present at home"
                uniqueMobile > 30 -> "High social exposure — $uniqueMobile devices (7d)"
                uniqueMobile > 0  -> "$uniqueMobile unique devices detected nearby (7d)"
                else              -> "No Bluetooth data"
            },
            body = when {
                cohabCount > 0 -> "$cohabCount device(s) appear regularly overnight at your home location. This week your device detected $uniqueMobile unique devices nearby, averaging ${String.format("%.1f", avgDailyMobile)} per day."
                uniqueMobile > 0 -> "Your device detected $uniqueMobile unique nearby devices this week, averaging ${String.format("%.1f", avgDailyMobile)} per day."
                else -> "Enable Bluetooth to detect nearby devices."
            },
            supportingFact = "$uniqueMobile devices (7d) · ${String.format("%.1f", avgDailyMobile)}/day · $cohabCount home contacts"
        ))
        add(InferenceBlock("App Usage", "📊", Color(0xFF8B5CF6),
            headline = when {
                topApp != null && peakHour != null -> "$topApp · most active at ${formatHour(peakHour)}"
                topApp != null -> "$topApp leads screen time this week"
                usageEvents.isNotEmpty() -> "${launchCounts.sumOf { it.launchCount }} app launches recorded"
                else -> "Grant Usage Stats permission in Settings"
            },
            body = when {
                topApp != null -> "You opened apps ${launchCounts.sumOf { it.launchCount }} times over 7 days with ${formatMinutes(TimeUnit.MILLISECONDS.toMinutes(totalScreenMs))} total screen time. $topApp received the most attention." + (if (peakHour != null) " Your peak activity window is around ${formatHour(peakHour)}." else "")
                usageEvents.isEmpty() -> "Grant Usage Stats permission in Settings to begin app usage tracking."
                else -> "${launchCounts.sumOf { it.launchCount }} launches recorded this week."
            },
            supportingFact = "${launchCounts.sumOf { it.launchCount }} launches · ${formatMinutes(TimeUnit.MILLISECONDS.toMinutes(totalScreenMs))} screen time"
        ))
        add(InferenceBlock("Device Behavior", "📲", Color(0xFFF43F5E),
            headline = when {
                estSleepHour != null && estWakeHour != null -> "Sleep ~${formatHour(estSleepHour)} · Wake ~${formatHour(estWakeHour)}"
                screenActivations7d > 0 -> "${screenActivations7d / 7} screen activations per day"
                else -> "Device state data collecting"
            },
            body = buildString {
                if (estSleepHour != null && estWakeHour != null) {
                    val dur = ((estWakeHour - estSleepHour + 24) % 24)
                    append("Your device goes dark around ${formatHour(estSleepHour)} and is active again by ${formatHour(estWakeHour)}, suggesting a ~$dur hour sleep window. ")
                }
                append("Your screen was activated ${screenActivations7d / 7} times per day on average this week.")
                if (sessionLengths.isNotEmpty()) append(" Typical session: ${TimeUnit.MILLISECONDS.toMinutes(sessionLengths.average().toLong())} minutes.")
            },
            supportingFact = "${screenActivations7d} activations (7d)" + (if (userPresent7d > 0) " · $userPresent7d unlocks" else "")
        ))
        add(InferenceBlock("Network", "🌐", Color(0xFFFB923C),
            headline = when {
                wifiPct >= 70 -> "Primarily connected via Wi-Fi ($wifiPct%)"
                wifiPct >= 40 -> "Mixed network use ($wifiPct% Wi-Fi)"
                wifiCount + cellCount > 0 -> "Primarily on cellular ($wifiPct% Wi-Fi)"
                else -> "No network transition data yet"
            },
            body = when {
                wifiPct >= 70 -> "Your device stays on known Wi-Fi networks most of the time — consistent with extended periods at fixed locations like home or an office."
                wifiCount + cellCount > 0 -> "Your device switches regularly between Wi-Fi and cellular, suggesting a daily commute or frequent movement between locations."
                else -> "Network transitions are logged automatically and require no permissions."
            },
            supportingFact = "Wi-Fi $wifiCount · Cellular $cellCount · $wifiPct% Wi-Fi (7d)"
        ))
    }

    val patterns = buildList<PatternBlock> {
        add(PatternBlock("Daily Routine", "🔄", Color(0xFF6366F1),
            score  = when { routineStdDev == null -> "Building ($deviceDaysOfData/3 days)"; routineStdDev < 30.0 -> "Very Regular"; routineStdDev < 75.0 -> "Moderately Regular"; else -> "Variable" },
            detail = when {
                routineStdDev == null -> "Your routine will be assessed after $deviceDaysOfData more day(s) of activity."
                routineStdDev < 30.0  -> "You start your day within 30 minutes of the same time each day."
                routineStdDev < 75.0  -> "Your day starts at roughly consistent times with some variation."
                else                  -> "Your daily start times vary significantly."
            }
        ))
        add(PatternBlock("Mobility Profile", "🗺", Color(0xFF10B981),
            score  = when { locationEvents.isEmpty() -> "No data"; mobilityRadius < 2.0 -> "Hyper-local"; mobilityRadius < 8.0 -> "Neighbourhood"; mobilityRadius < 25.0 -> "City-wide"; else -> "Regional" },
            detail = when {
                locationEvents.isEmpty() -> "Enable location permissions to build your mobility profile."
                homePlace == null        -> "Movement is being recorded. Your home location will be identified after a few nights at the same place."
                mobilityRadius < 2.0     -> "All your regular destinations are within 2 km of home."
                else                     -> String.format("Your regular activity spans %.1f km from home across %d mapped location(s).", mobilityRadius, activePlaces.size)
            }
        ))
        add(PatternBlock("Social Density", "👥", Color(0xFF60A5FA),
            score  = when { avgDailyMobile < 3f -> "Private"; avgDailyMobile < 8f -> "Low"; avgDailyMobile < 20f -> "Moderate"; else -> "High" },
            detail = when {
                avgDailyMobile < 3f  -> "You encounter very few other devices daily."
                avgDailyMobile < 8f  -> "You regularly encounter a small number of people."
                avgDailyMobile < 20f -> "You spend regular time in shared environments."
                else                 -> "You frequently move through high-density spaces."
            }
        ))
        add(PatternBlock("Sleep Pattern", "🌙", Color(0xFFA78BFA),
            score  = when { sleepWindows.isEmpty() -> "Building"; sleepWindows.size >= 5 -> "Established"; else -> "Partial (${sleepWindows.size}/5)" },
            detail = when {
                sleepWindows.isEmpty() -> "Your sleep window will become visible after a few more nights."
                estSleepHour != null && estWakeHour != null -> {
                    val dur = ((estWakeHour - estSleepHour + 24) % 24)
                    "Your device is typically dark from ${formatHour(estSleepHour)} to ${formatHour(estWakeHour)} — a ~$dur hour sleep window."
                }
                else -> "${sleepWindows.size} overnight period(s) recorded so far."
            }
        ))
        add(PatternBlock("Connectivity", "📶", Color(0xFFFB923C),
            score  = when { wifiCount + cellCount == 0 -> "No data"; wifiPct >= 75 -> "Fixed locations"; wifiPct >= 45 -> "Mixed"; else -> "Mobile" },
            detail = when {
                wifiCount + cellCount == 0 -> "Network data will appear as your device switches between Wi-Fi and cellular."
                wifiPct >= 75 -> "Your device stays connected to Wi-Fi the vast majority of the time."
                wifiPct >= 45 -> "Your device switches between Wi-Fi and cellular regularly."
                else          -> "Your device runs on cellular more than Wi-Fi."
            }
        ))
    }

    val logs = buildList<LogEntry> {
        locationEvents.takeLast(100).forEach { evt ->
            add(LogEntry(evt.timestamp, "Location", Color(0xFF10B981), "GPS Fix",
                if (evt.latitude != null) "%.4f, %.4f  ±${evt.accuracyMeters?.toInt() ?: "?"}m".format(evt.latitude, evt.longitude) else "No coords"))
        }
        btEvents7d.takeLast(100).forEach { evt ->
            add(LogEntry(evt.timestamp, "Bluetooth", Color(0xFF60A5FA), "${evt.deviceType} scanned", "RSSI ${evt.rssi ?: "?"}dBm"))
        }
        usageEvents.filter { it.packageName != context.packageName }.takeLast(100).forEach { evt ->
            add(LogEntry(evt.timestamp, "Usage", Color(0xFF8B5CF6), "${if (evt.eventType == 1) "RESUME" else "PAUSE"} · ${evt.packageName.substringAfterLast('.')}", evt.packageName))
        }
        deviceEvents7d.takeLast(100).forEach { evt ->
            add(LogEntry(evt.timestamp, "Device", Color(0xFFF43F5E), evt.eventType.replace('_', ' '), ""))
        }
        networkEvents.takeLast(100).forEach { evt ->
            add(LogEntry(evt.timestamp, "Network", Color(0xFFFB923C), "${evt.networkType} ${if (evt.isConnected) "connected" else "disconnected"}", ""))
        }
    }.sortedByDescending { it.timestamp }


    ReportData(inferences, patterns, logs)
}

@Composable
fun ReportScreen() {
    val context     = LocalContext.current
    val theme       = LocalAppTheme.current
    var data        by remember { mutableStateOf<ReportData?>(null) }
    var loading     by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs        = listOf("Inferences", "Patterns", "Raw Logs")

    val scope = rememberCoroutineScope()

    suspend fun reload(showLoading: Boolean = true) {
        try {
            if (showLoading) loading = true
            data = loadReport(context)
        } catch (e: Exception) {
            android.util.Log.e("ReportActivity", "reload error: ${e.message}")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload(showLoading = true) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2 && data != null) scope.launch { reload(showLoading = false) }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            while (true) {
                kotlinx.coroutines.delay(5_000L)
                if (data != null) reload(showLoading = false)
            }
        }
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && data != null) {
                scope.launch { reload(showLoading = false) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(bottomBar = { AppBottomNav(currentScreen = "Report") }, containerColor = Color.Transparent) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(theme.backgroundBrush).padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Intelligence Report", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                    color = theme.textPrimary, letterSpacing = (-1).sp)
                Text("Aggregated behavioral metadata", fontSize = 16.sp, color = theme.textSecondary)
            }

            TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent,
                contentColor = Color(0xFF4ADE80),
                divider = { HorizontalDivider(color = theme.borderColor, thickness = 1.dp) }) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(title,
                            fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == i) Color(0xFF4ADE80) else theme.textTertiary) })
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF4ADE80), strokeWidth = 2.dp)
                }
            } else {
                val d = data ?: return@Column
                when (selectedTab) {
                    0 -> InferencesTab(d.inferences, theme)
                    1 -> PatternsTab(d.patterns, theme)
                    2 -> LogsTab(d.logs, theme, onRefresh = { scope.launch { reload(showLoading = false) } })
                }
            }
        }
    }
}

@Composable
private fun InferencesTab(inferences: List<InferenceBlock>, theme: com.example.androidxpose.ui.theme.AppTheme) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        inferences.forEach { InferenceCard(it, theme) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InferenceCard(b: InferenceBlock, theme: com.example.androidxpose.ui.theme.AppTheme) {
    Surface(color = theme.surfaceColor, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, b.accentColor.copy(0.25f)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).background(b.accentColor.copy(0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) { Text(b.icon, fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(b.category, fontSize = 11.sp, color = b.accentColor, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                    Text(b.headline, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                }
            }
            HorizontalDivider(color = theme.borderColor)
            Text(b.body, fontSize = 14.sp, color = theme.textSecondary, lineHeight = 22.sp)
            Surface(color = b.accentColor.copy(0.1f), shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, b.accentColor.copy(0.2f))) {
                Text(b.supportingFact, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    fontSize = 11.sp, color = b.accentColor)
            }
        }
    }
}

@Composable
private fun PatternsTab(patterns: List<PatternBlock>, theme: com.example.androidxpose.ui.theme.AppTheme) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(12.dp))
        patterns.forEach { PatternCard(it, theme) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PatternCard(b: PatternBlock, theme: com.example.androidxpose.ui.theme.AppTheme) {
    Surface(color = theme.surfaceColor, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, theme.borderColor), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(20.dp)) {
            Box(modifier = Modifier.size(44.dp).background(b.accentColor.copy(0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) { Text(b.icon, fontSize = 20.sp) }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(b.title, fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 15.sp)
                    Surface(color = b.accentColor.copy(0.15f), shape = RoundedCornerShape(8.dp)) {
                        Text(b.score, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 11.sp, color = b.accentColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(b.detail, fontSize = 13.sp, color = theme.textSecondary, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun LogsTab(logs: List<LogEntry>, theme: com.example.androidxpose.ui.theme.AppTheme, onRefresh: () -> Unit = {}) {
    val categories   = listOf("All", "Location", "Bluetooth", "Usage", "Device", "Network")
    var selectedCat  by remember { mutableStateOf("All") }
    val filteredLogs = remember(selectedCat, logs) {
        if (selectedCat == "All") logs else logs.filter { it.category == selectedCat }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onRefresh) {
                Text("↻", fontSize = 18.sp, color = Color(0xFF4ADE80))
            }
            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { cat ->
                    val sel = cat == selectedCat
                    Surface(color = if (sel) Color(0xFF4ADE80).copy(0.15f) else theme.surfaceColor,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, if (sel) Color(0xFF4ADE80).copy(0.5f) else theme.borderColor),
                        modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(cat, modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { selectedCat = cat }.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) Color(0xFF4ADE80) else theme.textSecondary)
                    }
                }
            }
        }
        HorizontalDivider(color = theme.borderColor)
        if (filteredLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No ${if (selectedCat == "All") "" else "$selectedCat "}events", color = theme.textTertiary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filteredLogs) { LogRow(it, theme) }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry, theme: com.example.androidxpose.ui.theme.AppTheme) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.padding(top = 5.dp).size(8.dp).background(e.categoryColor, RoundedCornerShape(4.dp)))
        Column(modifier = Modifier.weight(1f)) {
            Text(e.summary, fontSize = 13.sp, color = theme.textPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.detail.isNotBlank()) Text(e.detail, fontSize = 11.sp, color = theme.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(e.timestamp.toRelative(), fontSize = 11.sp, color = theme.textTertiary)
    }
}