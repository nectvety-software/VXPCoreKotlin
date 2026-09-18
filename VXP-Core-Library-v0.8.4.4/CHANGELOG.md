# Changelog

## 0.8.4.4 — AUDIO playback accuracy + Android audio-system integration

- Added host-neutral duration/seek/loop/lifecycle fields to `MreAudioHost` / `MreAudioSnapshot`.
- Added pure Kotlin `MreAudioProbe` for RIFF/WAVE and Standard MIDI PPQN duration.
- Added first-class compatibility names: `vm_audio_bytes_duration`, `vm_audio_duration`, `vm_audio_play_file_ex`, `vm_audio_terminate_background_play`, `vm_midi_play_by_bytes_ex`.
- `AudioTrack.MODE_STATIC` now tracks logical base frame + unsigned playback-head delta for more accurate current-time reporting after seek.
- Completion marker is recomputed after seek/loop and active playback is armed before `play()` to avoid short-sound completion races.
- `MediaPlayer` path reports native duration/currentPosition and supports start offset, seek and loop state.
- Added Context-aware `AndroidVxpCore.open(...)` and `AndroidMreAudioHost(context, cacheDir)` for AudioManager integration.
- Added audio focus handling: transient pause/resume, ducking, permanent-loss pause, abandon on stop/completion/close.
- Added `onHostPause/onHostResume`, `audioSnapshot`, `seekAudioTo`, `setAudioLooping` public host APIs.
- Completion/interruption callbacks remain marshalled through `MreEventLoop`; no Android callback thread enters `ArmCpu`.
- Regression: AUDIO accuracy PASS; ARM/Thumb/PNG + SYSTEM/GRAPHICS/FILE_RESOURCE PASS; observed corpus 76/76; CatBoxMRE/RetroMRE/Whisk3D smoke PASS.

## 0.8.4.3 — AUDIO host-neutral bridge + Android playback

- Thêm `MreAudioHost`, `MreAudioRequest`, `MreAudioSnapshot`, `MreAudioHostEvent` trong `vxp-core`; không import Android API vào core.
- Thêm first-class audio handlers: play bytes/file, pause/resume/stop/stop-all, is-playing, get-time, volume và interrupt callback registration.
- Thêm first-class MIDI lifecycle: `vm_midi_play_by_bytes`, pause/resume/stop/stop-all/get-time.
- Guest byte buffer được copy trước playback; path file luôn qua C:/E: sandbox và ceiling 32 MiB.
- Thêm `MreEvent.GuestCallback`; completion/error từ host được marshal sang guest event-loop thread trước khi gọi callback ARM.
- `StateOnlyMreAudioHost` giữ headless/JVM deterministic và không phát âm thanh thật.
- `AndroidMreAudioHost`: PCM WAV -> AudioTrack; encoded bytes/MIDI/file -> MediaPlayer; temp cache app-private được cleanup khi stop/complete.
- `AndroidVxpCore.open()` nhận optional `audioHost` để test hoặc thay backend.
- Thêm `AudioRegression.kt`; lifecycle, copied bytes, traversal, MIDI, callback queue và invalid pointer đều PASS.
- CPU/Thumb/PNG + SYSTEM/GRAPHICS/FILE_RESOURCE regressions vẫn PASS.
- Corpus CatBoxMRE/RetroMRE/Whisk3D vẫn render sau patch; observed surface giữ 76/76 first-class.

## 0.8.4.2 — FILE_RESOURCE directory/path/resource-from-file pass

- Thêm first-class `vm_file_copy`, `vm_file_tell`, `vm_file_is_eof`, `vm_file_get_modify_time`.
- Nâng `vm_file_copy`/`vm_file_rename` cho same-path, read-only destination, cross-drive, directory/type conflict và metadata remap.
- Thêm guest path helpers `vm_get_default_folder_path`, `vm_get_filename`, `vm_get_path`; host path không lộ ra guest.
- Attribute state READ_ONLY/HIDDEN/SYSTEM/ARCHIVE được giữ bằng metadata host-side để hành vi nhất quán trên Linux/Android.
- Thêm `vm_get_resource_offset`, `vm_get_resource_offset_from_file`, `vm_load_resource_from_file`, `vm_resource_get_data_from_file`, `vm_res_delete`, `vm_res_deinit`.
- External resource data luôn được đọc qua sandbox và copy vào guest memory/heap, không mmap file host trực tiếp.
- Thêm regression cho tell/EOF, path helpers, copy/rename, timestamp/attributes, named resource-from-file, raw slice và resource lifetime.
- CPU/Thumb/PNG regressions cũ vẫn PASS; corpus observed surface giữ 76/76 first-class trên CatBoxMRE/RetroMRE/Whisk3D.

## 0.8.4.1 — SYSTEM / GRAPHICS / FILE_RESOURCE clean-room pass

### SYSTEM

- Đưa `vm_get_tick` thành first-class alias của monotonic guest tick.
- Thêm `vm_get_sym_entry` resolver callable cho GCC-built VXP.
- Thêm first-class callback aliases quan sát trong VXP: `vm_reg_key_callback`, `vm_reg_system_event_callback`, `vm_reg_touch_callback`.
- Thêm spelling alias `vm_get_removable_driver` bên cạnh `vm_get_removeable_driver`.
- Thêm `vm_sscanf` implementation Kotlin độc lập cho nhóm conversion cơ bản.
- `vm_get_disk_free_space` lấy dung lượng usable của C:/E: sandbox thay vì hằng số giả.

### GRAPHICS

- Thêm first-class aliases `vm_graphic_get_screen_w/h`.
- Thêm image buffer aliases `vm_graphic_get_image_buffer` / `vm_graphic_get_img_buffer`.
- Thêm image property alias `vm_graphic_get_image_property`.
- Thêm `vm_graphic_load_img`, `vm_graphic_release_image`.
- Thêm `vm_graphic_get_font_height`, `vm_graphic_get_text_width`.
- Đưa `vm_graphic_create_layer_ex` ra khỏi generic fallback và đăng ký trực tiếp.
- `vm_graphic_mirror` có software mirror thật cho layer/canvas handle do core sở hữu, đồng thời giữ safe no-op cho calling pattern chưa xác định.

### FILE_RESOURCE

- Thêm `vm_file_get_file_size` dual compatibility: handle hoặc UCS2 path.
- Cải thiện open semantics: quyền read/write tách theo mode, read-only không tạo directory, directory/path traversal bị từ chối.
- `read/write` từ chối length âm, kiểm tra guest buffer + output pointer trước I/O và giới hạn mỗi request ở 32 MiB.
- Partial read/EOF trả số byte chính xác; zero-byte read vẫn phải dùng handle hợp lệ.
- `seek` không còn clamp vị trí âm về 0; negative/overflow seek trả failure và không đổi file pointer.
- Append giờ được enforce ở mỗi lần write, kể cả sau khi guest đã seek sang vị trí khác.
- Thêm first-class `vm_resource_init` và `vm_res_load` cho các tên alias đã quan sát trong ELF VXP.
- `vm_load_resource`/`vm_res_load` dùng chung lookup + bounds validation; thêm UCS2-name compatibility fallback có kiểm soát.
- `vm_resource_get_data` kiểm tra range chặt và hỗ trợ zero-byte probe.
- Sửa parser ELF `.vm_res`: break đúng khi directory kết thúc/lỗi, loại duplicate entry, giữ offset mapping ổn định.
- Resource blob nhỏ hơn sẽ xóa tail cũ trong guest mapping để tránh stale resource bytes.
- Mở rộng regression cho partial read, EOF, append-after-seek, invalid length/pointer, sandbox traversal, raw-resource bounds, UCS2 resource name và ELF `.vm_res` loading.

### Clean-room diagnostics

- Thêm `tools/observed_vxp_surface.py`; tool chỉ đọc binary VXP người dùng cung cấp và runtime source, không cần vendor SDK.
- Corpus ELF hiện tại: 76 unique observed `vm_*` names, 76 first-class handlers, 0 missing.
- Thêm `SystemGraphicsFileResourceRegression.kt`.

### Regression

- CatBoxMRE: PASS — 218 framebuffer frames trong timed run cuối.
- RetroMRE: PASS — 39 frames, menu/launcher ổn định.
- Whisk3D: PASS — 9 frames, scene 3D ổn định.
- Spider-Man Daily Bugle: PASS — 83 frames, `stubbedSymbols=[]` trên test path.
- CrazyTaxi: PASS — frame 4 menu → OK → AVM1 → frame 5.
- Clean-room/Kotlin-only checks: PASS.

## 0.8.3 Clean-Room

- Rebased release on independently written Kotlin implementation.
- Removed SDK-derived catalog workflow.
- ELF/GCC bootstrap, ARM/Thumb compatibility, free-list heap, RGB565 graphics, sandbox filesystem/resources.
