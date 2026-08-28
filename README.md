# Pulse

Opinionated Android launcher. Idle is a print. Apps are reached by search, a four-app rail, and a pull-up recents sheet. Ringer mode is the theme.

Requires Android 12 (API 31)+.

## Build

JDK 17 and Android SDK (platform 35, build-tools 35) are required.

```bash
cd pulse
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

| Artifact | Path |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Sideload release | `app/build/outputs/apk/release/app-release.apk` (debug-signed) |

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Set as Home

After install, press Home and pick **Pulse**, or:

1. Open Pulse (it asks on first run).
2. Android Settings → Apps → Default apps → Home app → **Pulse**.
3. From Pulse settings: **Set as default launcher**.

From a computer:

```bash
adb shell cmd role add-role-holder android.app.role.HOME com.pulse.launcher
```

## Permissions

Pulse does not need a network, an account, or Play services.

| Access | When |
|---|---|
| Photo picker | Choosing a print. System picker only; no broad storage. |
| Do Not Disturb | First time you set Silent, so the phone can actually mute. Deny and the look still changes. |
| Usage access | Optional, Settings → Better ranking. Improves recents and unpinned rail slots. |

Sound / Vibrate / Silent follow the ringer jewel and the hardware ringer switch.
