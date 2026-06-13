plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.spotifybot.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.spotifybot.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

// ── ADB helpers — grant on EVERY connected device (USB + wireless) ─────────────
fun adbDevices(): List<String> {
    val proc = Runtime.getRuntime().exec(arrayOf("adb", "devices"))
    val lines = proc.inputStream.bufferedReader().readLines()
    proc.waitFor()
    return lines.drop(1)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 2 && parts[1] == "device") parts[0] else null
        }
}

fun adbShell(device: String?, vararg args: String) {
    val cmd = if (device != null) {
        arrayOf("adb", "-s", device, "shell") + args
    } else {
        arrayOf("adb", "shell") + args
    }
    val proc = Runtime.getRuntime().exec(cmd)
    proc.waitFor()
}

fun grantDebugPermissions(device: String?) {
    val pkg = "com.spotifybot.app"
    val label = device ?: "default"

    // NOTE: WRITE_SECURE_SETTINGS is intentionally NOT granted.
    // On Samsung One UI, an app that holds it will programmatically rewrite
    // enabled_accessibility_services to "enable" itself — but a programmatic enable
    // has no user consent and NEVER binds, so it just re-creates the enabled-but-unbound
    // "zombie" and fights every clean toggle. The accessibility service must be enabled
    // by a human toggle in Settings (one time). Keeping this permission off guarantees
    // the app can never re-zombie itself. (See SetupHelper / AccessibilityRebind — both
    // already refuse to write Settings.Secure for the same reason.)

    adbShell(device, "pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")
    println("✓ [$label] POST_NOTIFICATIONS granted")

    // Sideloaded APKs on Android 13+ cannot bind accessibility until this is allow.
    adbShell(device, "appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow")
    println("✓ [$label] ACCESS_RESTRICTED_SETTINGS allowed (required for accessibility bind)")

    adbShell(device, "dumpsys", "deviceidle", "whitelist", "+$pkg")
    adbShell(device, "am", "set-standby-bucket", pkg, "active")
    println("✓ [$label] Battery whitelist + ACTIVE standby bucket")

    // Samsung "never killed" levers — beyond the battery-optimization dialog.
    // RUN_ANY_IN_BACKGROUND is the op One UI uses to "put app to sleep"; allowing it
    // stops Samsung's Device Care from freezing the process and dropping the binding.
    adbShell(device, "cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")
    adbShell(device, "cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
    // Keep the FGS alive without time limits (Android 12+ background FGS launch op).
    adbShell(device, "cmd", "appops", "set", pkg, "START_FOREGROUND", "allow")
    println("✓ [$label] Background run + foreground-start appops allowed (Samsung anti-sleep)")

    // Samsung/Android 16: adb settings put shows ON but never binds — UI toggle required once.
    // Clear any zombie state from prior installs, then open accessibility settings.
    adbShell(device, "settings", "put", "secure", "accessibility_enabled", "0")
    // Use `delete`, not `put ... ""` — an empty-string value is rejected with
    // "Bad arguments" on One UI / Android 16, leaving the stale zombie entry in place.
    adbShell(device, "settings", "delete", "secure", "enabled_accessibility_services")
    adbShell(device, "am", "start", "-n", "$pkg/.MainActivity")
    adbShell(device, "am", "start", "-a", "android.settings.ACCESSIBILITY_SETTINGS")
    println("✓ [$label] Opened accessibility settings — toggle SpotifyBot ON once (binds permanently)")
}

fun grantAllConnectedDevices() {
    val devices = adbDevices()
    if (devices.isEmpty()) {
        println("⚠ No adb devices — granting on default target only")
        grantDebugPermissions(null)
    } else {
        devices.forEach { grantDebugPermissions(it) }
    }
}

tasks.register("grantDevicePermissions") {
    group = "install"
    description = "Grant WRITE_SECURE_SETTINGS + enable accessibility on all adb devices"
    doLast { grantAllConnectedDevices() }
}

// Post-install hooks: run after every debug install automatically.
tasks.whenTaskAdded {
    if (name == "installDebug") {
        doLast { grantAllConnectedDevices() }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)

    // OkHttp — WebSocket client for persistent backend connection
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson — JSON serialisation/deserialisation for command payloads
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
