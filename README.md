# Folio

An Android home screen. Idle is a photograph. Apps are four icons, a Search pill, and a pull-up grid. The ringer is the theme: **Sound** is full colour, **Vibrate** is half the saturation, **Silent** is black and white.

No ads, no feed, no account. Sideload only.

**Primary device:** Galaxy S23 (6.1", 1080×2340, 19.5:9, 120 Hz, centre punch-hole).

Requires Android 12 (API 31)+.

## Install

Sideload from [GitHub Releases](https://github.com/mattstyles333/folio-launcher/releases). The APK is `folio-X.Y.Z.apk`, signed with the Folio release key.

Certificate SHA-256:

```
C3:BD:A2:A1:0C:A2:66:0B:D2:36:42:06:39:94:3A:A7:7F:02:16:C4:71:84:EE:E7:A5:67:44:13:95:31:74:B4
```

[Obtainium](https://github.com/ImranR98/Obtainium) can watch that same repo and apply later updates. Builds **v1.2.7 and earlier** used the debug key — uninstall those before installing 1.2.8+, then press Home and pick **Folio**.

From source:

```bash
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`assembleRelease` needs `keystore.properties` (copy `keystore.properties.example`). Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Use

| Gesture | What |
|---|---|
| Tap Search | Spotlight |
| Drag the four icons up | App sheet — follows your finger, then scrolls. Recent / most used first, then A–Z. Other launchers stay out of the grid; search still finds them. Swipe an icon right to hide it. |
| Swipe down on the print | Notification shade |
| Double-tap the print | Next Bing photograph + next quote |
| Long-press the print | Cycle Sound → Vibrate → Silent (sets the real ringer). Next grade develops from your finger. |
| Long-press the clock | Settings — pick your own photo here too |
| Plug in | Hairline oval around the clock fills with charge. Closed at 100%. |
| Music | Previous / play / next above the four icons, always there. Tap play to pause, or to start Spotify if nothing is playing; long-press play opens Spotify. |
| Settings → Hidden apps | Unhide. |

Quotes sit under the date — one short line a day, from an on-device bank. A new Bing print turns the page.

## Permissions

First launch: **Set as Home**. Today's Bing print loads itself. Then one **Allow** walks Do Not Disturb, usage access, and notification access for Spotify (find Folio, switch on, Back). Skip any; they stay in Settings.

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
