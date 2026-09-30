package com.velometrics.app.readme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException

/**
 * Saves whole-screen PNGs, status bar included, to the app's external files dir under
 * `readme-screenshots/`, where `./gradlew readmeScreenshots` pulls them from.
 */
class ReadmeScreenshotCapture private constructor(private val directory: File) {

    /** Waits for Compose and the main thread to be idle, then saves the screen as `<name>.png`. */
    fun capture(compose: ComposeTestRule, name: String) {
        compose.waitForIdle()
        // Lets ripples, fades and map/list placeholders finish after the UI reports idle.
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val screen: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw IOException("Screenshot of $name failed")
        val file = File(directory, "$name.png")
        try {
            file.outputStream().use { out ->
                if (!screen.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Could not write $file")
            }
        } finally {
            screen.recycle()
        }
    }

    companion object {
        const val DIRECTORY = "readme-screenshots"
        private const val SETTLE_MS = 1_000L

        /** An empty screenshot folder: whatever an earlier run left there is deleted. */
        fun cleared(context: Context): ReadmeScreenshotCapture {
            val directory = File(context.getExternalFilesDir(null), DIRECTORY)
            directory.listFiles()?.forEach { if (!it.delete()) throw IOException("Could not delete $it") }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
            return ReadmeScreenshotCapture(directory)
        }
    }
}
