package com.velometrics.app.data.local

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.velometrics.app.di.DatabaseModule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

private const val TEST_DB = "migration_11_12_test.db"
private const val TEST_DB_14 = "migration_13_14_test.db"

/**
 * Speed histogram buckets narrowed from 8 to 5 in #148; MIGRATION_11_12 must merge each existing
 * row's old-labeled counts into the new labels rather than dropping data (`fallbackToDestructiveMigration`
 * would otherwise silently wipe every ride on upgrade).
 *
 * [Config.application] swaps out the real (Hilt) `VelometricsApplication` for a plain [Application]
 * (same rationale as [CyclingAssetDatabaseFixtureTest]) so this test can drive Room directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VelometricsMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VelometricsDatabase::class.java
    )

    @Test
    fun `migrate 11 to 12 merges old speed histogram buckets into the new 5-bucket scheme`() {
        val oldHistogram = JSONObject(
            mapOf(
                "0-5 km/h" to 2,
                "5-10 km/h" to 3,
                "10-20 km/h" to 10,
                "20-25 km/h" to 4,
                "25-30 km/h" to 6,
                "30-35 km/h" to 5,
                "35-40 km/h" to 1,
                ">40 km/h" to 7
            )
        ).toString()

        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                """
                INSERT INTO cycling_sessions
                    (id, fileName, fileSha1, sessionStart, sessionEnd, totalDurationSec,
                     pauseDurationSec, netDurationSec, distanceKm, speedHistogram, intervalCount,
                     intervalTotalTimeSec, gpsQualityPercent, hasPower, sprintCount)
                VALUES
                    (1, 'ride.fit', 'sha1', 0, 3600, 3600, 0, 3600, 30.0, '$oldHistogram', 0, 0,
                     100.0, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, DatabaseModule.MIGRATION_11_12)

        val cursor = migrated.query("SELECT speedHistogram FROM cycling_sessions WHERE id = 1")
        cursor.use {
            assertEquals(true, it.moveToFirst())
            val newHistogram = JSONObject(it.getString(0))

            // Every new bucket is an exact merge of contiguous old buckets — no data lost or invented.
            assertEquals(5, newHistogram.getInt("0-10 km/h"))   // 2 + 3
            assertEquals(10, newHistogram.getInt("10-20 km/h")) // unchanged
            assertEquals(10, newHistogram.getInt("20-30 km/h")) // 4 + 6
            assertEquals(6, newHistogram.getInt("30-40 km/h"))  // 5 + 1
            assertEquals(7, newHistogram.getInt(">40 km/h"))    // unchanged
            assertEquals(5, newHistogram.length())
        }
    }

    @Test
    fun `migrate 13 to 14 adds a nullable tag column defaulting to null`() {
        helper.createDatabase(TEST_DB_14, 13).apply {
            execSQL(
                """
                INSERT INTO cycling_sessions
                    (id, fileName, fileSha1, sessionStart, sessionEnd, totalDurationSec,
                     pauseDurationSec, netDurationSec, distanceKm, speedHistogram, intervalCount,
                     intervalTotalTimeSec, gpsQualityPercent, hasPower, sprintCount)
                VALUES
                    (1, 'ride.fit', 'sha1', 0, 3600, 3600, 0, 3600, 30.0, '{}', 0, 0, 100.0, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_14, 14, true, DatabaseModule.MIGRATION_13_14)

        val cursor = migrated.query("SELECT tag FROM cycling_sessions WHERE id = 1")
        cursor.use {
            assertEquals(true, it.moveToFirst())
            assertEquals(true, it.isNull(0))
        }
    }
}
