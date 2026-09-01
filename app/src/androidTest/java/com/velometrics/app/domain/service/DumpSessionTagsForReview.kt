package com.velometrics.app.domain.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.di.DatabaseModule
import com.velometrics.app.util.toDomain
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual dev tool for issue #170 (validate/tune [RideClassifier] thresholds against real ride
 * history) — NOT part of any regular test suite, and deliberately not asserting anything: it
 * opens the real on-device `velometrics_database` (same builder as production, via
 * [DatabaseModule.provideDatabase] — so no risk of a migration-version mismatch triggering
 * `fallbackToDestructiveMigration()` against real data) and hands the sessions to
 * [RideTagDumper] to write the review CSV.
 *
 * NOT CURRENTLY THE SUPPORTED PATH: `connectedDebugAndroidTest` reproducibly triggered an
 * unrelated on-device app uninstall (twice, wiping real ride data both times) during #170's
 * investigation on the rider's device — root cause undetermined, logcat's ring buffer rolled
 * past the event before it could be captured. Prefer the debug-only Settings row
 * ([com.velometrics.app.ui.screens.settings.SettingsViewModel.dumpSessionTagsForReview]), which
 * needs no androidTest install/uninstall cycle at all. Kept here in case the instrumented-test
 * path is ever safe to revisit (e.g. after finding what's auto-removing the app).
 */
@RunWith(AndroidJUnit4::class)
class DumpSessionTagsForReview {

    @Test
    fun dumpAllSessionTagsAndSignalsToCsv() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = DatabaseModule.provideDatabase(context)
        try {
            val sessions = db.cyclingSessionDao().getAllSessions().first().map { it.toDomain() }
            val ftp = UserSettingsRepository(context).ftp.first()
            val outFile = File(context.filesDir, "ride_tag_dump.csv")
            val byTag = RideTagDumper.dumpToCsv(sessions, outFile, ftp)

            // Surfaced in the instrumentation log too, in case adb pull is done from a different
            // shell than the one running the test.
            println("DumpSessionTagsForReview: wrote ${sessions.size} rows to ${outFile.absolutePath}")
            println("DumpSessionTagsForReview: tag counts = $byTag")
        } finally {
            db.close()
        }
    }
}
