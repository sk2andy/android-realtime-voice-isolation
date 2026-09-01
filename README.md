# Voice Isolation for Android

Android app that captures regular media and game audio through Shizuku, processes it with an on-device streaming ML model, and blends the original and isolated signals at an adjustable intensity.

## What works

- One tap on **START** connects to the already running Shizuku service, loads GTCRN, and configures the audio route.
- A Quick Settings tile starts or stops isolation without opening the app; long-pressing the tile opens the app.
- A persistent notification shows the active service and provides intensity and stop actions.
- The intensity control ranges from the latency-aligned original signal at 0% to fully isolated audio at 100%.
- Podcast, Film, Gaming, Maximum, Custom, and named user presets persist across app restarts and appear in the notification and Quick Settings tile.
- An auto-off slider can stop isolation after 5–60 minutes without captured audio, or the feature can be disabled.
- An opt-in, token-protected automation API supports start, stop, preset, and intensity commands from Tasker or ADB.
- Full isolation compensates the model's measured 2.3 dB voice attenuation while retaining background suppression.
- Processing stays entirely on-device; the app intentionally has no internet permission.
- A privileged AOSP `AudioPolicy` loopback route replaces the original mix without changing the global media volume.
- Stopping releases `AudioRecord`, `AudioTrack`, ONNX Runtime, and the dynamic audio policy.

## Requirements

1. Android 13 or newer.
2. [Install Shizuku](https://shizuku.rikka.app/download/).
3. Start Shizuku. Without root, this is required again after every reboot; Android 11+ can use wireless debugging.
4. Install the app and grant Shizuku access on first use.
5. Start a media app and tap **START** in Voice Isolation.
6. Optional: add **Voice Isolation** from the Quick Settings edit screen for one-tap access.

Once Shizuku is running and permission has been granted, starting the app is fully automatic. Without root, the app cannot start Shizuku by itself after a device reboot.

## Tasker and automation

Enable **Automation API** in the app and copy its per-install token. The exported receiver has no intent filter; callers must target it explicitly and include that token. Keep the token private.

Actions:

| Action | Extra |
|---|---|
| `dev.sk2andy.voiceisolation.automation.START` | `token` |
| `dev.sk2andy.voiceisolation.automation.STOP` | `token` |
| `dev.sk2andy.voiceisolation.automation.SET_PRESET` | `token`, `preset`: `podcast`, `film`, `gaming`, `maximum`, `custom`, or a saved preset name |
| `dev.sk2andy.voiceisolation.automation.SET_INTENSITY` | `token`, `intensity`: number from `0.0` to `1.0` |

Configure Tasker's **Send Intent** action with target **Broadcast Receiver**, package `dev.sk2andy.voiceisolation`, class `dev.sk2andy.voiceisolation.service.AutomationReceiver`, and one action from the table. Example ADB commands:

```bash
adb shell am broadcast \
  -n dev.sk2andy.voiceisolation/.service.AutomationReceiver \
  -a dev.sk2andy.voiceisolation.automation.START \
  --es token YOUR_TOKEN

adb shell am broadcast \
  -n dev.sk2andy.voiceisolation/.service.AutomationReceiver \
  -a dev.sk2andy.voiceisolation.automation.SET_PRESET \
  --es token YOUR_TOKEN \
  --es preset film

adb shell am broadcast \
  -n dev.sk2andy.voiceisolation/.service.AutomationReceiver \
  -a dev.sk2andy.voiceisolation.automation.SET_INTENSITY \
  --es token YOUR_TOKEN \
  --ef intensity 0.8
```

Android may reject automatic starts while the app is background-restricted. In that case, exempt Voice Isolation from battery restrictions or trigger start from a user-initiated Tasker action. Shizuku must already be running and authorized.

## Languages

English is the default. The app also includes Spanish, Swedish, Polish, French, Portuguese, Russian, and Simplified Chinese translations. Android selects the language from the device or per-app language settings.

## Technical limitations

| Area | Behavior |
|---|---|
| Regular media and game audio | Processes `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` |
| Calls, alarms, notifications | Intentionally not captured |
| DRM, hardware offload, tunneled playback | May bypass the framework mix route and remain unchanged |
| Vendor ROMs | Dynamic shell `AudioPolicy` is AOSP-based but not guaranteed on every OEM device |
| Model quality | GTCRN performs speech enhancement, not perfect vocal separation; music is usually attenuated strongly, but complete removal is not guaranteed |
| Shizuku through ADB | Must be restarted after every device reboot |

## Architecture

```text
Media apps
    │
    ▼
AOSP AudioPolicy: pure LOOP_BACK (Shizuku UID shell/root)
    │  16 kHz mono PCM
    ▼
AudioRecord ─┬──────────────────── delayed dry ────────────────┐
             └→ STFT → GTCRN/ONNX Runtime → iSTFT → +2.3 dB ─┤
                                                                ▼
                                                     intensity mix → AudioTrack
```

The app excludes its own `AudioTrack` from the capture mix by Shizuku UID. Android 13+ also receives the correct `com.android.shell` attribution so AudioFlinger accepts the shell identity. On Android 12, the ADB shell lacks the required `MODIFY_AUDIO_ROUTING` permission, so that version is not listed as compatible.

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Tests:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
```

## Verification

| Test | Coverage |
|---|---|
| JVM unit tests | PCM boundaries and STFT/iSTFT reconstruction |
| Android instrumentation | Real ONNX Runtime and `gtcrn.onnx` loading, followed by four streaming frames |
| Android 16 emulator | Shizuku UserService as UID 2000, policy registration, remote submix, 440 Hz `USAGE_MEDIA` capture, GTCRN processing, and stop/policy unregister |
| Speech/music vector | Pinned real GTCRN speech sample plus a deterministic music bed; requires at least 2 dB SI-SDR improvement and 6 dB speech-over-music selectivity |

On the emulator, the synthetic signal took about `0.9 ms` of inference time per `16 ms` frame (`RTF ≈ 0.05`). Actual performance depends on the device.

The reproducible model measurement runs separately so the Android app itself can remain offline:

```bash
python3 -m venv /tmp/voice-isolation-validation
/tmp/voice-isolation-validation/bin/pip install numpy onnxruntime
/tmp/voice-isolation-validation/bin/python tools/validate_gtcrn_music.py
```

The pinned vector measured `+2.81 dB` SI-SDR improvement, `-2.30 dB` speech gain, and `-16.11 dB` music gain. Speech therefore remained `13.80 dB` above the music gain. This demonstrates strong attenuation for this test vector, not perfect separation for arbitrary music.

## Acknowledgements and third-party software

[Poise Android](https://github.com/chabandou/poise-android) was used only as a product and architecture comparison. That project does not publish a license, so no Poise source code was copied. The GTCRN model comes directly from the MIT-licensed official upstream project. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details.

The app also uses [ONNX Runtime](https://github.com/microsoft/onnxruntime) and the [Shizuku API](https://github.com/RikkaApps/Shizuku-API). Thanks to their maintainers and contributors.

## License

Original source code is available under the [MIT License](LICENSE). Third-party components remain under their respective licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
