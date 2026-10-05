package com.example.androidxpose.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

object PermissionManager {

    val foregroundLocation = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val bluetooth = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    val backgroundLocation = arrayOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAll(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    fun hasForegroundLocation(context: Context): Boolean =
        hasAll(context, foregroundLocation)

    fun hasBluetooth(context: Context): Boolean =
        hasAll(context, bluetooth)

    fun hasBackgroundLocation(context: Context): Boolean =
        hasAll(context, backgroundLocation)

    fun hasUsageStats(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}
