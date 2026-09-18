# VXP-Core v0.8.4.4 — AUDIO playback accuracy + Android audio-system integration

## Scope

This release keeps the clean-room Kotlin architecture and focuses on two audio goals before NETWORK work:

1. More accurate playback timing/state: duration, current position, seek-ready host bridge, start offset and loop state.
2. Android audio-system integration: audio focus, transient interruption, ducking, lifecycle pause/resume and safe focus release.

`vxp-core` remains JVM-neutral. All `android.*` references stay in `vxp-core-android`.

## Core changes

`MreAudioSnapshot` now includes:

- `positionMs`
- `durationMs`
- `loop`
- playback state / playback id / kind / volume

`MreAudioHost` adds optional default-compatible operations:

- `seekTo(positionMs, kind)`
- `setLooping(loop, kind)`
- `onHostPause()`
- `onHostResume()`

Third-party hosts compiled from source do not need Android dependencies.

### Duration probe

`MreAudioProbe` is pure Kotlin and calculates duration for:

- RIFF/WAVE using the public container byte-rate/data-size structure.
- Standard MIDI (PPQN timing) using delta ticks and tempo meta events.

Unknown compressed formats return `0` in the JVM-only probe; Android `MediaPlayer` reports duration after prepare.

## Compatibility handlers added

First-class names added in v0.8.4.4:

- `vm_audio_bytes_duration`
- `vm_audio_duration`
- `vm_audio_play_file_ex`
- `vm_audio_terminate_background_play`
- `vm_midi_play_by_bytes_ex`

The currently unobserved `vm_audio_play_file_ex` extra arguments use a synthetic clean-room compatibility profile: argument 2 = start position in milliseconds, argument 3 = non-zero loop flag. This calling shape is regression-tested but is not claimed as a vendor prototype until a user-supplied VXP confirms it.

## Playback accuracy

### MediaPlayer

- duration: native `duration`
- current time: native `currentPosition`
- seek: native `seekTo`
- loop: native `isLooping`
- start offset: applied after prepare and before playback starts

### AudioTrack MODE_STATIC

PCM WAV playback now tracks a logical base frame plus the unsigned playback-head delta. This avoids treating the cumulative `playbackHeadPosition` as a direct buffer offset after seek.

Completion-marker position is recalculated after seek/loop changes. The active playback is installed before `play()` is called so a very short buffer cannot finish before the runtime has an active playback id.

## Android audio focus

The Context-aware Android entry point creates `AndroidMreAudioHost(context, cacheDir)`.

Behavior:

- request focus before playback;
- API 26+ uses `AudioFocusRequest`;
- API 23–25 uses the legacy focus request;
- transient loss pauses and remembers auto-resume;
- transient duck reduces player gain to 20%;
- gain restores volume and resumes only playback paused by focus loss;
- permanent loss pauses without automatic resume;
- stop/completion/close abandons focus.

Guest `vm_set_volume` changes only player gain; it never changes the user's Android system volume.

## Lifecycle integration

`VxpSession` and `AndroidVxpSession` expose:

```kotlin
session.onHostPause()
session.onHostResume()
session.audioSnapshot()
session.seekAudioTo(1500)
session.setAudioLooping(true)
```

Only playback that was actively playing when the host paused is automatically resumed.

## Callback threading

Host events remain marshalled to the guest CPU event loop. Compatibility event codes are:

- `1`: completed
- `-1`: host error
- `2`: interrupted
- `3`: resumed

The callback convention remains a clean-room compatibility profile `(eventCode, playbackId)`.

## Regression

`tests/jvm/AudioRegression.kt` verifies:

- exact 1000 ms WAV duration;
- exact 1000 ms synthetic Standard MIDI duration;
- `vm_audio_bytes_duration` / `vm_audio_duration`;
- current position after seek on the fake host;
- extended file start position + loop propagation;
- focus/lifecycle interrupt/resume callback marshalling;
- file sandbox/traversal behavior;
- MIDI lifecycle;
- invalid guest pointer/size rejection.

Result:

```text
[OK] v0.8.4.4 AUDIO playback-accuracy regression passed
```

Android audio/facade source was syntax-compiled against local API stubs. `AndroidAudioHostRegression.kt` also executes both API 26+ and legacy API 23–25 focus paths against behavioral stubs and passes. A full Android Gradle build still requires an Android SDK on the build host.
