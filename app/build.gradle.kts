import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.velometrics.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.velometrics.app"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Dropbox OAuth2 PKCE app key (public client identifier, not a secret)
        val dropboxAppKey = "91vlvyjz1nhumjl"
        manifestPlaceholders["dropboxAppKey"] = dropboxAppKey
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Exposes the Room-exported schema JSONs (ksp room.schemaLocation below) to
        // MigrationTestHelper. Robolectric's local unit tests read assets from the `debug`
        // variant's merged assets (isIncludeAndroidResources), not a "test"-scoped source set —
        // adding it here (rather than "main") keeps the schema JSONs out of release builds.
        getByName("debug").assets.srcDirs("$projectDir/schemas")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        // sharedTest: code + assets used by both JVM tests and instrumented tests (the README demo
        // ride generator, #219). JVM tests read the assets as classpath resources, device tests as
        // instrumentation-context assets — both under the same "demo/..." path.
        getByName("test") {
            java.srcDir("src/sharedTest/java")
            resources.srcDir("src/sharedTest/assets")
        }
        getByName("androidTest") {
            java.srcDir("src/sharedTest/java")
            assets.srcDir("src/sharedTest/assets")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)

    // Garmin FIT SDK
    implementation(libs.garmin.fit)

    // MapLibre
    implementation(libs.maplibre.android.sdk)

    // Play services (fused location)
    implementation(libs.play.services.location)

    // Gson
    implementation(libs.gson)

    // RTree2 for spatial indexing
    implementation(libs.rtree2)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Dropbox
    implementation(libs.dropbox.core.sdk)
    implementation(libs.dropbox.android.sdk)

    // Encrypted storage (Keystore-backed)
    implementation(libs.androidx.security.crypto)

    // Testing
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// README screenshots from the #219 demo rides (androidTest ReadmeScreenshots), on a running
// emulator: ./gradlew readmeScreenshots. Not part of CI.
abstract class ReadmeScreenshotsTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Internal abstract val adbExecutable: Property<File>
    @get:Internal abstract val appApk: RegularFileProperty
    @get:Internal abstract val testApk: RegularFileProperty
    @get:Internal abstract val applicationId: Property<String>
    @get:Internal abstract val testClass: Property<String>
    @get:Internal abstract val pullDir: DirectoryProperty
    @get:Internal abstract val screenshotsDir: DirectoryProperty

    @TaskAction
    fun capture() {
        val serial = emulatorSerial()
        val app = applicationId.get()
        logger.lifecycle("Taking README screenshots on $serial")
        adb(serial, "install", "-r", "-t", appApk.get().asFile.absolutePath)
        adb(serial, "install", "-r", "-t", testApk.get().asFile.absolutePath)
        // Emulator data is disposable; this also drops any Dropbox link so no sync worker fires mid-import.
        adb(serial, "shell", "pm", "clear", app)

        adb(serial, "shell", "settings", "put", "global", "sysui_demo_allowed", "1")
        val output: String
        try {
            // Pixel-class 1080x2400 @ 420 dpi, whatever the AVD's own profile.
            adb(serial, "shell", "wm", "size", "1080x2400")
            adb(serial, "shell", "wm", "density", "420")
            demo(serial, "enter")
            demo(serial, "clock", "-e", "hhmm", "1200")
            demo(serial, "battery", "-e", "level", "100", "-e", "plugged", "false")
            demo(serial, "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true", "-e", "mobile", "hide")
            demo(
                serial, "status", "-e", "volume", "hide", "-e", "bluetooth", "hide", "-e", "location", "hide",
                "-e", "alarm", "hide", "-e", "sync", "hide", "-e", "zen", "hide", "-e", "mute", "hide",
                "-e", "speakerphone", "hide", "-e", "eri", "hide", "-e", "tty", "hide"
            )
            demo(serial, "notifications", "-e", "visible", "false")
            // Demo mode leaves some system notifications (e.g. Safety Center's "no screen lock") visible.
            adb(serial, "shell", "cmd", "statusbar", "send-disable-flag", "notification-icons")
            output = adb(
                serial, "shell", "am", "instrument", "-w",
                "-e", "readmeScreenshots", "true", "-e", "class", testClass.get(),
                "$app.test/androidx.test.runner.AndroidJUnitRunner"
            )
        } finally {
            adb(serial, "shell", "cmd", "statusbar", "send-disable-flag", "none")
            demo(serial, "exit")
            adb(serial, "shell", "wm", "size", "reset")
            adb(serial, "shell", "wm", "density", "reset")
        }
        logger.lifecycle(output.trim())
        // am instrument exits 0 even when a test fails.
        if (output.contains("FAILURES!!!") || !output.contains("OK (")) {
            throw GradleException("The README screenshot test failed; see the output above.")
        }

        val pulled = pullDir.get().asFile
        pulled.deleteRecursively()
        pulled.mkdirs()
        adb(serial, "pull", "/sdcard/Android/data/$app/files/readme-screenshots", pulled.absolutePath)
        val pngs = File(pulled, "readme-screenshots").listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        if (pngs.isEmpty()) throw GradleException("No screenshots were pulled from $serial.")

        val target = screenshotsDir.get().asFile
        target.mkdirs()
        target.listFiles { f -> f.name.endsWith(".png") }?.forEach { it.delete() }
        pngs.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        logger.lifecycle("Saved ${pngs.map { it.name }.sorted().joinToString()} to $target")
    }

    /** The one running emulator (or ANDROID_SERIAL, which must be an emulator); real devices are never used. */
    private fun emulatorSerial(): String {
        System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }?.let { requested ->
            if (adb(requested, "shell", "getprop", "ro.kernel.qemu").trim() != "1") {
                throw GradleException("ANDROID_SERIAL=$requested is not an emulator; readmeScreenshots never runs on a physical device.")
            }
            return requested
        }
        val emulators = adb(null, "devices").lines()
            .map { it.split("\t") }
            .filter { it.size == 2 && it[1].trim() == "device" && it[0].startsWith("emulator-") }
            .map { it[0] }
        if (emulators.isEmpty()) throw GradleException("readmeScreenshots needs a running emulator; none found.")
        if (emulators.size > 1) {
            throw GradleException("Several emulators are running ($emulators); pick one with ANDROID_SERIAL.")
        }
        return emulators.single()
    }

    private fun demo(serial: String, command: String, vararg extras: String) {
        adb(serial, "shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command, *extras)
    }

    private fun adb(serial: String?, vararg args: String): String {
        val commandLine = buildList {
            add(adbExecutable.get().absolutePath)
            if (serial != null) addAll(listOf("-s", serial))
            addAll(args)
        }
        val out = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(commandLine)
            standardOutput = out
        }
        return out.toString(Charsets.UTF_8)
    }
}

tasks.register<ReadmeScreenshotsTask>("readmeScreenshots") {
    group = "documentation"
    description = "Takes the README screenshots from the demo rides on a running emulator."
    dependsOn("assembleDebug", "assembleDebugAndroidTest")
    adbExecutable.set(android.adbExecutable)
    appApk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    testApk.set(layout.buildDirectory.file("outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    applicationId.set(android.defaultConfig.applicationId)
    testClass.set("com.velometrics.app.readme.ReadmeScreenshots")
    pullDir.set(layout.buildDirectory.dir("readme-screenshots"))
    screenshotsDir.set(rootProject.layout.projectDirectory.dir("docs/screenshots"))
    outputs.upToDateWhen { false }
}
