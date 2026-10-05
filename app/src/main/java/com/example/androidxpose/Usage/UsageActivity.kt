package com.example.androidxpose.usage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.ui.theme.AppBottomNav
import com.example.androidxpose.ui.theme.LocalAppTheme
import com.example.androidxpose.ui.theme.ThemeManager
import com.example.androidxpose.ui.theme.formatHour
import com.example.androidxpose.ui.theme.formatMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class UsageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        setContent {
            CompositionLocalProvider(LocalAppTheme provides ThemeManager.current) {
                UsageScreen()
            }
        }
    }
}

private val APP_CATEGORIES = mapOf(
    "com.instagram.android" to "Social", "com.facebook.katana" to "Social",
    "com.twitter.android" to "Social", "com.snapchat.android" to "Social",
    "com.linkedin.android" to "Social", "com.pinterest" to "Social",
    "com.reddit.frontpage" to "Social", "com.discord" to "Social",
    "com.whatsapp" to "Communication", "com.facebook.orca" to "Communication",
    "com.google.android.apps.messaging" to "Communication", "org.thoughtcrime.securesms" to "Communication",
    "com.microsoft.teams" to "Communication", "com.slack" to "Communication",
    "com.skype.raider" to "Communication", "com.google.android.gm" to "Communication",
    "com.microsoft.office.outlook" to "Communication",
    "com.netflix.mediaclient" to "Entertainment", "com.google.android.youtube" to "Entertainment",
    "com.spotify.music" to "Entertainment", "com.amazon.avod.thirdpartyclient" to "Entertainment",
    "com.hulu.plus" to "Entertainment", "com.disneyplus" to "Entertainment",
    "tv.twitch.android.app" to "Entertainment", "com.tiktok.musically" to "Entertainment",
    "com.google.android.apps.docs" to "Productivity", "com.microsoft.office.word" to "Productivity",
    "com.microsoft.office.excel" to "Productivity", "com.microsoft.office.powerpoint" to "Productivity",
    "com.google.android.keep" to "Productivity", "com.todoist" to "Productivity",
    "com.notion.id" to "Productivity", "com.google.android.calendar" to "Productivity",
    "com.chrome.browser" to "Productivity", "org.mozilla.firefox" to "Productivity",
    "com.android.chrome" to "Productivity",
    "com.king.candycrushsaga" to "Gaming", "com.supercell.clashofclans" to "Gaming",
    "com.activision.callofduty.shooter" to "Gaming", "com.roblox.client" to "Gaming",
    "com.nianticlabs.pokemongo" to "Gaming",
    "com.amazon.mShop.android.shopping" to "Shopping", "com.ebay.mobile" to "Shopping",
    "com.google.android.apps.maps" to "Utilities", "com.google.android.dialer" to "Utilities",
    "com.google.android.contacts" to "Utilities", "com.google.android.settings" to "Utilities"
)
private fun categorize(pkg: String): String = APP_CATEGORIES[pkg] ?: when {
    pkg.contains("game", ignoreCase = true)  -> "Gaming"
    pkg.contains("shop", ignoreCase = true)  -> "Shopping"
    pkg.contains("news", ignoreCase = true)  -> "News"
    pkg.contains("health", ignoreCase = true) -> "Health"
    pkg.contains("bank", ignoreCase = true) || pkg.contains("pay", ignoreCase = true) -> "Finance"
    else -> "Other"
}
private fun resolveAppName(context: Context, pkg: String): String = try {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
} catch (_: Exception) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }

private data class AppRow(
    val packageName: String, val appName: String, val category: String,
    val foregroundMinutes: Long, val launchCount: Int, val avgSessionSeconds: Long
)
private enum class SessionType { NOTIFICATION_CHECK, FOCUSED, BROWSING }
private data class UsageData(
    val totalScreenTimeMin: Long, val screenActivations7d: Int, val avgSessionMin: Long,
    val sessionTypeCounts: Map<SessionType, Int>, val topApps: List<AppRow>,
    val categoryMinutes: Map<String, Long>, val dailyCounts: List<Int>, val dayLabels: List<String>,
    val hourCountsWeekday: IntArray, val hourCountsWeekend: IntArray,
    val habitApp: String?, val habitStreak: Int, val peakHour: Int?, val usageGranted: Boolean
)

private suspend fun loadUsageData(context: Context): UsageData = withContext(Dispatchers.IO) {
    val db           = XposeDatabase.getInstance(context)
    val since7d      = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    val latestAudit  = db.permissionAuditDao().getLatestAudit()
    val usageGranted = latestAudit?.usageStats ?: false

    if (!usageGranted) return@withContext UsageData(
        totalScreenTimeMin = 0, screenActivations7d = 0, avgSessionMin = 0,
        sessionTypeCounts  = emptyMap(), topApps = emptyList(), categoryMinutes = emptyMap(),
        dailyCounts = List(7) { 0 }, dayLabels = buildDayLabels(),
        hourCountsWeekday  = IntArray(24), hourCountsWeekend = IntArray(24),
        habitApp = null, habitStreak = 0, peakHour = null, usageGranted = false
    )

    val usageEvents  = db.appUsageEventDao().getEventsSince(since7d).filter { it.usageGranted }.sortedBy { it.timestamp }
    val deviceEvents = db.deviceStateEventDao().getEventsSince(since7d).sortedBy { it.timestamp }
    val todayMidnight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
    val dayStarts = (6 downTo 0).map { d -> (todayMidnight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -d) }.timeInMillis }
    val dayLabels = dayStarts.map { SimpleDateFormat("EEE", Locale.US).format(Date(it)) }
    val dailyCounts = dayStarts.map { start -> val end = start + TimeUnit.DAYS.toMillis(1); deviceEvents.count { it.eventType == "SCREEN_ON" && it.timestamp in start..end } }
    val screenActivations7d = dailyCounts.sum()
    val sessionLengths = mutableListOf<Long>(); var lastOn: Long? = null; var totalScreenMs = 0L
    deviceEvents.forEach { evt ->
        when (evt.eventType) {
            "SCREEN_ON"  -> lastOn = evt.timestamp
            "SCREEN_OFF" -> lastOn?.let { on -> val dur = evt.timestamp - on; if (dur in 1_000..7_200_000) { sessionLengths.add(dur); totalScreenMs += dur }; lastOn = null }
        }
    }
    val avgSessionMin = TimeUnit.MILLISECONDS.toMinutes(if (sessionLengths.isNotEmpty()) sessionLengths.average().roundToLong() else 0L)
    val sessionTypes = mutableMapOf(SessionType.NOTIFICATION_CHECK to 0, SessionType.FOCUSED to 0, SessionType.BROWSING to 0)
    sessionLengths.forEach { durMs ->
        val mins = TimeUnit.MILLISECONDS.toMinutes(durMs)
        val type = when { mins < 1 -> SessionType.NOTIFICATION_CHECK; mins >= 20 -> SessionType.FOCUSED; else -> SessionType.BROWSING }
        sessionTypes[type] = (sessionTypes[type] ?: 0) + 1
    }
    val foregroundMs = mutableMapOf<String, Long>(); val launchCounts = mutableMapOf<String, Int>(); val sessionTimes = mutableMapOf<String, MutableList<Long>>()
    usageEvents.groupBy { it.packageName }.forEach { (pkg, events) ->
        var lastResume: Long? = null; var total = 0L; var launches = 0
        events.forEach { evt ->
            when (evt.eventType) {
                1 -> { lastResume = evt.timestamp; launches++ }
                2 -> lastResume?.let { r -> val dur = (evt.timestamp - r).coerceIn(0L, 7_200_000L); total += dur; sessionTimes.getOrPut(pkg) { mutableListOf() }.add(dur); lastResume = null }
            }
        }
        if (total > 0) foregroundMs[pkg] = total
        if (launches > 0) launchCounts[pkg] = launches
    }
    val topApps = foregroundMs.entries.sortedByDescending { it.value }.take(10).map { (pkg, ms) ->
        val sessions = sessionTimes[pkg] ?: emptyList()
        AppRow(pkg, resolveAppName(context, pkg), categorize(pkg), TimeUnit.MILLISECONDS.toMinutes(ms),
            launchCounts[pkg] ?: 0, if (sessions.isNotEmpty()) TimeUnit.MILLISECONDS.toSeconds(sessions.average().roundToLong()) else 0L)
    }
    val categoryMinutes = mutableMapOf<String, Long>()
    foregroundMs.forEach { (pkg, ms) -> val cat = categorize(pkg); categoryMinutes[cat] = (categoryMinutes[cat] ?: 0L) + TimeUnit.MILLISECONDS.toMinutes(ms) }
    val hourCountsWeekday = IntArray(24); val hourCountsWeekend = IntArray(24)
    usageEvents.filter { it.eventType == 1 }.forEach { evt ->
        val cal = Calendar.getInstance().apply { timeInMillis = evt.timestamp }
        val h = cal.get(Calendar.HOUR_OF_DAY); val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) hourCountsWeekend[h]++ else hourCountsWeekday[h]++
    }
    val peakHour = hourCountsWeekday.indices.maxByOrNull { hourCountsWeekday[it] + hourCountsWeekend[it] }?.takeIf { hourCountsWeekday[it] + hourCountsWeekend[it] > 0 }
    val firstOpenByDay = dayStarts.map { start ->
        val end = start + TimeUnit.DAYS.toMillis(1)
        deviceEvents.firstOrNull { it.eventType == "SCREEN_ON" && it.timestamp in start..end }?.let { on ->
            usageEvents.firstOrNull { it.eventType == 1 && it.timestamp in on.timestamp..(on.timestamp + 10_000) }?.packageName
        }
    }
    val habitCandidates = firstOpenByDay.filterNotNull().groupingBy { it }.eachCount().filter { it.value >= 4 }
    val habitPkg = habitCandidates.maxByOrNull { it.value }?.key
    UsageData(TimeUnit.MILLISECONDS.toMinutes(totalScreenMs), screenActivations7d, avgSessionMin, sessionTypes, topApps, categoryMinutes, dailyCounts, dayLabels, hourCountsWeekday, hourCountsWeekend, habitPkg?.let { resolveAppName(context, it) }, habitCandidates[habitPkg] ?: 0, peakHour, true)
}

private fun buildDayLabels(): List<String> {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0) }
    return (6 downTo 0).map { d -> (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -d) }.let { SimpleDateFormat("EEE", Locale.US).format(it.time) } }
}

@Composable
fun UsageScreen() {
    val context = LocalContext.current
    val theme   = LocalAppTheme.current
    var data    by remember { mutableStateOf<UsageData?>(null) }
    var loading by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    suspend fun reload(showLoading: Boolean = true) {
        try {
            if (showLoading) loading = true
            data = loadUsageData(context)
        } catch (e: Exception) {
            android.util.Log.e("UsageActivity", "reload error: ${e.message}")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload(showLoading = true) }

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

    Scaffold(bottomBar = { AppBottomNav("Usage") }, containerColor = Color.Transparent) { padding ->
        Column(
            modifier            = Modifier.fillMaxSize().background(theme.backgroundBrush)
                .padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Usage Insights", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = theme.textPrimary, letterSpacing = (-1).sp)
            Text("7-day behavioral analysis", fontSize = 16.sp, color = theme.textSecondary)

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF4ADE80), strokeWidth = 2.dp) }
                Spacer(Modifier.height(24.dp)); return@Column
            }

            val d = data ?: return@Column
            if (!d.usageGranted) {
                UsageSection("App Usage", "📊", theme) {
                    Text("Usage Stats permission not granted.\nOpen Settings → ⚡ Force Usage Sync after granting.", color = theme.textSecondary, fontSize = 14.sp, lineHeight = 22.sp)
                }
                Spacer(Modifier.height(24.dp)); return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatBadge("Screen Time", formatMinutes(d.totalScreenTimeMin), Color(0xFF818CF8), theme, Modifier.weight(1f))
                StatBadge("Activations", "${d.screenActivations7d}",          Color(0xFF4ADE80), theme, Modifier.weight(1f))
                StatBadge("Avg Session", "${d.avgSessionMin}m",               Color(0xFFFB923C), theme, Modifier.weight(1f))
            }

            if (d.habitApp != null) {
                Surface(color = Color(0xFF6366F1).copy(0.12f), shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF6366F1).copy(0.3f))) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("📱", fontSize = 28.sp)
                        Column {
                            Text("First-open habit", fontSize = 12.sp, color = Color(0xFF818CF8), fontWeight = FontWeight.SemiBold)
                            Text(d.habitApp, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                            Text("Opened first ${d.habitStreak} of the last 7 days within 10s of screen activation", fontSize = 11.sp, color = theme.textTertiary)
                        }
                    }
                }
            }

            val total     = d.sessionTypeCounts.values.sum().coerceAtLeast(1)
            val notifPct  = ((d.sessionTypeCounts[SessionType.NOTIFICATION_CHECK] ?: 0) * 100f / total).roundToInt()
            val focusPct  = ((d.sessionTypeCounts[SessionType.FOCUSED] ?: 0) * 100f / total).roundToInt()
            val browsePct = 100 - notifPct - focusPct
            UsageSection("Session Types", "🔍", theme) {
                Text("How long each screen session lasts", fontSize = 12.sp, color = theme.textTertiary)
                Spacer(Modifier.height(10.dp)); SessionTypeBar(notifPct, focusPct, browsePct); Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SessionTypeLegend("Quick\nCheck\n(<1min)", notifPct, Color(0xFFFB923C))
                    SessionTypeLegend("Standard\n(1–20min)", browsePct, Color(0xFF818CF8))
                    SessionTypeLegend("Extended\nUse\n(>20min)", focusPct, Color(0xFF4ADE80))
                }
                Spacer(Modifier.height(8.dp))
                when { notifPct > 60 -> InferenceTag("Most pickups are brief — fragmented, notification-driven usage", Color(0xFFFB923C)); focusPct > 40 -> InferenceTag("Frequent extended sessions — sustained, intentional engagement", Color(0xFF4ADE80)); else -> InferenceTag("Balanced mix of quick checks and longer sessions", Color(0xFF818CF8)) }
            }

            UsageSection("Daily Activity", "📅", theme) {
                Text("Screen activations per day", fontSize = 12.sp, color = theme.textTertiary); Spacer(Modifier.height(10.dp))
                VerticalBarChart(d.dailyCounts.map { it.toFloat() }, d.dayLabels, Color(0xFF818CF8), Modifier.fillMaxWidth().height(120.dp))
            }

            UsageSection("Top Apps", "📊", theme) {
                if (d.topApps.isEmpty()) {
                    Text("No app session data. Grant Usage Stats in Settings and run Force Sync.", color = theme.textSecondary, fontSize = 14.sp)
                } else {
                    Text("By screen time", fontSize = 12.sp, color = theme.textTertiary); Spacer(Modifier.height(12.dp))
                    val maxTime = d.topApps.maxOfOrNull { it.foregroundMinutes }?.coerceAtLeast(1L) ?: 1L
                    val barColors = listOf(Color(0xFF818CF8), Color(0xFF60A5FA), Color(0xFF4ADE80), Color(0xFFFB923C), Color(0xFFF43F5E), Color(0xFFFFD700), Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFFF97316))


                    d.topApps.forEachIndexed { i, app ->
                        HorizontalAppBar(
                            packageName = app.packageName,
                            appName = app.appName,
                            category = app.category,
                            value = app.foregroundMinutes.toFloat() / maxTime,
                            rawLabel = formatMinutes(app.foregroundMinutes),
                            barColor = barColors.getOrElse(i) { Color(0xFF818CF8) },
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }

                    if (d.categoryMinutes.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp)); HorizontalDivider(color = theme.borderColor); Spacer(Modifier.height(12.dp))
                        Text("By category", fontSize = 12.sp, color = theme.textTertiary); Spacer(Modifier.height(8.dp))
                        val totalCatMin = d.categoryMinutes.values.sum().coerceAtLeast(1L)
                        val catColors = mapOf("Social" to Color(0xFFF43F5E), "Entertainment" to Color(0xFFFB923C), "Productivity" to Color(0xFF4ADE80), "Communication" to Color(0xFF60A5FA), "Gaming" to Color(0xFF818CF8), "Shopping" to Color(0xFFFFD700), "Health" to Color(0xFF22D3EE), "Finance" to Color(0xFF34D399), "News" to Color(0xFFA78BFA), "Utilities" to Color(0xFF94A3B8), "Other" to Color(0xFF475569))
                        d.categoryMinutes.entries.sortedByDescending { it.value }.take(6).forEach { (cat, mins) ->
                            val pct = (mins * 100f / totalCatMin).roundToInt()
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(catColors[cat] ?: Color(0xFF475569), RoundedCornerShape(2.dp)))
                                    Text(cat, fontSize = 12.sp, color = theme.textSecondary)
                                }
                                Text("$pct% · ${formatMinutes(mins)}", fontSize = 12.sp, color = theme.textTertiary)
                            }
                        }
                    }
                }
            }

            UsageSection("Peak Hours", "🕐", theme) {
                Text("App launches by hour · weekday vs weekend", fontSize = 12.sp, color = theme.textTertiary); Spacer(Modifier.height(8.dp))
                DualHourHistogram(d.hourCountsWeekday, d.hourCountsWeekend)
                d.peakHour?.let { h ->
                    Spacer(Modifier.height(10.dp))
                    val weekdayPeak = d.hourCountsWeekday.indices.maxByOrNull { d.hourCountsWeekday[it] }
                    val weekendPeak = d.hourCountsWeekend.indices.maxByOrNull { d.hourCountsWeekend[it] }
                    val diff = if (weekdayPeak != null && weekendPeak != null) abs(weekdayPeak - weekendPeak) else 0
                    if (diff >= 3) InferenceTag("Weekday and weekend peaks differ by ${diff}h — distinct work/leisure schedule", Color(0xFF22D3EE))
                    else InferenceTag("Peak activity at ${formatHour(h)} · consistent across weekdays and weekends", Color(0xFF22D3EE))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UsageSection(title: String, icon: String, theme: com.example.androidxpose.ui.theme.AppTheme, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = theme.surfaceColor, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, theme.borderColor), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(icon, fontSize = 20.sp); Spacer(Modifier.width(10.dp)); Text(title, fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 18.sp) }
            Spacer(Modifier.height(16.dp)); content()
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String, color: Color, theme: com.example.androidxpose.ui.theme.AppTheme, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = color, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, fontSize = 10.sp, color = theme.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SessionTypeBar(notifPct: Int, focusPct: Int, browsePct: Int) {
    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        val w = size.width; var x = 0f
        if (notifPct > 0) { drawRect(Color(0xFFFB923C), topLeft = Offset(x, 0f), size = Size(w * notifPct / 100f, size.height)); x += w * notifPct / 100f }
        if (focusPct > 0) { drawRect(Color(0xFF4ADE80), topLeft = Offset(x, 0f), size = Size(w * focusPct / 100f, size.height)); x += w * focusPct / 100f }
        if (browsePct > 0) drawRect(Color(0xFF818CF8), topLeft = Offset(x, 0f), size = Size(w * browsePct / 100f, size.height))
    }
}

@Composable
private fun SessionTypeLegend(label: String, pct: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$pct%", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = Color.White.copy(0.5f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun VerticalBarChart(values: List<Float>, labels: List<String>, barColor: Color, modifier: Modifier = Modifier) {
    val maxVal = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val count = values.size; val barW = size.width / (count * 1.8f); val spacing = (size.width - barW * count) / (count + 1)
            drawLine(Color.White.copy(0.08f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            values.forEachIndexed { i, v -> val barH = (v / maxVal) * size.height * 0.88f; val x = spacing + i * (barW + spacing); if (barH > 0f) drawRoundRect(barColor, Offset(x, size.height - barH), Size(barW, barH), CornerRadius(6f), alpha = 0.85f) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            labels.forEach { l -> Text(l, fontSize = 10.sp, color = Color.White.copy(0.4f), textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HorizontalAppBar(
    packageName: String,
    appName: String,
    category: String,
    value: Float,
    rawLabel: String,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current


    var iconBitmap by remember(packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)

                val bitmap = drawable.toBitmap(100, 100)
                iconBitmap = bitmap.asImageBitmap()
            } catch (e: Exception) {

            }
        }
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!,
                contentDescription = "$appName icon",
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {

            Box(
                Modifier
                    .size(28.dp)
                    .background(Color.White.copy(0.1f), RoundedCornerShape(6.dp))
            )
        }

        Spacer(Modifier.width(8.dp))


        Column(modifier = Modifier.width(85.dp)) {
            Text(appName, fontSize = 12.sp, color = Color.White.copy(0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(category, fontSize = 9.sp, color = barColor.copy(0.7f))
        }
        Spacer(Modifier.width(6.dp))
        Canvas(modifier = Modifier.weight(1f).height(16.dp)) {
            drawRoundRect(barColor.copy(alpha = 0.15f), cornerRadius = CornerRadius(8f))
            val fillW = (size.width * value.coerceIn(0f, 1f)).coerceAtLeast(8f)
            drawRoundRect(barColor, size = Size(fillW, size.height), cornerRadius = CornerRadius(8f), alpha = 0.9f)
        }
        Spacer(Modifier.width(6.dp))
        Text(rawLabel, fontSize = 11.sp, color = barColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp), textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun DualHourHistogram(weekday: IntArray, weekend: IntArray) {
    val maxCount = (weekday.maxOrNull()?.coerceAtLeast(1) ?: 1).coerceAtLeast(weekend.maxOrNull()?.coerceAtLeast(1) ?: 1)
    Column {
        Canvas(modifier = Modifier.fillMaxWidth().height(90.dp)) {
            val slotW = size.width / 24f; val barW = (slotW * 0.45f).coerceAtLeast(1f)
            for (h in 0..23) {
                val wdH = (weekday[h].toFloat() / maxCount) * size.height * 0.9f; val weH = (weekend[h].toFloat() / maxCount) * size.height * 0.9f; val slotX = h * slotW
                if (wdH > 0f) drawRect(Color(0xFF818CF8), topLeft = Offset(slotX, size.height - wdH), size = Size(barW, wdH), alpha = 0.85f)
                if (weH > 0f) drawRect(Color(0xFF4ADE80), topLeft = Offset(slotX + barW + 1f, size.height - weH), size = Size(barW, weH), alpha = 0.85f)
            }
            drawLine(Color.White.copy(0.08f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12am","6am","12pm","6pm","12am").forEach { Text(it, fontSize = 9.sp, color = Color.White.copy(0.35f)) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { LegendDot(Color(0xFF818CF8), "Weekday"); LegendDot(Color(0xFF4ADE80), "Weekend") }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, fontSize = 10.sp, color = Color.White.copy(0.5f))
    }
}

@Composable
private fun InferenceTag(text: String, color: Color) {
    Surface(color = color.copy(0.1f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, color.copy(0.25f))) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}