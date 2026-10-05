package com.example.androidxpose

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager

private const val TAG = "XposeApplication"

class XposeApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "XposeApplication initialised")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
}
