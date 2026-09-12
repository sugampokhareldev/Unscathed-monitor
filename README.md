# Unscathed Monitor

An Android watchdog for a phone left running Roblox while you are away from it.

It watches the screen, works out which screen is actually showing, presses Play when the game
drops to its welcome screen, gets you back in after a disconnect, and pings you on Discord when
the Dark Arts Merchant appears or when something needs you.

Built for [Unscathed RNG](https://www.roblox.com/games/122951224417794), but the game-specific
parts (screen wording, announcements, place ID) live in one catalog file, so another game can be
added without touching the detection code.

## What it does

**Knows which screen is up.** Roblox not running, loading, the Roblox home screen, the game
welcome screen, in game, disconnected, or unrecognised. No single pixel or OCR line decides it:
each state accumulates weight from independent signals (which app is in front, which words are on
screen, *where* those words are, whether the disconnect dialog matched) and the strongest wins. A
frame nothing claims confidently comes back as "unknown" rather than as a guess, and a state has
to be read several times in a row before anything acts on it - so one bad frame moves nothing.

**Presses Play, once.** On a confirmed welcome screen whose Play button was actually located, it
taps and then waits to see the game load before it will consider tapping again. Repeated failures
back it off completely rather than hammering the button.

**Gets you back in.** Taps Reconnect on the disconnect dialog, or relaunches Roblox straight into
the place. With "never give up" on it keeps retrying with growing gaps for as long as it takes,
and waits rather than burning attempts while the phone is offline.

**Watches for the Dark Arts Merchant.** Fuzzy-matches "The Dark Arts merchant has appeared!"
through the noise OCR adds, needs to read it on more than one scan, then alerts once per
appearance no matter how long the banner lingers - and tags you.

**Tells you when it cannot act.** If the phone locks itself or an app draws over the game, taps
cannot reach Roblox - Android delivers them to whatever window is on top. Rather than failing
silently it names what is in the way, stops spending attempts on taps that cannot land, and
routes recovery through a relaunch instead, which is an intent rather than a touch.

**Keeps the screen awake**, because a phone that sleeps sees nothing and wakes up behind a lock
screen.

Also: freeze detection, crash detection, Wi-Fi recovery that retries until back online, battery
and temperature alerts, an event log you can export, and a small web page for changing settings
from a browser on the same Wi-Fi.

## Installing it

Grab the APK from the [releases page](https://github.com/sugampokhareldev/Unscathed-monitor/releases)
and open it on the phone. Android will ask you to allow installing from your browser or files app.

Then, in the app:

1. **Discord tab** - paste a webhook URL and your numeric Discord user ID, tap *Check connection*,
   then *Send test* to confirm the tag actually reaches you. Webhooks can only ping by ID; a
   username shows as plain text.
2. **Games tab** - check the place ID, and turn on the features you want.
3. Grant the two permissions the dashboard asks for: **screen capture** (so it can see) and
   **Accessibility** (so it can tap). Android switches Accessibility off every time the app is
   updated, so re-enable it after an update.
4. **Status tab** - turn Monitoring on.

Worth knowing: keeping the screen on for hours risks burn-in on an AMOLED panel, so turn the
brightness well down for long sessions. And if the phone has a screen lock, either leave "keep
the screen awake" on or set the lock to None - otherwise the lock screen ends up over the game
taking every tap.

## Building it

Needs JDK 17 and the Android SDK (compileSdk 35). The Kotlin 2.0 compiler cannot parse Java 21+
version strings, so if your system JDK is newer, point Gradle at a 17 with
`org.gradle.java.home` in `~/.gradle/gradle.properties`.

```bash
./gradlew testDebugUnitTest     # the detection logic, no device needed
./gradlew assembleDebug         # app/build/outputs/apk/debug/
./gradlew installDebug          # straight onto a connected phone
```

The detection core is deliberately free of Android imports - the classifier, the stabilizer, the
event matcher, the Play automator, the recovery planner and the state machine are all plain
Kotlin, so the behaviour that matters is covered by ordinary unit tests.

### Signed release builds

Create a keystore once, keep it somewhere safe, and never commit it:

```bash
keytool -genkeypair -v -keystore release.jks -alias unscathed \
    -keyalg RSA -keysize 2048 -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties` (gitignored) and fill in your
passwords, then `./gradlew assembleRelease`. Without that file the release build still compiles,
it is just unsigned - and an unsigned APK will not install.

Losing the keystore means you can never ship an update that existing installs will accept, so
back it up.

### Publishing a release

`.github/workflows/release.yml` builds and attaches an APK to a GitHub release when you push a
tag:

```bash
git tag v0.2.0
git push origin v0.2.0
```

For it to sign the build, add four repository secrets under *Settings, Secrets and variables,
Actions*: `KEYSTORE_BASE64` (the keystore base64-encoded), `STORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD`. Encode the keystore with `base64 -w0 release.jks` on Linux or
`certutil -encode release.jks out.txt` on Windows. Without those secrets the workflow still runs
and produces an unsigned APK, which is useful for testing but cannot be installed.

## How it is put together

| Area | Where |
| --- | --- |
| Screen classification and confirmation | `analyzer/ScreenState.kt` |
| Staged capture pipeline | `analyzer/FrameAnalyzer.kt` |
| Announcement matching | `analyzer/GameEventDetector.kt`, `analyzer/EventConfirmer.kt` |
| Auto Play | `automation/PlayAutomator.kt` |
| Reconnect and rejoin planning | `recovery/RecoveryPlanner.kt` |
| Watchdog state over time | `state/WatchdogStateMachine.kt` |
| The loop that drives it all | `service/MonitoringService.kt` |
| Per-game wording and events | `games/GameCatalog.kt` |
| Discord delivery | `network/` |

OCR is expensive, so it is earned rather than assumed. Every tick does a cheap greyscale diff and
a coarse brightness fingerprint of the frame; the full text pass only runs when the picture has
actually changed, a heartbeat is due, or something is still waiting to be confirmed. A screen
sitting still is not re-read at all.

Every automated action follows the same cycle: detect, verify the thing it is about to act on is
really there, act, then confirm the screen changed the way it should have.

## Limits

- **Android will not let anything tap past a window that is on top.** No app can, by design. If
  the phone is locked or something is drawing over the game, Play and Reconnect cannot be pressed.
  The app detects this and says so, but it cannot work around it.
- Capture stops while the screen is off - hence the wake lock.
- Accessibility permission is revoked by Android on every app update.
- Only Unscathed is in the catalog so far.
