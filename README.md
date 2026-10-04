# PhoneStream

[![Build](https://github.com/Jadiac5/PhoneToTv/actions/workflows/build.yml/badge.svg)](https://github.com/Jadiac5/PhoneToTv/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Jadiac5/PhoneToTv)](https://github.com/Jadiac5/PhoneToTv/releases/latest)

Mirror an Android phone's screen **and sound** to an Android TV over your local Wi-Fi, fullscreen, with as little delay as possible. One app does both jobs: when you open it you choose **Send** (phone) or **Receive** (TV).

**Download:** the APK is attached to the [latest release](https://github.com/Jadiac5/PhoneToTv/releases/latest) (Android 8.0+, phone and Android TV).

> **Status: tested on a PC only for 1.1.0.** The code compiles, 108 automated JVM tests pass (protocol, slicing writer, bitrate tuner, updater, a loopback receiver and a simulated slow Wi-Fi link) and lint has 0 errors, but there is no automated test of the screen-capture, encoder, decoder or speaker parts. Those only run on real phones and TVs; see [Known limitations](#known-limitations).

## Installing

**Phone**
1. Download `PhoneStream-<version>.apk` from the releases page on the phone (or copy it over by cable / cloud drive).
2. Open it. Android asks to allow "install unknown apps" for the app you opened it from. Allow it, then install.

**Android TV**
- Easiest: install a file-manager app on the TV (e.g. "File Commander" or "X-plore"), put the APK on a USB stick or reach it over the network, open it from there. Allow "install unknown apps" for that file manager in the TV's settings.
- Or with `adb` from a PC (enable Developer options, then USB/network debugging on the TV):
  ```
  adb connect <tv-ip>:5555
  adb install PhoneStream-<version>.apk
  ```

**Later versions** install from inside the app, see [Updates](#updates). The one exception is 1.0.0 → 1.1.0: the protocol changed, and 1.0.0 has no updater, so install 1.1.0 by hand once on each device. Android keeps your settings when you install over an older version.

## Using it

1. **TV:** open PhoneStream, choose **Receive**. The TV shows its name and "Waiting for a sender...".
2. **Phone:** open PhoneStream, choose **Send**. TVs in receive mode on the same Wi-Fi appear by name (a TV that is already connected to a phone doesn't). Tap one.
3. Pick a resolution (and, on a landscape phone, a picture shape), start streaming, and accept Android's screen-capture prompt. On first use it also asks for the microphone and notification permissions. The microphone permission is what lets Android hand the app the phone's *internal* audio; nothing is recorded from the mic.
4. Press **Home**. A notification shows PhoneStream is running; screen and sound keep streaming.
5. To stop: open the app and press **Stop streaming** on the phone, or choose Disconnect on the TV (press Back once to show the overlay, again to disconnect).

If your TV isn't listed (some routers block device discovery), use **Enter IP address manually** on the phone. The TV shows its IP address on the waiting screen.

### Resolution

| Preset | What is sent |
|---|---|
| Native | The phone's real screen resolution (capped at what both devices' codecs support, e.g. 4K) |
| 1080p | Scaled so the screen's **short side** is 1080 px, aspect ratio kept |
| 720p | same, short side 720 px |
| 480p | same, short side 480 px |

Sizes are written long side first, e.g. `2400×1080`. If the picture stutters, the app lowers frame rate and bitrate by itself; stepping down one preset helps on a weak network.

### Picture shape (16:9)

For a **landscape** phone that is not exactly 16:9 (most are 19.5:9 or 20:9), three modes:

| Mode | Result |
|---|---|
| Original | The phone's real shape; the TV adds black bars at the sides. No change to the picture. |
| Fill 16:9 | Trims the left and right edges so the picture fills the TV. Nothing is distorted; on a 20:9 phone about a fifth of the width is lost (a tenth on each side). Best for video that is already letterboxed (YouTube etc.). |
| Stretch 16:9 | Squeezes the whole screen into 16:9. Nothing is lost but everything is about 20% narrower, which you will notice on round shapes and faces. |

A **portrait** phone always keeps its shape (stretching 9:20 to 16:9 would be unwatchable); the TV shows it with bars.

### Sound

While streaming, the phone's own speaker is muted and only the TV plays the sound. The app turns the phone's media volume to zero and then **checks** that the stream still carries sound. If a phone's Android version lets the capture follow the volume, the stream would go silent, so the app notices, restores the volume and keeps the phone audible instead (the Send screen says so). The result is remembered; **Settings → Phone speaker while streaming → Test again** repeats the check. The original volume is always restored when streaming ends.

### Updates

Open **Settings** with the gear icon at the top right of the start screen. The button there checks this repository's releases: **Check for updates** becomes **Update to vX** when a newer version exists. A small pill also appears on the start screen when one is available (or after the automatic check, which runs when the app opens, at most every 6 hours, and can be switched off).

Pressing the update button downloads the APK from this repository's releases, checks its size and checksum, and hands it to Android's installer, which asks you to confirm. The first time, Android asks to allow "install unknown apps" for PhoneStream. Updates are refused while a stream is running. Updating the TV works the same way (use the remote's D-pad to reach the gear icon).

## About "uncompressed"

True uncompressed video is not possible here: 1080p at 60 fps is about 4 Gbit/s, 4K about 16 Gbit/s, far beyond any Wi-Fi. So PhoneStream uses the phone's **hardware video encoder (H.264, H.265 as fallback) at a high bitrate**, chosen from the resolution and lowered automatically when the network can't keep up. **Audio** is genuinely uncompressed (16-bit PCM, 48 kHz stereo, ~1.5 Mbit/s).

How it keeps the delay low:
- Video frames are cut into 16 KB slices, so sound and control messages can slip in between instead of waiting behind a large key frame. Sound goes first.
- When the network falls behind, video that has waited too long is dropped and the stream resumes at the next key frame, so you see brief glitches instead of a growing delay.
- Bitrate follows the measured delay and drops; frame rate follows how well the TV's decoder copes (the TV reports once a second).
- The TV shows frames as soon as they are decoded; sound has a small cushion (about 80 ms) that grows if the Wi-Fi hiccups and shrinks again when it is calm.

## Known limitations

- **Real-device behaviour is unproven** for the capture pipeline (screen → GPU relay → encoder), the TV decoder and the phone-mute check. Every risky step has a fallback (the GPU relay falls back to feeding the encoder directly, which turns off the 16:9 modes and the frame-rate limiter), but please report anything odd.
- **Apps can block capture:** banking apps, DRM video (Netflix etc.) show black; some apps forbid internal audio capture, so their sound won't reach the TV.
- **Android 14+** shows its screen-share consent dialog every time you start.
- **No audio/video sync offset:** they travel separately, so lip-sync may be a few tens of ms off.
- **No PIN or pairing:** anyone on the same network can send to a TV that is waiting in receive mode. Only use it on a network you trust.
- **Public repository, public updates:** the updater trusts what the `Jadiac5/PhoneToTv` releases contain. Android only installs an update that is signed with the same key as the installed app, which protects against someone else's APK.
- Same Wi-Fi/LAN required (port 47800 TCP, plus mDNS and UDP broadcast for discovery). Wi-Fi 5 or better recommended; a TV on Ethernet is best.

## If something doesn't work

| Symptom | Try |
|---|---|
| TV not in the list | Both on the same Wi-Fi? Guest networks/"client isolation" block discovery. Use manual IP entry. |
| "Update the app on the older device" | One side is still 1.0.0. Install the current release on both. |
| Connects, black picture | The app you're showing blocks capture (DRM / secure window), or the codec failed: pick a lower preset. |
| Picture OK, no sound | Android version < 10, the app blocks audio capture, or the microphone permission was denied. |
| Phone keeps playing sound | Your phone's Android lets the capture follow the volume; the app detected that and left the phone audible. Use headphones on the phone. |
| Stops when the phone is locked or after a while | Disable battery optimization for PhoneStream; some phones kill background apps aggressively (see dontkillmyapp.com). |
| Stutter / freezes | Move closer to the router; put the TV on Ethernet; try a lower preset. |
| Update button says it can't reach GitHub | The device needs internet access for this one feature; streaming itself works offline. |

## Building from source

Requirements: JDK 17+ (21 used), Android SDK (platform 35, build-tools 35.0.0). `local.properties` must point `sdk.dir` at the SDK (Android Studio creates it).

```
./gradlew testDebugUnitTest     # unit tests
./gradlew assembleDebug         # debug APK (no signing setup needed)
./gradlew assembleRelease       # signed APK -> app/build/outputs/apk/release/
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and the keystore from the project root. Neither is in the repository. Without them `assembleRelease` produces an unsigned APK.

> **Keep the release keystore safe and private.** Android installs an update over an existing app only if it is signed with the same key. If the key is lost, everyone has to uninstall and reinstall.

### Releasing a new version

1. Raise `appVersion` and `appVersionCode` in `app/build.gradle.kts` and add a section for the version to `CHANGELOG.md` (the app shows it as "What's new").
2. Commit, then tag and push: `git tag v1.2.0 && git push origin main v1.2.0`.
3. The [Release workflow](.github/workflows/release.yml) tests, builds and signs the APK and publishes `PhoneStream-1.2.0.apk` as a GitHub release. It needs the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` and fails with a clear message when one is missing.

Without the secrets you can publish from the machine that has the keystore: `./gradlew assembleRelease`, then `gh release create v1.2.0 app/build/outputs/apk/release/PhoneStream-1.2.0-release.apk --notes-file notes.md` (name the file `PhoneStream-1.2.0.apk` first if you like tidy names; the app takes any `.apk` asset).

The [Build workflow](.github/workflows/build.yml) runs tests, lint and a debug build on every push and pull request.

## Project layout

```
app/src/main/java/com/phonestream/app/
  MainActivity.kt        Send / Receive chooser (D-pad friendly for TV), update pill
  SettingsActivity.kt    update button, auto-check switch, phone-speaker check
  core/                  wire protocol (Proto, Msg, FrameAssembler), Planner (sizes, 16:9),
                         Tuner (adaptive bitrate / fps), MuteGuard, AudioBuffering
  net/                   device name, discovery (NSD + UDP broadcast + manual IP)
  media/Codecs.kt        encoder/decoder capability checks
  send/                  SendActivity, StreamService (foreground service), ScreenStreamer + GlRelay,
                         AudioStreamer, SenderSession, PacketWriter (sliced, prioritised queues), PhoneMute
  receive/               ReceiveActivity, ReceiverServer, VideoPlayer, AudioPlayer
  update/                GitHub release check, download, install
  ui/Ui.kt               small view helpers and layout rules (wide / short screens)
app/src/test/            JVM tests for all of the above that doesn't need Android
.github/workflows/       build.yml (tests on every push), release.yml (tag -> signed release)
```
