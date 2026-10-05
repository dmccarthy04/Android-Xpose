package com.example.androidxpose.ui.theme

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.androidxpose.MainActivity
import com.example.androidxpose.MappingActivity
import com.example.androidxpose.Profile.ProfileActivity.ProfileActivity
import com.example.androidxpose.report.ReportActivity
import com.example.androidxpose.usage.UsageActivity
@Composable
fun AppBottomNav(currentScreen: String) {
    val context = LocalContext.current
    val theme   = LocalAppTheme.current

    val activeTint   = Color(0xFF4ADE80)
    val inactiveTint = if (theme.isDark) Color.White.copy(0.4f) else Color(0xFF64748B)
    val homeActive   = Color(0xFF6366F1)
    val homeInactive = if (theme.isDark) Color(0xFF334155) else Color(0xFFE2E8F0)

    Surface(
        modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        shape         = RoundedCornerShape(24.dp),
        color         = theme.navBarColor,
        tonalElevation = 8.dp,
        border        = androidx.compose.foundation.BorderStroke(1.dp, theme.borderColor)
    ) {
        Row(
            modifier              = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            val items = listOf(
                Triple(Icons.Default.LocationOn, "Mapping",  MappingActivity::class.java),
                Triple(Icons.Default.BarChart,   "Usage",    UsageActivity::class.java),
                Triple(Icons.Default.Home,        "Home",     MainActivity::class.java),
                Triple(Icons.Default.Assessment,  "Report",   ReportActivity::class.java),
                Triple(Icons.Default.Person,      "Profile",  ProfileActivity::class.java)
            )

            items.forEach { (icon, label, clazz) ->
                val navIntent = remember(clazz) {
                    Intent(context, clazz).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                }

                if (label == "Home") {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (currentScreen == "Home") homeActive else homeInactive)
                            .clickable { context.startActivity(navIntent) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = Color.White)
                    }
                } else {
                    IconButton(onClick = {
                        if (currentScreen != label) {
                            context.startActivity(navIntent)
                        }
                    }) {
                        Icon(
                            icon, null,
                            tint = if (currentScreen == label) activeTint else inactiveTint
                        )
                    }
                }
            }
        }
    }
}