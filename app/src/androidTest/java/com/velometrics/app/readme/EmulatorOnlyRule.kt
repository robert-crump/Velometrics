package com.velometrics.app.readme

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Guards tests that overwrite the app's data. Skips unless the instrumentation argument
 * `readmeScreenshots=true` is set, so a plain connectedAndroidTest never runs them, and fails on
 * anything that isn't an emulator, before any other rule or setup touches the device. Put it
 * outermost in the rule chain.
 */
class EmulatorOnlyRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assumeTrue(
                "Only runs with -e $ARGUMENT true (./gradlew readmeScreenshots)",
                InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true"
            )
            if (!isEmulator()) {
                throw AssertionError(
                    "README screenshots replace the app's rides and settings, so they only run on an " +
                        "emulator. This device (${Build.MANUFACTURER} ${Build.MODEL}) was left untouched."
                )
            }
            base.evaluate()
        }
    }

    companion object {
        const val ARGUMENT = "readmeScreenshots"

        fun isEmulator(): Boolean =
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.PRODUCT.contains("sdk")
    }
}
