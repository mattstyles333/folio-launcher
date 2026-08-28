# Pulse

An Android home screen. Idle is a photograph. Apps are four icons, a Search pill, and a pull-up grid. The ringer is the theme: **Sound** is full colour, **Vibrate** is half the saturation, **Silent** is black and white.

No ads, no feed, no account. Sideload only.

**Primary device:** Galaxy S23 (6.1", 1080×2340, 19.5:9, 120 Hz, centre punch-hole).

Requires Android 12 (API 31)+.

## Install

```bash
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release is **debug-signed** for sideload (not Play). Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Press Home and pick **Pulse**, or:

```bash
adb shell cmd role add-role-holder android.app.role.HOME com.pulse.launcher
```

## Use

| Gesture | What |
|---|---|
| Tap Search / swipe down / tap clock | Spotlight |
| Drag the four icons up | App sheet — recent / most used first, then A–Z. Scroll for everything. |
| Double-tap the print | Next Bing photograph + next quote |
| Long-press the print | Cycle Sound → Vibrate → Silent (sets the real ringer). Next grade develops from your finger. |
| Plug in | Hairline around the clock fills with charge |
| Music (optional) | Song under the date; tap to skip |

Quotes sit under the date — one short line a day, from an on-device bank. A new Bing print turns the page.

## Permissions

| Access | When |
|---|---|
| Internet | Bing prints only. Everything else is local. |
| Photo picker | Settings → Choose photo. No broad storage. |
| Do Not Disturb | Optional. Silent still *looks* silent if you deny; tap the hint to mute for real. |
| Usage access | Optional. Settings → Better ranking. |
| Notification access | Optional. Settings → Now playing. |

## Build

JDK 17, Android SDK 35.

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

See `AGENTS.md` if you are a coding agent working in this tree.

## License

MIT. See `LICENSE`.
