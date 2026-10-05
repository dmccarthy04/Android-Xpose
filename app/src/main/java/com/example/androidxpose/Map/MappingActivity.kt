package com.example.androidxpose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidxpose.data.db.BluetoothEvent
import com.example.androidxpose.data.db.InferredPlace
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.ui.theme.AppBottomNav
import com.example.androidxpose.ui.theme.LocalAppTheme
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.*

val AURORA_UNIVERSITY = LatLng(41.7511, -88.3283)

private const val MAP_WINDOW_DAYS         = 30L
private const val DISTINCT_NIGHT_MIN      = 3
private const val DISTINCT_WEEKDAY_MIN    = 3
private const val DISTINCT_VENUE_MIN      = 3
private const val DISTINCT_FREQUENT_MIN   = 2
private const val COHABITATION_MIN_DAYS   = 3
private const val PLACE_STALE_DAYS        = 60L
private const val GHOST_MIN_AGE_DAYS      = 14L
private const val SCAN_WINDOW_MS          = 10 * 60 * 1000L
private const val RSSI_SIGMA_MAX          = 8.0
private const val PRESENCE_RATIO_MIN      = 0.55
private const val MIN_BT_HITS_FOR_CONTACT = 2
private const val PLACE_MATCH_RADIUS_FLOOR = 200.0
private val   BT_WINDOW_MS                = TimeUnit.MINUTES.toMillis(5)
private const val BT_TIER_MEDIUM          = 11
private const val BT_TIER_HIGH            = 31
private const val VISIT_GAP_MS            = 30 * 60 * 1000L
private const val FREQUENT_MIN_DWELL_MIN  = 5L
private const val VENUE_MIN_DWELL_MIN     = 15L
private const val WORK_MIN_DWELL_MIN      = 20L
private const val HOME_MIN_DWELL_MIN      = 30L
private const val INFERENCE_SPEED_GATE_MPS = 5.0f

private val COLOR_BT_LOW    = Color(0x4060A5FA)
private val COLOR_BT_MEDIUM = Color(0x507C3AED)
private val COLOR_BT_HIGH   = Color(0x60EF4444)
private const val BT_RADIUS_LOW    = 50.0
private const val BT_RADIUS_MEDIUM = 90.0
private const val BT_RADIUS_HIGH   = 140.0

private val BG_DARK        = Color(0xFF0F172A)
private val BG_CARD        = Color(0xFF1E293B)
private val TEXT_PRIMARY   = Color.White
private val TEXT_SECONDARY = Color.White.copy(alpha = 0.5f)
private val TEXT_LEGEND    = Color.White.copy(alpha = 0.65f)

private fun estimatedDistM(evt: BluetoothEvent): Float? {
    val tx   = evt.txPowerLevel?.takeIf { it in -127..20 }
    val rssi = evt.rssi
    if (tx != null && rssi != null) {
        val raw = Math.pow(10.0, (tx - rssi) / 20.0).toFloat()
        return raw.coerceIn(0.1f, 500f)
    }
    return null
}

private const val PROXIMITY_CLOSE_M = 8.0f
private val BLE_UUID_TYPE_MAP = mapOf(
    "180d" to "FITNESS", "1814" to "FITNESS", "1816" to "FITNESS", "1818" to "FITNESS",
    "181c" to "HEALTH",  "181d" to "HEALTH",  "181e" to "HEALTH",  "181f" to "HEALTH",
    "110b" to "AUDIO",   "110a" to "AUDIO",   "1108" to "AUDIO",   "1112" to "AUDIO",
    "1131" to "AUDIO",   "fd5a" to "AUDIO",
    "1812" to "HID",     "1124" to "HID",     "1105" to "HID",     "1106" to "HID",
    "febe" to "HID",     "fd6f" to "PROXIMITY_BEACON"
)
private val INFRA_NAME_PATTERNS = listOf(
    "HP ", "HP-", "HEWLETT", "EPSON", "CANON", "BROTHER", "ENVY", "OFFICEJET",
    "LASERJET", "PIXMA", "BRAVIA", "SAMSUNG TV", "LG TV", "VIZIO", "TCL-",
    "HISENSE", "ECHO", "ALEXA", "FIRETV", "FIRE TV", "FIRE-TV", "ROKU",
    "CHROMECAST", "APPLE TV", "XBOX", "PLAYSTATION", "PS4", "PS5", "PS3",
    "SOUNDBAR", "SONOS", "NEST ", "RING-", "WYZE", "TPLINK", "TP-LINK",
    "NETGEAR", "ASUS RT-", "LINKSYS", "TILE:", "AIRTAG", "SMARTTAG"
)
private val MAC_FRAGMENT_REGEX = Regex("[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}")

private fun reclassifyByUuid(uuids: String?): String? {
    if (uuids.isNullOrBlank()) return null
    val lower = uuids.lowercase()
    return BLE_UUID_TYPE_MAP.entries.firstOrNull { lower.contains(it.key) }?.value
}
private fun isInfrastructureByName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val upper = name.uppercase()
    if (MAC_FRAGMENT_REGEX.containsMatchIn(name)) return true
    return INFRA_NAME_PATTERNS.any { upper.contains(it) }
}
private fun effectiveDeviceType(e: BluetoothEvent): String =
    reclassifyByUuid(e.serviceUuids) ?: if (isInfrastructureByName(e.deviceName)) "HID" else e.deviceType
private fun String.isMobileDeviceType(): Boolean = this != "HID" && this != "PROXIMITY_BEACON"

private fun buildStaticHashSet(builders: List<ClusterBuilder>, sortedBt: List<BluetoothEvent>): Set<String> {
    data class DD(
        val clusterIndices: MutableSet<Int>  = mutableSetOf(),
        val rssi:           MutableList<Int> = mutableListOf(),
        val scanBuckets:    MutableSet<Long> = mutableSetOf()
    )
    val map = mutableMapOf<String, DD>()
    builders.forEachIndexed { ci, b ->
        sortedBtInRange(sortedBt, b.firstSeen - BT_WINDOW_MS, b.lastSeen + BT_WINDOW_MS).forEach { evt ->
            val r = evt.rssi ?: return@forEach
            map.getOrPut(evt.addressHash) { DD() }.apply {
                clusterIndices.add(ci)
                rssi.add(r)
                scanBuckets.add(evt.timestamp / SCAN_WINDOW_MS)
            }
        }
    }
    return map.filter { (_, d) ->
        if (d.clusterIndices.size != 1) return@filter false
        if (d.rssi.size < 3)           return@filter false
        val sigma = sqrt(d.rssi.map { (it - d.rssi.average()).pow(2) }.average())
        if (sigma >= RSSI_SIGMA_MAX)   return@filter false
        val ci            = d.clusterIndices.first()
        val b             = builders[ci]
        val spanMs        = (b.lastSeen - b.firstSeen).coerceAtLeast(SCAN_WINDOW_MS)
        val totalWindows  = spanMs / SCAN_WINDOW_MS
        val presenceRatio = d.scanBuckets.size.toDouble() / totalWindows
        presenceRatio >= PRESENCE_RATIO_MIN
    }.keys.toSet()
}

private fun sortedBtInRange(sorted: List<BluetoothEvent>, start: Long, end: Long): List<BluetoothEvent> {
    if (sorted.isEmpty()) return emptyList()
    var lo = 0; var hi = sorted.size
    while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m].timestamp < start) lo = m + 1 else hi = m }
    val result = mutableListOf<BluetoothEvent>(); var i = lo
    while (i < sorted.size && sorted[i].timestamp <= end) result.add(sorted[i++])
    return result
}

private fun btEventsInWindow(index: Map<Long, List<BluetoothEvent>>, centerTs: Long): List<BluetoothEvent> {
    val bucket = centerTs / BT_WINDOW_MS
    val start  = centerTs - BT_WINDOW_MS; val end = centerTs + BT_WINDOW_MS
    return ((bucket - 1)..(bucket + 1)).flatMap { index[it] ?: emptyList() }
        .filter { it.timestamp in start..end }
}

data class VisitRecord(val startTs: Long, val durationMinutes: Long)

private fun computeVisits(fixTimestamps: List<Long>): List<VisitRecord> {
    if (fixTimestamps.isEmpty()) return emptyList()
    val sorted = fixTimestamps.sorted()
    val visits = mutableListOf<VisitRecord>()
    var start = sorted[0]; var prev = sorted[0]
    sorted.drop(1).forEach { ts ->
        if (ts - prev > VISIT_GAP_MS) { visits.add(VisitRecord(start, TimeUnit.MILLISECONDS.toMinutes(prev - start))); start = ts }
        prev = ts
    }
    visits.add(VisitRecord(start, TimeUnit.MILLISECONDS.toMinutes(prev - start)))
    return visits
}

private fun computeConfidence(distinctDays: Int, fixes: Int, lastConfirmedMs: Long): Int {
    val dayScore     = (distinctDays * 4).coerceAtMost(60)
    val fixScore     = (fixes / 5).coerceAtMost(20)
    val daysSince    = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastConfirmedMs)
    val recencyScore = when { daysSince <= 7 -> 20; daysSince <= 14 -> 15; daysSince <= 30 -> 10; daysSince <= 60 -> 5; else -> 0 }
    return (dayScore + fixScore + recencyScore).coerceAtMost(100)
}

private fun buildConfidenceLabel(label: String, score: Int): String = when (label) {
    "HOME"     -> when { score >= 65 -> "Home"; score >= 40 -> "Likely Home"; else -> "Possible Home" }
    "WORK"     -> when { score >= 65 -> "Workplace"; score >= 40 -> "Likely Workplace"; else -> "Possible Workplace" }
    "VENUE"    -> when { score >= 65 -> "Recurring Venue"; score >= 40 -> "Possible Venue"; else -> "Occasional Venue" }
    "FREQUENT" -> "Frequent Location"
    else       -> ""
}

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hrs  = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when { mins < 2 -> "just now"; mins < 60 -> "${mins}m ago"; hrs < 24 -> "${hrs}h ago"; days < 30 -> "${days}d ago"; else -> "${days}d ago" }
}

private fun clusterHue(label: String?, isGhost: Boolean, isMostRecent: Boolean): Float = when {
    isMostRecent                -> BitmapDescriptorFactory.HUE_GREEN
    label == "HOME"   && isGhost -> BitmapDescriptorFactory.HUE_ORANGE
    label == "HOME"              -> BitmapDescriptorFactory.HUE_YELLOW
    label == "WORK"   && isGhost -> BitmapDescriptorFactory.HUE_VIOLET
    label == "WORK"              -> BitmapDescriptorFactory.HUE_AZURE
    label == "VENUE"  && isGhost -> BitmapDescriptorFactory.HUE_MAGENTA
    label == "VENUE"             -> BitmapDescriptorFactory.HUE_CYAN
    label == "FREQUENT"          -> 15.0f
    else                         -> BitmapDescriptorFactory.HUE_ROSE
}

private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

data class LocationCluster(
    val centroidLat: Double,
    val centroidLng: Double,
    val totalFixCount: Int,
    val bestAccuracyM: Float?,
    val firstSeen: Long,
    val lastSeen: Long,
    val visitCount: Int,
    val avgVisitMinutes: Long,
    val recentVisits: List<VisitRecord>,
    val mobileDeviceCount: Int,
    val staticDeviceCount: Int,
    val filteredInfraCount: Int,
    val cohabitationCount: Int,
    val distinctDays: Int,
    val isDwellCluster: Boolean,
    val isMostRecent: Boolean,
    val isGhost: Boolean,
    val inferredLabel: String?,
    val confidenceScore: Int,
    val confidenceLabel: String,
    val persistedPlaceId: Long?
)

private class ClusterBuilder(lat: Double, lng: Double, accuracy: Float?, speed: Float?, ts: Long) {
    private var sumLat = lat; private var sumLng = lng
    var totalCount         = 1
    var bestAccuracy       = accuracy
    var firstSeen          = ts; var lastSeen = ts
    var cohabitationCount  = 0
    val fixTimestamps      = mutableListOf(ts)
    val accuracyValues     = mutableListOf<Float>()
    val distinctDays       = mutableSetOf<Int>()
    val distinctNightDays  = mutableSetOf<Int>()
    val distinctWeekdayDays = mutableSetOf<Int>()

    init { accuracy?.let { accuracyValues.add(it) }; addDayBuckets(speed, ts) }

    val centroidLat get() = sumLat / totalCount
    val centroidLng get() = sumLng / totalCount

    fun absorb(lat: Double, lng: Double, accuracy: Float?, speed: Float?, ts: Long) {
        sumLat += lat; sumLng += lng; totalCount++
        accuracy?.let { if (bestAccuracy == null || it < bestAccuracy!!) bestAccuracy = it; accuracyValues.add(it) }
        if (ts < firstSeen) firstSeen = ts; if (ts > lastSeen) lastSeen = ts
        fixTimestamps.add(ts); addDayBuckets(speed, ts)
    }

    private fun addDayBuckets(speed: Float?, ts: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val h   = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        distinctDays.add(doy)
        val gated = speed == null || speed < INFERENCE_SPEED_GATE_MPS
        if (gated && (h >= 22 || h < 6)) distinctNightDays.add(doy)
        if (gated && dow in Calendar.MONDAY..Calendar.FRIDAY && h in 8..17) distinctWeekdayDays.add(doy)
    }
}

suspend fun loadMapData(context: android.content.Context): List<LocationCluster> =
    withContext(Dispatchers.IO) {
        try {
            val db  = XposeDatabase.getInstance(context)
            val now = System.currentTimeMillis()

            val since30d = now - TimeUnit.DAYS.toMillis(MAP_WINDOW_DAYS)
            val existingDeferred = coroutineScope { async {
                try { db.inferredPlaceDao().getAll() } catch (_: Exception) { emptyList() }
            }}
            val rawFixesDeferred = coroutineScope { async {
                var f = db.locationEventDao().getFixesSince(since30d).sortedBy { it.timestamp }
                if (f.isEmpty()) f = db.locationEventDao().getFixesSince(now - TimeUnit.DAYS.toMillis(90)).sortedBy { it.timestamp }
                f
            }}

            var rawFixes = rawFixesDeferred.await()
            val existingPlaces = existingDeferred.await()
            if (rawFixes.isEmpty()) {
                val stale = now - TimeUnit.DAYS.toMillis(PLACE_STALE_DAYS)
                return@withContext existingPlaces.filter { it.lastConfirmedMs > stale }.map { buildGhostCluster(it) }
            }
            val btDeferred = coroutineScope { async {
                db.bluetoothEventDao().getEventsSince(rawFixes.first().timestamp).sortedBy { it.timestamp }
            }}

            val trueLast  = rawFixes.last()
            val step      = (rawFixes.size / 3000).coerceAtLeast(1)
            val sampledBase = rawFixes.filterIndexed { i, _ -> i % step == 0 }
            val fixes = if (sampledBase.any { it.timestamp == trueLast.timestamp }) sampledBase
            else (sampledBase + trueLast).sortedBy { it.timestamp }

            val allAccuracies = fixes.mapNotNull { it.accuracyMeters }.sorted()
            val medianAccuracy = if (allAccuracies.isNotEmpty()) allAccuracies[allAccuracies.size / 2].toDouble() else 25.0
            val clusterRadius  = max(2.0 * medianAccuracy, 75.0)
            val matchRadius    = max(clusterRadius * 1.5, PLACE_MATCH_RADIUS_FLOOR)
            val degBound       = clusterRadius * 1.5 / 111_111.0

            val builders      = mutableListOf<ClusterBuilder>()
            val fixClusterIdx = IntArray(fixes.size) { -1 }

            for ((fIdx, fix) in fixes.withIndex()) {
                val lat = fix.latitude!!; val lng = fix.longitude!!
                var nearestIdx = -1; var nearestDist = Double.MAX_VALUE
                for ((bIdx, b) in builders.withIndex()) {
                    if (abs(b.centroidLat - lat) > degBound || abs(b.centroidLng - lng) > degBound * 2) continue
                    val d = haversineM(b.centroidLat, b.centroidLng, lat, lng)
                    if (d < nearestDist) { nearestDist = d; nearestIdx = bIdx }
                }
                if (nearestIdx < 0 && builders.isNotEmpty()) {
                    nearestIdx = 0; nearestDist = haversineM(builders[0].centroidLat, builders[0].centroidLng, lat, lng)
                    for (bIdx in 1 until builders.size) {
                        val d = haversineM(builders[bIdx].centroidLat, builders[bIdx].centroidLng, lat, lng)
                        if (d < nearestDist) { nearestDist = d; nearestIdx = bIdx }
                    }
                }
                if (nearestIdx >= 0 && nearestDist <= clusterRadius) {
                    builders[nearestIdx].absorb(lat, lng, fix.accuracyMeters, fix.speedMps, fix.timestamp)
                    fixClusterIdx[fIdx] = nearestIdx
                } else {
                    fixClusterIdx[fIdx] = builders.size
                    builders.add(ClusterBuilder(lat, lng, fix.accuracyMeters, fix.speedMps, fix.timestamp))
                }
            }

            val builderVisits = builders.map { b ->
                if (b.distinctDays.size >= DISTINCT_FREQUENT_MIN) computeVisits(b.fixTimestamps) else emptyList()
            }
            val builderAvgVisitMin = builderVisits.map { visits ->
                if (visits.isNotEmpty()) visits.map { it.durationMinutes }.average().roundToLong() else 0L
            }

            val homeIdx = builders.indices
                .filter { builders[it].distinctNightDays.size >= DISTINCT_NIGHT_MIN }
                .filter { builderAvgVisitMin[it] >= HOME_MIN_DWELL_MIN }
                .maxByOrNull { builders[it].distinctNightDays.size }

            val workIdx = builders.indices
                .filter { it != homeIdx && builders[it].distinctWeekdayDays.size >= DISTINCT_WEEKDAY_MIN }
                .filter { builderAvgVisitMin[it] >= WORK_MIN_DWELL_MIN }
                .maxByOrNull { builders[it].distinctWeekdayDays.size }

            val lastFixClusterIdx = fixClusterIdx.getOrNull(fixes.indexOfLast { it.timestamp == trueLast.timestamp })

            val sortedBt   = btDeferred.await()
            val btBucketIdx = sortedBt.groupBy { it.timestamp / BT_WINDOW_MS }
            val staticHashes = buildStaticHashSet(builders, sortedBt)

            val actualFixCounts = IntArray(builders.size)
            for (fix in rawFixes) {
                val lat = fix.latitude ?: continue
                val lng = fix.longitude ?: continue
                var best = -1; var bestDist = Double.MAX_VALUE
                for ((i, b) in builders.withIndex()) {
                    if (abs(b.centroidLat - lat) > degBound || abs(b.centroidLng - lng) > degBound * 2) continue
                    val d = haversineM(b.centroidLat, b.centroidLng, lat, lng)
                    if (d < bestDist) { bestDist = d; best = i }
                }
                if (best >= 0 && bestDist <= clusterRadius) actualFixCounts[best]++
            }

            if (homeIdx != null) {
                val hb   = builders[homeIdx]
                val step = (hb.fixTimestamps.size / 150).coerceAtLeast(1)
                val sampleTs = hb.fixTimestamps.filterIndexed { i, _ -> i % step == 0 }
                val hitsByDevice  = mutableMapOf<String, MutableSet<Int>>()
                val daysByDevice  = mutableMapOf<String, MutableSet<Int>>()
                val closeByDevice = mutableMapOf<String, Boolean>()
                val hasDistByDevice = mutableMapOf<String, Boolean>()
                val reusedCal = Calendar.getInstance()
                sampleTs.forEach { fixTs ->
                    btEventsInWindow(btBucketIdx, fixTs)
                        .filter { effectiveDeviceType(it).isMobileDeviceType() && it.addressHash !in staticHashes }
                        .forEach { evt ->
                            reusedCal.timeInMillis = evt.timestamp
                            val day    = reusedCal.get(Calendar.DAY_OF_YEAR)
                            val bucket = (evt.timestamp / BT_WINDOW_MS).toInt()
                            hitsByDevice.getOrPut(evt.addressHash) { mutableSetOf() }.add(bucket)
                            daysByDevice.getOrPut(evt.addressHash) { mutableSetOf() }.add(day)
                            val dist = estimatedDistM(evt)
                            if (dist != null) {
                                hasDistByDevice[evt.addressHash] = true
                                if (dist <= PROXIMITY_CLOSE_M) closeByDevice[evt.addressHash] = true
                            }
                        }
                }
                hb.cohabitationCount = hitsByDevice
                    .filter { (hash, hits) -> hits.size >= MIN_BT_HITS_FOR_CONTACT }
                    .filter { (hash, _)    -> (daysByDevice[hash]?.size ?: 0) >= COHABITATION_MIN_DAYS }
                    .count  { (hash, _)    -> hasDistByDevice[hash] != true || closeByDevice[hash] == true }
            }

            val usedPlaceIds = mutableSetOf<Long>()
            val builderToPlace = builders.mapIndexed { idx, b ->
                val match = existingPlaces
                    .filter { it.id !in usedPlaceIds }
                    .filter { haversineM(it.centroidLat, it.centroidLng, b.centroidLat, b.centroidLng) <= matchRadius }
                    .minByOrNull { haversineM(it.centroidLat, it.centroidLng, b.centroidLat, b.centroidLng) }
                match?.let { usedPlaceIds.add(it.id) }
                idx to match
            }.toMap()

            val placesToUpsert = mutableListOf<InferredPlace>()

            val mainClusters = builders.mapIndexed { idx, b ->
                val existing          = builderToPlace[idx]
                val mergedDistinct    = max(existing?.distinctDays ?: 0, b.distinctDays.size)
                val mergedNight       = max(existing?.distinctNightDays ?: 0, b.distinctNightDays.size)
                val mergedWeekday     = max(existing?.distinctWeekdayDays ?: 0, b.distinctWeekdayDays.size)
                val mergedFixes       = actualFixCounts[idx]
                val lastConfirmed     = if (b.totalCount > 0) now else (existing?.lastConfirmedMs ?: now)

                val freshLabel = when (idx) {
                    homeIdx -> "HOME"
                    workIdx -> "WORK"
                    else    -> when {
                        b.distinctDays.size >= DISTINCT_VENUE_MIN &&
                                builderAvgVisitMin[idx] >= VENUE_MIN_DWELL_MIN -> "VENUE"
                        b.distinctDays.size >= DISTINCT_FREQUENT_MIN &&
                                builderAvgVisitMin[idx] >= FREQUENT_MIN_DWELL_MIN -> "FREQUENT"
                        else -> "TRANSIT"
                    }
                }

                val confidence   = computeConfidence(mergedDistinct, mergedFixes, lastConfirmed)
                val displayLabel = freshLabel.takeIf { it != "TRANSIT" }

                if (freshLabel != "TRANSIT" || existing != null) {
                    placesToUpsert.add(InferredPlace(
                        id                   = existing?.id ?: 0,
                        label                = if (freshLabel != "TRANSIT") freshLabel else "TRANSIT",
                        centroidLat          = b.centroidLat,
                        centroidLng          = b.centroidLng,
                        radiusM              = clusterRadius,
                        confidenceScore      = confidence,
                        distinctDays         = mergedDistinct,
                        distinctNightDays    = mergedNight,
                        distinctWeekdayDays  = mergedWeekday,
                        totalStationaryFixes = mergedFixes,
                        cohabitationCount    = if (idx == homeIdx) b.cohabitationCount
                        else (existing?.cohabitationCount ?: 0),
                        firstInferredMs      = existing?.firstInferredMs ?: now,
                        lastConfirmedMs      = lastConfirmed
                    ))
                }

                val isMostRecent = idx == lastFixClusterIdx
                val mobileHashes: Set<String>
                val staticSet: Set<String>
                val filteredInfra: Int
                if (freshLabel != "TRANSIT" || isMostRecent) {
                    val step     = (b.fixTimestamps.size / 150).coerceAtLeast(1)
                    val btSample = b.fixTimestamps.filterIndexed { i, _ -> i % step == 0 }
                    val staticSetM        = mutableSetOf<String>()
                    var filteredInfraM    = 0
                    val mobileHitBuckets = mutableMapOf<String, MutableSet<Int>>()
                    btSample.forEach { fixTs ->
                        btEventsInWindow(btBucketIdx, fixTs).forEach { evt ->
                            val etype = effectiveDeviceType(evt)
                            when {
                                !etype.isMobileDeviceType()     -> { staticSetM.add(evt.addressHash); filteredInfraM++ }
                                evt.addressHash in staticHashes -> filteredInfraM++
                                else -> mobileHitBuckets.getOrPut(evt.addressHash) { mutableSetOf() }
                                    .add((evt.timestamp / BT_WINDOW_MS).toInt())
                            }
                        }
                    }
                    mobileHashes  = mobileHitBuckets.filter { (_, b) -> b.size >= MIN_BT_HITS_FOR_CONTACT }.keys.toSet()
                    staticSet      = staticSetM
                    filteredInfra  = filteredInfraM
                } else {
                    mobileHashes  = emptySet()
                    staticSet      = emptySet()
                    filteredInfra  = 0
                }

                val visits      = builderVisits[idx]
                val avgVisitMin = builderAvgVisitMin[idx]

                LocationCluster(
                    centroidLat       = b.centroidLat,
                    centroidLng       = b.centroidLng,
                    totalFixCount     = actualFixCounts[idx],
                    bestAccuracyM     = b.bestAccuracy,
                    firstSeen         = b.firstSeen,
                    lastSeen          = b.lastSeen,
                    visitCount        = visits.size,
                    avgVisitMinutes   = avgVisitMin,
                    recentVisits      = visits.sortedByDescending { it.startTs }.take(5),
                    mobileDeviceCount = mobileHashes.size,
                    staticDeviceCount = staticSet.size,
                    filteredInfraCount = filteredInfra,
                    cohabitationCount = b.cohabitationCount,
                    isDwellCluster    = b.distinctDays.size >= DISTINCT_VENUE_MIN,
                    distinctDays      = b.distinctDays.size,
                    isMostRecent      = isMostRecent,
                    isGhost           = false,
                    inferredLabel     = displayLabel,
                    confidenceScore   = confidence,
                    confidenceLabel   = buildConfidenceLabel(displayLabel ?: "", confidence),
                    persistedPlaceId  = existing?.id
                )
            }

            val matchedIds = placesToUpsert.mapNotNull { it.id.takeIf { id -> id > 0 } }.toSet()
            existingPlaces.filter { it.id !in matchedIds }.forEach { place ->
                val updatedLabel = if (place.label == "FREQUENT" || place.label == "VENUE") "TRANSIT" else place.label
                placesToUpsert.add(place.copy(
                    label           = updatedLabel,
                    confidenceScore = computeConfidence(place.distinctDays, place.totalStationaryFixes, place.lastConfirmedMs)
                ))
            }
            try { db.inferredPlaceDao().upsertAll(placesToUpsert) }
            catch (e: Exception) { android.util.Log.w("MappingActivity", "upsert failed: ${e.message}") }

            val stale       = now - TimeUnit.DAYS.toMillis(PLACE_STALE_DAYS)
            val ghostMinAge = now - TimeUnit.DAYS.toMillis(GHOST_MIN_AGE_DAYS)
            val ghosts = existingPlaces
                .filter { it.id !in matchedIds && it.lastConfirmedMs > stale && it.lastConfirmedMs < ghostMinAge }
                .filter { it.label != "TRANSIT" }
                .map { buildGhostCluster(it) }

            mainClusters + ghosts
        } catch (e: Exception) {
            android.util.Log.e("MappingActivity", "loadMapData failed: ${e.message}", e)
            emptyList()
        }
    }

private fun buildGhostCluster(p: InferredPlace) = LocationCluster(
    centroidLat = p.centroidLat, centroidLng = p.centroidLng,
    totalFixCount = 0, bestAccuracyM = null,
    firstSeen = p.firstInferredMs, lastSeen = p.lastConfirmedMs,
    visitCount = 0, avgVisitMinutes = 0, recentVisits = emptyList(),
    mobileDeviceCount = 0, staticDeviceCount = 0, filteredInfraCount = 0,
    cohabitationCount = p.cohabitationCount,
    isDwellCluster = true, distinctDays = 0, isMostRecent = false, isGhost = true,
    inferredLabel = p.label, confidenceScore = p.confidenceScore,
    confidenceLabel = buildConfidenceLabel(p.label, p.confidenceScore),
    persistedPlaceId = p.id
)

@Composable
fun MapOverlays(
    clusters: List<LocationCluster>,
    previewMode: Boolean = false
) {
    val labeled  = clusters.filter { it.inferredLabel != null || it.isMostRecent }
    val transit  = if (previewMode) emptyList()
    else clusters.filter { it.inferredLabel == null && !it.isMostRecent && !it.isGhost }
        .sortedByDescending { it.lastSeen }
    val display  = labeled + transit

    labeled.forEach { cluster ->
        val pos = LatLng(cluster.centroidLat, cluster.centroidLng)
        if (cluster.mobileDeviceCount > 0) {
            val (color, radius) = when {
                cluster.mobileDeviceCount >= BT_TIER_HIGH   -> COLOR_BT_HIGH   to BT_RADIUS_HIGH
                cluster.mobileDeviceCount >= BT_TIER_MEDIUM -> COLOR_BT_MEDIUM to BT_RADIUS_MEDIUM
                else                                         -> COLOR_BT_LOW    to BT_RADIUS_LOW
            }
            Circle(center = pos, radius = radius, strokeColor = color, strokeWidth = 1.5f,
                fillColor = if (cluster.isGhost) color.copy(alpha = 0.1f) else color)
        }
    }

    display.forEach { cluster ->
        val pos = LatLng(cluster.centroidLat, cluster.centroidLng)
        val alpha = when {
            cluster.isGhost                          -> 0.7f
            cluster.inferredLabel != null            -> 1.0f
            cluster.isMostRecent                     -> 1.0f
            cluster.totalFixCount >= 6               -> 1.0f
            cluster.totalFixCount >= 2               -> 0.7f
            else                                     -> 0.5f
        }
        Marker(
            state   = MarkerState(position = pos),
            title   = buildClusterTitle(cluster),
            snippet = buildClusterSnippet(cluster),
            icon    = BitmapDescriptorFactory.defaultMarker(clusterHue(cluster.inferredLabel, cluster.isGhost, cluster.isMostRecent)),
            alpha   = alpha
        )
    }
}

private fun buildClusterTitle(c: LocationCluster): String {
    val recency  = if (!c.isGhost && c.lastSeen > 0) " · ${relativeTime(c.lastSeen)}" else ""
    val fixCount = if (!c.isGhost && c.totalFixCount > 0) " · ${c.totalFixCount} fix${if (c.totalFixCount != 1) "es" else ""}" else ""
    return when {
        c.isGhost -> "📍 Remembered · ${relativeTime(c.lastSeen)}"
        c.inferredLabel == "HOME"     -> "🏠 ${c.confidenceLabel} (${c.confidenceScore}%)$fixCount$recency"
        c.inferredLabel == "WORK"     -> "🏢 ${c.confidenceLabel} (${c.confidenceScore}%)$fixCount$recency"
        c.inferredLabel == "VENUE"    -> "📍 ${c.confidenceLabel}$fixCount$recency"
        c.inferredLabel == "FREQUENT" -> "📌 Frequent Location$fixCount$recency"
        c.isMostRecent                -> "📍 Most Recent$fixCount · ${relativeTime(c.lastSeen)}"
        else                          -> "· Transit$fixCount$recency"
    }
}

private fun buildClusterSnippet(c: LocationCluster): String {
    if (c.isGhost) return "Last seen ${TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - c.lastSeen)}d ago"
    val parts = mutableListOf<String>()
    if (c.visitCount > 0 && c.avgVisitMinutes > 0)
        parts.add("${c.visitCount} visit(s) · avg ${c.avgVisitMinutes}min")
    else if (c.visitCount > 0)
        parts.add("${c.visitCount} visit(s)")
    else
        c.bestAccuracyM?.let { parts.add("±${it.toInt()}m") }
    if (c.inferredLabel != null || c.isMostRecent) {
        when {
            c.mobileDeviceCount >= BT_TIER_HIGH   -> parts.add("${c.mobileDeviceCount} devices · high exposure")
            c.mobileDeviceCount >= BT_TIER_MEDIUM -> parts.add("${c.mobileDeviceCount} devices · moderate exposure")
            c.mobileDeviceCount > 0               -> parts.add("${c.mobileDeviceCount} nearby device(s)")
            c.cohabitationCount > 0               -> parts.add("${c.cohabitationCount} frequent contact(s)")
        }
    }
    return parts.joinToString(" · ")
}

class MappingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.androidxpose.ui.theme.ThemeManager.init(this)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.example.androidxpose.ui.theme.LocalAppTheme provides com.example.androidxpose.ui.theme.ThemeManager.current
            ) {
                MaterialTheme {
                    MappingFeatureHub(onGoHome = {
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                    })
                }
            }
        }
    }
}

@Composable
fun MappingFeatureHub(onGoHome: () -> Unit) {
    val theme   = LocalAppTheme.current
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var isFullMapMode by remember { mutableStateOf(false) }
    var clusters      by remember { mutableStateOf<List<LocationCluster>>(emptyList()) }
    var isLoading     by remember { mutableStateOf(true) }

    suspend fun reload(showLoading: Boolean = true) {
        if (showLoading) isLoading = true
        try {
            val db    = XposeDatabase.getInstance(context)
            val now   = System.currentTimeMillis()
            val stale = now - TimeUnit.DAYS.toMillis(PLACE_STALE_DAYS)
            val minAge = now - TimeUnit.DAYS.toMillis(GHOST_MIN_AGE_DAYS)
            val places = withContext(Dispatchers.IO) { db.inferredPlaceDao().getAll() }
            val ghosts = places.filter {
                it.lastConfirmedMs > stale && it.lastConfirmedMs < minAge && it.label != "TRANSIT"
            }.map { buildGhostCluster(it) }
            if (ghosts.isNotEmpty()) { clusters = ghosts; isLoading = false }
        } catch (_: Exception) {}
        try {
            val fresh = loadMapData(context)
            clusters = fresh
        } catch (e: Exception) {
            android.util.Log.e("MappingActivity", "Data load failed: ${e.message}")
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !isLoading) {
                scope.launch { reload(showLoading = false) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isFullMapMode) {
        if (!isFullMapMode) {
            while (true) {
                kotlinx.coroutines.delay(15_000L)
                if (!isLoading) reload(showLoading = false)
            }
        }
    }

    BackHandler(enabled = isFullMapMode) { isFullMapMode = false }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(visible = !isFullMapMode) {
                AppBottomNav(currentScreen = "Mapping")
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.backgroundBrush)
                .padding(padding)
        ) {
            if (isFullMapMode) {
                FullMapScreen(clusters = clusters, onBackToPreview = { isFullMapMode = false })
            } else {
                MappingPreviewContent(clusters = clusters, isLoading = isLoading, onExpandMap = { isFullMapMode = true })
            }
        }
    }
}

@Composable
fun MappingPreviewContent(clusters: List<LocationCluster>, isLoading: Boolean, onExpandMap: () -> Unit) {
    val theme = LocalAppTheme.current


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {

        Text(
            text = "Location Activity",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = theme.textPrimary
        )
        Text(
            text = "30-day behavioral map",
            color = theme.textSecondary,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(20.dp))


        if (clusters.isNotEmpty()) {
            ClusterSummaryPanel(clusters)
            Spacer(Modifier.height(16.dp))
        } else if (isLoading) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF4ADE80), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(16.dp))
        }


        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(32.dp))
                .clickable { onExpandMap() },
            border = BorderStroke(1.dp, theme.borderColor),
            colors = CardDefaults.cardColors(
                containerColor = theme.cardBackground
            ),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                val previewCameraState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(AURORA_UNIVERSITY, 13.5f)
                }
                LaunchedEffect(clusters.isNotEmpty()) {
                    if (clusters.isNotEmpty()) {
                        val best = clusters.filter { !it.isGhost }.maxByOrNull { it.lastSeen }
                        if (best != null) {
                            previewCameraState.position = CameraPosition.fromLatLngZoom(
                                LatLng(best.centroidLat, best.centroidLng), 13.5f
                            )
                        }
                    }
                }

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = previewCameraState,
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        scrollGesturesEnabled = false,
                        zoomGesturesEnabled = false,
                        rotationGesturesEnabled = false,
                        tiltGesturesEnabled = false
                    )
                ) {
                    MapOverlays(clusters = clusters, previewMode = true)
                }


                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(50.dp)
                    ) {
                        Text(
                            text = "Tap to Explore Map",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))


        MapLegend()


        Spacer(Modifier.height(100.dp))
    }
}

@Composable
fun ClusterSummaryPanel(clusters: List<LocationCluster>) {
    val theme = LocalAppTheme.current
    if (clusters.isEmpty()) return

    val real         = clusters.filter { !it.isGhost }
    val home         = clusters.find { it.inferredLabel == "HOME" }
    val work         = clusters.find { it.inferredLabel == "WORK" }
    val venueCount   = clusters.count { it.inferredLabel == "VENUE" }
    val freqCount    = clusters.count { it.inferredLabel == "FREQUENT" }
    val cohab        = home?.cohabitationCount ?: 0
    val maxMobile    = real.maxOfOrNull { it.mobileDeviceCount } ?: 0
    val filtered     = real.sumOf { it.filteredInfraCount }

    Surface(
        color = theme.cardBackground,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, theme.textPrimary.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryChip("${real.sumOf { it.totalFixCount }} fixes", Color(0xFF22D3EE))
                SummaryChip(if (home != null) "🏠 ${home.confidenceLabel}" else "No home", Color(0xFFFFD700))
                if (work != null) SummaryChip("🏢 ${work.confidenceLabel}", Color(0xFF60A5FA))
            }


            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (venueCount > 0) SummaryChip("$venueCount venue(s)", Color(0xFF22D3EE))
                if (freqCount > 0)  SummaryChip("$freqCount frequent", Color(0xFFFF3300))
                if (cohab > 0)      SummaryChip("$cohab cohabiting", Color(0xFF818CF8))
            }

            HorizontalDivider(color = theme.textPrimary.copy(0.07f))


            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "peak cluster: $maxMobile devices",
                        fontSize = 11.sp,
                        color = theme.textSecondary
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "$filtered devices filtered (labeled places)",
                        fontSize = 11.sp,
                        color = theme.textSecondary
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "30-day behavioral window",
                        fontSize = 11.sp,
                        color = theme.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun FullMapScreen(clusters: List<LocationCluster>, onBackToPreview: () -> Unit) {

    val fullCameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(AURORA_UNIVERSITY, 14f)
    }
    LaunchedEffect(clusters.isNotEmpty()) {
        if (clusters.isNotEmpty()) {
            val best = clusters.filter { !it.isGhost }.maxByOrNull { it.lastSeen }
            if (best != null) {
                fullCameraState.position = CameraPosition.fromLatLngZoom(
                    LatLng(best.centroidLat, best.centroidLng), 14f
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = fullCameraState,
            uiSettings = MapUiSettings(zoomControlsEnabled = true)
        ) { MapOverlays(clusters = clusters, previewMode = false) }

        LargeFloatingActionButton(onClick = onBackToPreview,
            modifier = Modifier.padding(24.dp).align(Alignment.BottomStart),
            containerColor = BG_CARD, contentColor = TEXT_PRIMARY) {
            Icon(Icons.Default.Close, contentDescription = "Exit Map")
        }

        if (clusters.isNotEmpty()) {
            val home   = clusters.find { it.inferredLabel == "HOME" }
            val work   = clusters.find { it.inferredLabel == "WORK" }
            val ghosts = clusters.count { it.isGhost }
            val cohab  = home?.cohabitationCount ?: 0
            Surface(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                shape = RoundedCornerShape(16.dp), color = BG_CARD.copy(alpha = 0.92f)) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    home?.let { Text("🏠 ${it.confidenceLabel} (${it.confidenceScore}%)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD700)) }
                    work?.let { Text("🏢 ${it.confidenceLabel} (${it.confidenceScore}%)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF60A5FA)) }
                    if (cohab > 0)  Text("$cohab frequent contact(s)", fontSize = 12.sp, color = Color(0xFF818CF8))
                    if (ghosts > 0) Text("$ghosts place(s) from memory", fontSize = 12.sp, color = Color(0xFFFB923C))
                }
            }
        }
    }
}

@Composable
fun ClusterDetailSheet(cluster: LocationCluster, onDismiss: () -> Unit) {
    val dateFmt = SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { onDismiss() })
        Surface(modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = BG_CARD, border = BorderStroke(1.dp, Color.White.copy(0.1f))) {
            Column(modifier = Modifier.padding(24.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(buildClusterTitle(cluster), fontWeight = FontWeight.Bold, color = TEXT_PRIMARY, fontSize = 17.sp)
                        if (cluster.isGhost) Text("Remembered · no recent GPS fixes", fontSize = 12.sp, color = Color(0xFFFB923C))
                        else if (cluster.inferredLabel == null && !cluster.isMostRecent) {
                            val d = cluster.distinctDays
                            Text("Visited $d distinct day(s) this month", fontSize = 12.sp, color = TEXT_SECONDARY)
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = TEXT_SECONDARY) }
                }

                if (cluster.confidenceScore > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress   = { cluster.confidenceScore / 100f },
                            modifier   = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color      = when { cluster.confidenceScore >= 65 -> Color(0xFF4ADE80); cluster.confidenceScore >= 40 -> Color(0xFFFFD700); else -> Color(0xFFFB923C) },
                            trackColor = Color.White.copy(0.1f)
                        )
                        Text("${cluster.confidenceScore}% confidence", fontSize = 12.sp, color = TEXT_SECONDARY)
                    }
                }

                HorizontalDivider(color = Color.White.copy(0.08f))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cluster.visitCount > 0)       StatPill("${cluster.visitCount} visits", Color(0xFF22D3EE))
                    if (cluster.avgVisitMinutes > 0)   StatPill("avg ${cluster.avgVisitMinutes}min", Color(0xFF4ADE80))
                    when {
                        cluster.mobileDeviceCount >= BT_TIER_HIGH   -> StatPill("${cluster.mobileDeviceCount} devices · high", Color(0xFFEF4444))
                        cluster.mobileDeviceCount >= BT_TIER_MEDIUM -> StatPill("${cluster.mobileDeviceCount} devices · moderate", Color(0xFF7C3AED))
                        cluster.mobileDeviceCount > 0               -> StatPill("${cluster.mobileDeviceCount} device(s)", Color(0xFF818CF8))
                    }
                    if (cluster.cohabitationCount > 0) StatPill("${cluster.cohabitationCount} cohabiting", Color(0xFFFFD700))
                }

                if (cluster.filteredInfraCount > 0)
                    Text("${cluster.filteredInfraCount} infrastructure event(s) filtered (UUID · name · RSSI)", fontSize = 11.sp, color = TEXT_SECONDARY)

                if (cluster.recentVisits.isNotEmpty()) {
                    Text("Recent Visits", fontWeight = FontWeight.SemiBold, color = TEXT_PRIMARY, fontSize = 14.sp)
                    cluster.recentVisits.forEach { visit ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(dateFmt.format(java.util.Date(visit.startTs)), fontSize = 12.sp, color = TEXT_SECONDARY,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (visit.durationMinutes > 0) "${visit.durationMinutes}min" else "<1min", fontSize = 12.sp, color = TEXT_PRIMARY, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (cluster.isGhost) {
                    Text("Last visited ${TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - cluster.lastSeen)} day(s) ago · expires after $PLACE_STALE_DAYS days without a visit", fontSize = 11.sp, color = TEXT_SECONDARY)
                } else {
                    cluster.bestAccuracyM?.let { Text("GPS accuracy ±${it.toInt()}m · ${cluster.totalFixCount} fixes", fontSize = 11.sp, color = TEXT_SECONDARY) }
                }
            }
        }
    }
}

@Composable
private fun StatPill(text: String, color: Color) {
    Surface(color = color.copy(0.15f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color.copy(0.3f))) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SummaryChip(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.18f), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}
@Composable
fun MapLegend() {

    val theme = LocalAppTheme.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),

        color = theme.cardBackground,
        border = BorderStroke(1.dp, theme.textPrimary.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Map Legend",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary
            )
            Text(
                text = "Solid = recent · Faded = remembered",
                fontSize = 10.sp,
                color = theme.textSecondary
            )

            Spacer(Modifier.height(2.dp))


            LegendRow(Color(0xFFFFD700), "Home", theme.textSecondary)
            LegendRow(Color(0xFF60A5FA), "Workplace", theme.textSecondary)
            LegendRow(Color(0xFF22D3EE), "Recurring venue", theme.textSecondary)
            LegendRow(Color(0xFF4ADE80), "Most recent location", theme.textSecondary)

            Spacer(Modifier.height(2.dp))

            LegendRow(Color(0xFFEF4444), "High exposure (crowded)", theme.textSecondary)
        }
    }
}

@Composable
fun LegendRow(color: Color, label: String, textColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = textColor
        )
    }
}