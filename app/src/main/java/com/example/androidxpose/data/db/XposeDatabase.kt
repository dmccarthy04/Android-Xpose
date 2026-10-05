package com.example.androidxpose.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `inferred_places` (
                `id`                   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `label`                TEXT    NOT NULL,
                `centroidLat`          REAL    NOT NULL,
                `centroidLng`          REAL    NOT NULL,
                `radiusM`              REAL    NOT NULL,
                `confidenceScore`      INTEGER NOT NULL,
                `distinctDays`         INTEGER NOT NULL,
                `distinctNightDays`    INTEGER NOT NULL,
                `distinctWeekdayDays`  INTEGER NOT NULL,
                `totalStationaryFixes` INTEGER NOT NULL,
                `firstInferredMs`      INTEGER NOT NULL,
                `lastConfirmedMs`      INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `inferred_places` ADD COLUMN `cohabitationCount` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

@Database(
    entities = [
        PermissionAudit::class,
        LocationEvent::class,
        BluetoothEvent::class,
        AppUsageEvent::class,
        DeviceStateEvent::class,
        NetworkEvent::class,
        InferredPlace::class
    ],
    version = 5,
    exportSchema = false
)
abstract class XposeDatabase : RoomDatabase() {

    abstract fun permissionAuditDao(): PermissionAuditDao
    abstract fun locationEventDao(): LocationEventDao
    abstract fun bluetoothEventDao(): BluetoothEventDao
    abstract fun appUsageEventDao(): AppUsageEventDao
    abstract fun deviceStateEventDao(): DeviceStateEventDao
    abstract fun networkEventDao(): NetworkEventDao
    abstract fun inferredPlaceDao(): InferredPlaceDao

    companion object {
        @Volatile
        private var INSTANCE: XposeDatabase? = null

        fun getInstance(context: Context): XposeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    XposeDatabase::class.java,
                    "xpose_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(true)
                    .createFromAsset("xpose_database.db")
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}