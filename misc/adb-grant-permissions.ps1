# Run after manual `adb install` (when not using Gradle installDebug hook).
# Grants every permission SpotifyBot needs so you don't tap through setup dialogs.

$pkg = "com.spotifybot.app"
$acc = "$pkg/$pkg.SpotifyAccessibilityService"

Write-Host "Granting permissions for $pkg ..."

adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist "+$pkg"
adb shell am set-standby-bucket $pkg active
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services $acc
adb shell am start -n "$pkg/.MainActivity"

Write-Host "Done — accessibility enabled, battery whitelisted, app launched."
