# Folio

An Android home screen. Idle is a photograph. Apps are four icons, a Search pill, and a pull-up grid. The ringer is the theme: **Sound** is full colour, **Vibrate** is half the saturation, **Silent** is black and white.

No ads, no feed, no account. Sideload only.

**Primary device:** Galaxy S23 on Android 14+ (6.1", 1080×2340, 19.5:9, 120 Hz, centre punch-hole).

Requires Android 12 (API 31)+; built and tested for Android 14+ on the S23.

## Install

Sideload from [GitHub Releases](https://github.com/mattstyles333/folio-launcher/releases). The APK is `folio-X.Y.Z.apk`, signed with the Folio release key. Each Release also carries `hermes-live-A.B.C.apk`, the optional Hermes Live companion, signed with the same key: install it and sign in there to let Folio ask your own Hermes.

Certificate SHA-256:

```
C3:BD:A2:A1:0C:A2:66:0B:D2:36:42:06:39:94:3A:A7:7F:02:16:C4:71:84:EE:E7:A5:67:44:13:95:31:74:B4
```

[Obtainium](https://github.com/ImranR98/Obtainium) can watch that same repo and apply later updates; have it filter APKs by the regular expression `^folio-.*\.apk$` so it never picks Hermes Live's. Builds **v1.2.7 and earlier** used the debug key — uninstall those before installing 1.2.8+, then press Home and pick **Folio**.

From source:

```bash
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew :app:testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`assembleRelease` needs `keystore.properties` (copy `keystore.properties.example`). Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Use

| Gesture | What |
|---|---|
| Tap Search | Spotlight. With an OpenRouter key (Settings → Jev), Jev reads what you type and offers one route: the app you meant ("trains" → Trainline), the web, a one-line answer, your Ask app, or Spotify. Enter follows it. |
| Triple-tap the print, or long-press Search | Ask: Hermes when Hermes Live is installed and signed in (it answers over the print), otherwise Grok, ChatGPT, Gemini or Claude. Settings → Ask picks which. |
| Swipe left on the print | Hermes chosen: a Hermes Live voice call; otherwise open the chosen AI app |
| Swipe right on the print | Google Search |
| Drag the four icons up | App sheet — follows your finger. A short swipe peeks the most-used apps in thumb reach; pull further to scroll. Most used first. Other launchers stay out of the grid; search still finds them. Swipe an icon right to hide it. Long-press an icon in the sheet or search for app info. |
| Long-press one of the four icons | Pick an app to pin in that slot. Long-press and drag to reorder. Settings → Reset pins: the four fill from use again. |
| Swipe down on the print | Notification shade |
| Double-tap the print | Next Bing photograph + next quote |
| Long-press the print or the quote | Cycle Sound → Vibrate → Silent (sets the real ringer; media stays on). Next grade develops from your finger. |
| Long-press the clock | Settings — pick your own photo here too. With Show clock off, long-press where it sat. |
| Plug in | Hairline oval around the clock fills with charge. Green on Sound. Closed at 100%. Same oval when the battery is at 15% or below, even unplugged. |
| Music | Previous / play / next above the four icons, always there. Tap play to pause, or to start Spotify if nothing is playing; long-press play opens the player (Spotify if there is none). |
| Settings → Hidden apps | Unhide. |

Quotes sit above the clock — one short line a day, from an on-device bank. A new Bing print turns the page; with an OpenRouter key, Jev picks the line that suits the photograph (only its Bing title and caption are sent). The print itself refreshes on the first unlock after midnight; Settings → Previous print goes back one.

## Permissions

First launch: **Set as Home**. Today's Bing print loads itself. Then one **Allow** walks Do Not Disturb, usage access, and notification access for Spotify (find Folio, switch on, Back). Skip any; they stay in Settings.

| Access | When |
|---|---|
| Internet | Bing prints. With an OpenRouter key: searches that aren't an app's name (or that have a space), plus your app labels and package names, go to Jev; one-line answers come from DeepSeek V4.1 Flash. No key, no calls. With Hermes chosen, what you ask Hermes (in its overlay, or a search Jev routes there) goes to your own Hermes server, at the address Hermes Live hands over, only while that overlay is open; a plain-http Tailscale (`*.ts.net`) address is used only while Tailscale is on (Folio reads the phone's network state to check). Release builds also send crash reports, errors and start-up time (event names, codes, stack traces; tagged with app version, phone model, Android version and a random install ID; never what you type or say, answers or keys) to the maintainer's diagnostics endpoint, set at build time from the CI variable `FOLIO_DIAGNOSTICS_URL`; Settings → Diagnostics turns it off, and builds you make yourself send nothing. |
| Photo picker | Settings → Choose photo. No broad storage. |
| Do Not Disturb | Optional. Asked once at onboarding. Silent still *looks* silent if you deny, until the ringer changes. |
| Usage access | Optional. Asked once at onboarding, or Settings → Better ranking. Last 30 days of opens. |
| Notification access | Optional. Settings → Now playing. Needed for Spotify controls on the print. Folio's switch greyed out? Android 13+ restricts it for APKs installed from a browser or file manager: Settings → Apps → Folio → ⋮ → Allow restricted settings first. |

## Build

JDK 17, Android SDK 37 (compile) / 35 (target).

Hermes support builds the `:hermes` library from the [hermes-live](https://github.com/mattstyles333/hermes-live) submodule, so clone with `git clone --recurse-submodules` (or run `git submodule update --init`).

```bash
env -u XAI_API_KEY -u OPENROUTER_API_KEY ./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug assembleRelease
```

Name `:app` for tests: a bare `testDebugUnitTest` also runs the `:hermes` suite, whose live evals call Grok Voice and OpenRouter, billed, whenever `XAI_API_KEY` or `OPENROUTER_API_KEY` is exported.

See `AGENTS.md` if you are a coding agent working in this tree.

## License

MIT. See `LICENSE`.
