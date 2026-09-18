# VXP Core Library v0.8.4.4 — Clean-Room Kotlin Edition

Thư viện lõi `.vxp` độc lập để nhúng vào UI Android hiện có. Runtime là **Kotlin-only / clean-room**: không JNI, NDK, CMake, C/C++ guest runtime, proprietary vendor headers/libraries hay runtime bên thứ ba.

> Đây là chính sách kỹ thuật/provenance, không phải tư vấn pháp lý.

## Mục tiêu v0.8.4.4

Bản này giữ SYSTEM / GRAPHICS / FILE_RESOURCE của 0.8.4.2 và AUDIO bridge của 0.8.4.3, rồi tập trung vào:

1. **Độ chính xác playback:** duration/current position, seek-ready host API, loop, start offset, WAV/MIDI duration probe và completion race fix.
2. **Tích hợp audio system Android:** audio focus, transient interruption, ducking, lifecycle pause/resume và release focus đúng lúc.
3. Giữ `vxp-core` hoàn toàn JVM-neutral; mọi `android.*` chỉ nằm trong `vxp-core-android`.

## Kiến trúc

- `vxp-core`: Kotlin/JVM trung lập — ELF32 ARM, RAW_ARM_ZLIB, ARM/Thumb interpreter, guest memory/heap/event loop, compatibility dispatcher, RGB565 graphics, sandbox filesystem/resources và `MreAudioHost`.
- `vxp-core-android`: Kotlin Android host — Flash Lite/SWF/AVM1 renderer, text rasterizer, RGB565 adapter và `AndroidMreAudioHost`.

UI Android phụ thuộc:

```kotlin
implementation(project(":vxp-core-android"))
```

## Cách mở session Android — khuyến nghị v0.8.4.4

Dùng overload có `Context` để bật audio focus/interruption:

```kotlin
val session = AndroidVxpCore.open(
    context = context,
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) {
            // frame.pixels = RGB565 framebuffer của guest
        }
    }
)

session.start()
```

Overload cũ không có `Context` vẫn được giữ để tương thích source, nhưng không thể dùng `AudioManager` focus tự động.

### Lifecycle Activity/Fragment

```kotlin
override fun onPause() {
    session.onHostPause()
    super.onPause()
}

override fun onResume() {
    super.onResume()
    session.onHostResume()
}
```

Chỉ playback đang thực sự chạy lúc `onHostPause()` mới được đánh dấu để resume. Playback do guest chủ động pause sẽ không tự chạy lại.

## Public audio host API

```kotlin
val state = session.audioSnapshot()
println("${state.positionMs}/${state.durationMs} ms")

session.seekAudioTo(1500)
session.setAudioLooping(true)
```

Các API này là host-facing; guest ABI vẫn được xử lý trong `MreRuntime`.

```kotlin
VxpCoreLibrary.VERSION // 0.8.4.4
AndroidVxpCore.VERSION // 0.8.4.4
```

## AUDIO compatibility

Các handler nền từ v0.8.4.3 vẫn có:

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

v0.8.4.4 thêm first-class names:

- `vm_audio_bytes_duration`
- `vm_audio_duration`
- `vm_audio_play_file_ex`
- `vm_audio_terminate_background_play`
- `vm_midi_play_by_bytes_ex`

`vm_audio_play_file_ex` hiện dùng clean-room compatibility profile `(path, format, startMs, loopFlag)`. Extra-argument calling shape này chưa có VXP trong corpus hiện tại xác nhận nên **không được mô tả là prototype chính thức**.

## Độ chính xác playback

### WAV PCM / AudioTrack

- WAV PCM 8/16-bit, mono/stereo dùng `AudioTrack.MODE_STATIC`.
- Duration tính theo frame/sample-rate.
- Current position dùng logical base frame + unsigned playback-head delta.
- Completion marker được cập nhật lại sau seek/loop.
- Active playback được gắn trước `play()` để sound rất ngắn không làm mất callback completion.

### Encoded audio / MIDI / file

- Encoded bytes và MIDI bytes được copy vào private cache rồi phát bằng `MediaPlayer`.
- Sandbox files phát bằng `MediaPlayer` từ host path đã được core resolve an toàn.
- Duration/current position dùng `MediaPlayer.duration/currentPosition` sau prepare.
- Seek và looping dùng API playback của Android.

### JVM-neutral duration probe

`MreAudioProbe` hỗ trợ:

- RIFF/WAVE duration từ byte-rate/data-size.
- Standard MIDI PPQN duration từ delta tick + tempo meta event.

Encoded MP3/AAC/AMR không được giả đoán trong JVM core; duration thật do Android media backend báo sau khi prepare.

## Tích hợp hệ thống âm thanh Android

Khi dùng overload `AndroidVxpCore.open(context=...)`:

- request audio focus trước khi start;
- Android 8.0+ dùng `AudioFocusRequest`;
- Android 6/7 dùng focus API legacy;
- transient focus loss → pause và đánh dấu resume;
- duck → giảm gain của player xuống 20%;
- focus gain → restore volume, chỉ resume playback bị focus pause;
- permanent focus loss → pause, không tự resume;
- stop/completion/close → abandon audio focus.

`vm_set_volume` chỉ thay đổi gain playback của emulator, **không thay đổi system media volume của người dùng**.

## Callback audio

Callback Android không gọi `ArmCpu` trực tiếp. Host phát `MreAudioHostEvent`, `MreRuntime` enqueue `MreEvent.GuestCallback`, rồi `MreEventLoop` gọi guest callback trên CPU thread.

Compatibility event profile hiện tại:

- `1` = completed
- `-1` = host error
- `2` = interrupted
- `3` = resumed

Calling shape `(eventCode, playbackId)` vẫn là clean-room compatibility convention cho tới khi corpus thực tế xác nhận chi tiết hơn.

## Safety / lifetime

- Guest audio byte buffer được copy trước khi rời API call.
- Audio file chỉ được resolve từ guest sandbox C:/E:.
- Path traversal bị từ chối trước host playback.
- Byte request tối đa 32 MiB.
- Temp audio/MIDI cache được xóa khi stop/completion.
- `vxp-core` không import `android.*`.

## SYSTEM / GRAPHICS / FILE_RESOURCE

v0.8.4.4 giữ các nâng cấp trước:

- SYSTEM aliases/callback/tick/resolver và `vm_sscanf` subset.
- RGB565 layers/canvas/clip/blit/text/PNG/image aliases/mirror.
- sandbox open/read/write/seek/tell/EOF/copy/rename/path helpers/timestamp/attributes.
- resource init/load/resource-from-file/offset/delete/deinit với bounds validation.

## Regression v0.8.4.4

- AUDIO playback-accuracy regression: PASS.
- SYSTEM/GRAPHICS/FILE_RESOURCE regression: PASS.
- ARM/Thumb/PNG regressions: PASS.
- Android audio host + facade syntax compile against API stubs: PASS.
- CatBoxMRE: 186 frames / 240×320.
- RetroMRE: 91 frames / 240×320.
- Whisk3D: 2 frames / 240×320.
- Observed corpus: 76/76 unique `vm_*` symbols first-class, missing 0.

Xem:

- `validation/AUDIO_v0.8.4.4.md`
- `validation/COMPATIBILITY_v0.8.4.4.md`
- `validation/regression_v0.8.4.4.txt`
- `validation/audio_corpus_smoke_v0.8.4.4.log`

## Ranh giới clean-room

- Không bundle commercial `.vxp` trong release.
- Không copy/redistribute proprietary vendor headers, libraries, source, binaries hoặc tài liệu dumps.
- Compatibility dựa trên binary metadata/import names, guest traces, regression tự viết và public platform specifications/APIs.

Xem `docs/CLEAN_ROOM_POLICY.md` và `docs/PROVENANCE.md`.
