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
| Drag the four icons up | App sheet — follows your finger, then scrolls. Recent / most used first, then A–Z. |
| Double-tap the print | Next Bing photograph + next quote |
| Long-press the print | Cycle Sound → Vibrate → Silent (sets the real ringer). Next grade develops from your finger. |
| Long-press the clock | Settings — pick your own photo here too |
| Plug in | Hairline around the clock fills with charge |
| Music (optional) | A bordered plate above the four icons: album art, title, controls, progress. Tap art or title to open Spotify. |

Quotes sit under the date — one short line a day, from an on-device bank. A new Bing print turns the page.

## Permissions

First launch: **Set as Home**. Today's Bing print loads itself. Then one **Allow** walks Do Not Disturb, usage access, and notification access for Spotify (find Pulse, switch on, Back). Skip any; they stay in Settings.

| Access | When |
|---|---|
| Internet | Bing prints only. Everything else is local. |
| Photo picker | Settings → Choose photo. No broad storage. |
| Do Not Disturb | Optional. Asked once at onboarding. Silent still *looks* silent if you deny. |
| Usage access | Optional. Asked once at onboarding, or Settings → Better ranking. Last 30 days of opens. |
| Notification access | Optional. Settings → Now playing. Needed for Spotify controls on the print. |

## Build

JDK 17, Android SDK 35.

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

See `AGENTS.md` if you are a coding agent working in this tree.

## License

MIT. See `LICENSE`.
