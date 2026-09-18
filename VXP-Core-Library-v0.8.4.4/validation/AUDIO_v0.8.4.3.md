# VXP-Core v0.8.4.3 — AUDIO focused validation

## Scope

This release adds a clean-room, JVM-neutral audio bridge before NETWORK work begins.

### Core API surface

First-class handlers in `MreRuntime.kt`:

- `vm_audio_play_bytes`
- `vm_audio_play_bytes_no_block`
- `vm_audio_play_file`
- `vm_audio_pause`
- `vm_audio_resume`
- `vm_audio_stop`
- `vm_audio_stop_all`
- `vm_audio_is_app_playing`
- `vm_audio_get_time`
- `vm_audio_register_interrupt_callback`
- `vm_audio_clear_interrupt_callback`
- `vm_set_volume`
- `vm_audio_set_volume_type`
- `vm_midi_play_by_bytes`
- `vm_midi_pause`
- `vm_midi_resume`
- `vm_midi_stop`
- `vm_midi_stop_all`
- `vm_midi_get_time`

CatBoxMRE directly observes the existing non-blocking play / stop-all / is-playing / volume path in the current corpus. The remaining handlers are compatibility-first synthetic regression coverage until a supplied VXP selects their exact calling shape.

## Architecture

`vxp-core` contains no Android classes. It defines `MreAudioHost` plus immutable request/event/state models. Guest memory is copied before host playback and sandbox file paths are resolved before they leave the runtime.

`vxp-core-android` supplies `AndroidMreAudioHost`:

- PCM WAV 8/16-bit mono/stereo -> `AudioTrack.MODE_STATIC`.
- Encoded audio bytes -> private temporary file -> `MediaPlayer`.
- MIDI bytes -> private `.mid` temporary file -> `MediaPlayer` when supported by the device codec stack.
- Sandbox files -> `MediaPlayer` using the already-resolved app-private host file.

## Callback threading

Android playback completion/error may be delivered from an Android media callback thread. The host emits `MreAudioHostEvent`; `MreRuntime` converts it to `MreEvent.GuestCallback`; only `MreEventLoop` invokes the guest callback through `ArmCpu`.

This avoids concurrent entry into guest CPU state.

The current clean-room completion compatibility profile passes `(eventCode, playbackId)`, where `1` means completion and `-1` means host error. Exact vendor enum values/callback prototype are intentionally not claimed without corpus evidence.

## Regression

`tests/jvm/AudioRegression.kt` verifies:

- all v0.8.4.3 audio/MIDI symbols are first-class;
- bytes are copied out of guest memory;
- invalid pointer/size fails before host side effects;
- play / pause / resume / stop state;
- volume normalization;
- sandbox file resolution and traversal rejection;
- separate MIDI media kind;
- completion is queued as `MreEvent.GuestCallback`;
- clearing the interrupt callback suppresses completion delivery.

Result:

```text
[OK] v0.8.4.3 AUDIO regression passed
```

## Android source check

`AndroidMreAudioHost.kt` was syntax-compiled against minimal Android API stubs after the JVM core compile. A full Android Gradle build still requires an Android SDK on the build host.

## Limitations carried intentionally

- `vm_audio_play_bytes` currently dispatches asynchronously, same as the no-block path, so the core event loop is never blocked by host media playback.
- MIDI playback depends on the Android device/media codec stack.
- Audio duration/seek/mixed/stream APIs are not part of this v0.8.4.3 scope.
- NETWORK remains unchanged/offline-compatible and is deferred to the next focused pass.
