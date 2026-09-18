# VXP-Core Library — Tổng hợp các phiên bản

Thư viện lõi Kotlin-only chạy `.vxp` để nhúng vào UI Android hiện có.
Không `Activity/View/Compose`, không `JNI/NDK/CMake/C++`, không `System.loadLibrary`.
ARM/Thumb trong `.vxp` là dữ liệu guest do interpreter Kotlin thực thi.

```
VXP file
 -> AndroidVxpCore
 -> Kotlin backend (ARM/MRE hoặc Flash Lite)
 -> FrameSnapshot RGB565
 -> controller của bạn
 -> EmulatorScreenCanvas / UI hiện có
```

## 1. Danh sách phiên bản trong repo

| Thư mục | Version code | `dist` | Trạng thái |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` (`VxpCoreLibrary.VERSION`, `AndroidVxpCore.VERSION`) | `dist/vxp-core-0.8.0.jar` | Thư viện lõi đầu tiên, bỏ app/demo UI |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` (đồng bộ core + Android facade) | `dist/vxp-core-0.8.2.jar` | Thumb ALU + SMS sandbox + regression dài |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` (đồng bộ core + Android facade) | `dist/vxp-core-0.8.3.jar` | Tương thích ELF/GCC + 3 title ELF mới |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room (đồng bộ core + Android facade) | `dist/vxp-core-0.8.3-cleanroom.jar` | Mới nhất: rebase Kotlin-only, bỏ catalog SDK, + docs provenance/compliance |

> Ghi chú: `CHANGELOG v0.8.2` có nhắc patch `v0.8.1` (PNG, Thumb BLX immediate, App Manager, operator-code)
> nhưng trong repo hiện chỉ lưu 2 gói `v0.8` và `v0.8.2`.

## 2. Module chung

| Module | Vai trò |
|---|---|
| `vxp-core` | Lõi Kotlin/JVM trung lập: ELF ARM + RAW_ARM_ZLIB/Gameloft, MRE runtime, heap, file, graphics RGB565, input/timer |
| `vxp-core-android` | Host Android Kotlin-only: Flash Lite/SWF renderer bằng `android.graphics`, AVM1 menu/input, system font rasterizer, `FrameSnapshot -> Bitmap` |
| `tests/jvm` (chỉ v0.8.2+) | `SpiderManLibraryTest`, `ThumbAluRegression`, `ThumbBlxRegression`, `PngDecoderRegression` (+ v0.8.3: `ArmClz/ArmDoubleword/ArmLongMultiply/ArmPcOperand/ThumbBlxRegister/ThumbHalfwordImmediate/ThumbPcSemantics`) |
| `tests/android-graphics-jvm` (chỉ v0.8.2+) | `CrazyTaxiAndroidBackendTest` + `android.graphics` stubs để compile-test trên JVM |
| `validation/` | Log chạy thật, sha256 frame, ảnh PNG |
| `docs/` | `TEST_RESULTS.md`, `INTEGRATE_EXISTING_UI.md` |

Phụ thuộc duy nhất cho app:

```kotlin
implementation(project(":vxp-core-android")) // tự kéo theo :vxp-core
```

## 3. Backend hỗ trợ

| Dạng VXP | Backend | v0.8.0 | v0.8.2 | v0.8.3 |
|---|---|---|---|---|
| `ELF32 ARM` | Kotlin ARM/Thumb + MRE | Có | Có | Có, +GCC bootstrap/init-array |
| Gameloft raw ARM + zlib (`RAW_ARM_ZLIB`) | Kotlin ARM/Thumb + MRE | Có | Có | Có, giữ regression |
| Flash Lite `FWS/CWS` | Android Kotlin + SWF/AVM1 | Có, compatibility-first | Có, giữ nguyên + test JVM stubs | Có, giữ menu 4→5 |
| Unknown/proprietary | Detector | Trả `UNKNOWN`, không fallback MREmu | Giữ nguyên | Giữ nguyên |

## 4. Public API (giữ nguyên qua 2 bản)

```kotlin
val session = AndroidVxpCore.open(
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) { /* RGB565 -> UI */ }
        override fun onState(state: VxpSessionState) { /* Starting/Running/Stopped/Failed */ }
    }
)
session.start()
session.keyDownLegacy(5) // OK; 1-4 D-pad, 6 LSK, 7 RSK, 10 CLEAR, 48-57 digits, 42 *, 35 #
session.keyUpLegacy(5)
session.penDown(x, y); session.penMove(x, y); session.penUp(x, y)
session.stop(); session.close()
```

Quy tắc UI: **không hiển thị `bootMessage` sau frame đầu tiên** — `onFrame()` là nguồn LCD duy nhất khi `Running`.
Đường cũ đã bỏ: `MreNativeVxpApp -> NativeVxpBridge -> JNI -> vxp_runner.cpp`.

## 5. So sánh regression thực tế

### 5.1 The Amazing Spider-Man - The Daily Bugle (`RAW_ARM_ZLIB`, binary user-supplied, không kèm ZIP)

| Chỉ số | v0.8.0 | v0.8.2 |
|---|---|---|
| Thời gian / quy mô test | 6s, `6.896.572` insn | `23.524.795` insn ( Core log ghi >24M / >700 frames trên demo dài) |
| Frames / events / timer | `76 / 77 / -` | `425 / 440 / 424` |
| Framebuffer | `240x320 RGB565`, sha `7613e735…` | `240x320 RGB565` |
| CPU/memory fault | Không | Không |
| `stubbedSymbols` | `[]` | `[]` |

Fix liên quan Spider-Man:
- v0.8.0: RAW ARM two-zlib loader, ARM/Thumb halfword/signed loads, ARM BLX immediate, Thumb LDMIA base-in-list, heap split/coalesce/realloc, resource/canvas/blt, text/file runtime, `vm_malloc(0)` không tính OOM, `vm_graphic_mirror` no-op, audio suspend/resume compat.
- v0.8.1 (kế thừa trong v0.8.2): PNG image loading/property, Thumb BLX immediate `F001 E9A4`, App Manager installed-list count query, operator-code output.
- v0.8.2: Thumb ALU `ADC/SBC/ROR/NEG/CMN`, đúng opcode `0x4241 = NEG r1,r0`, `strtoi` thành API thật.

An toàn: binary có chuỗi `GL_Demo/SMS/UNLOCK`; `vm_send_sms` (v0.8.2) luôn trả failure, không gửi SMS/mạng thật.

### 5.2 CrazyTaxi_1.0.vxp (`FLASH_LITE`)

| Chỉ số | v0.8.0 | v0.8.2 |
|---|---|---|
| Stage | `176x220, 20 FPS, 35 frames` | Giữ nguyên |
| Shapes/Sprites/Bitmaps/Buttons | `25 / 12 / JPEG3 5 / 7` | Giữ nguyên |
| Menu | frame 4, `OK` -> AVM1 `ButtonCondAction` -> frame 5 | Giữ nguyên |
| AVM1 actions / inputs / playing | `10 / 1 / true` | Giữ nguyên |
| Kiểm chứng | sha menu `fbf54d2f…`, gameplay `417846c0…` | `validation/crazytaxi_v082.txt` |

Flash backend v0.8.x: parser SWF + player + AVM1 menu/input, `ZWS/LZMA` báo chưa hỗ trợ, giữ rule timeline `v0.4 -> v0.5` cho scenery động.

### 5.3 v0.8.3 — 3 title ELF mới (binary user-supplied, không kèm ZIP)

| VXP | Backend | Kết quả | Ghi chú |
|---|---|---|---|
| `CatBoxMRE.vxp` | `ELF_ARM` | PASS gameplay, 218 frames / 28.3M instr | input, timer, resource, file, graphics active |
| `RetroMRE.vxp` | `ELF_ARM` | PASS menu `RETRO MRE / PIXEL LAUNCHER`, 18 frames / 11.8M instr | `vm_find_first/next/close` thật + UCS2 NUL fix, test `demo.gb` ở sandbox E: |
| `Whisk3D.vxp` | `ELF_ARM` | PASS scene 3D, 9 frames / 33.3M instr | render cube/sphere/cone 240×320 |

Fix lõi nhờ 3 title này: `R_ARM_RELATIVE` sym-index-0, bootstrap `gcc_entry` + `.init_array`,
Thumb `BLX register` + PC semantics (+4) + `STRH/LDRH`, ARM `CLZ` + `LDRD/STRD` +
`UMULL/UMLAL/SMULL/SMLAL` + Operand2 PC (+8), `_vm_log_info/_vm_log_error`,
`vm_find_*` wildcard, UCS2→ASCII NUL. Xem
`VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`.

### 5.4 v0.8.3-cleanroom — re-validation trên cây Kotlin-only rebase (không bundle binary)

| Workload | Backend | Kết quả clean-room |
|---|---|---|
| `CatBoxMRE` | `ELF_ARM` | 30.163.160 instr / 218 frames, stable timed run |
| `RetroMRE` | `ELF_ARM` | 18.960.170 instr / 28 frames, stable timed run |
| `Whisk3D` | `ELF_ARM` | 33.296.269 instr / 9 frames, stable timed run |
| Spider-Man | `RAW_ARM_ZLIB` | 9.480.936 instr / 82 frames, no unresolved symbols |
| Crazy Taxi | `FLASH_LITE` | menu 4 → OK → frame 5 |

- `verify_clean_room.sh`: PASS; không còn file C/C++/JNI/NDK hay catalog sinh từ SDK trong gói.
- Tài liệu mới: `NOTICE-CLEANROOM.txt`, `docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`,
  `docs/COMMERCIAL_DISTRIBUTION_CHECKLIST.md`, `validation/CLEANROOM_VALIDATION_v0.8.3.md`,
  thêm `build/GenericVxpProbe.kt` + `build/core_sources.txt`.

## 6. Thay đổi chi tiết

### v0.8.3-cleanroom
- Rebase trên implementation Kotlin-only v0.8.3; xóa workflow catalog suy từ SDK proprietary và mọi wording SDK trong source/docs.
- Thêm chính sách clean-room/provenance; handler tương thích chỉ giữ khi implement độc lập từ hành vi quan sát + regression tests.
- Không JNI/NDK/C/C++/MREmu runtime; `vm_*` là compatibility identifiers do guest import, implement bằng Kotlin độc lập.
- Commercial binaries không kèm trong gói; checklist phát hành thương mại (SAF import, sandbox, SMS/network opt-in, ads/Pro tách khỏi content).

### v0.8.0
- Tách thành thư viện, xóa app/demo UI khỏi deliverable.
- `vxp-core` JVM thuần cho ELF + RAW_ARM_ZLIB.
- `vxp-core-android` Kotlin host, port Flash renderer từ AWT/Swing sang `android.graphics`.
- Public `VxpCoreLibrary`, `VxpSession`, `AndroidVxpCore`, `AndroidVxpSession`; output duy nhất `FrameSnapshot RGB565`; Nokia key helpers.
- Crazy Taxi: Shape/PlaceObject2/RemoveObject2/JPEG3/timeline/ButtonCondAction/AVM1 Start.
- Spider-Man: regression 6s không fault.

### v0.8.3
- ELF `R_ARM_RELATIVE` sym-index-0; GOT/init-array VXP GCC relocate đúng.
- Bootstrap `gcc_entry` (`vm_get_sym_entry`) + chạy `.init_array` trước `vm_main`.
- Thumb `BLX register` (LR + interworking), PC high-register = current+4, thêm `STRH/LDRH` immediate.
- ARM `CLZ`, `LDRD/STRD`, `UMULL/UMLAL/SMULL/SMLAL`; Operand2 đọc r15 = current+8 (fix veneer `ADD pc,r12,pc`).
- `_vm_log_info/_vm_log_error` thành logging API; `vm_find_first/next/close` enumerate thật + wildcard; UCS2→ASCII giữ NUL.
- Regression: CatBoxMRE gameplay, RetroMRE menu, Whisk3D 3D; giữ Spider-Man + Crazy Taxi.
- Thêm 7 CPU regression tests + `validation/new_vxp/*`, `validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`, CSV/SHA256.

### v0.8.2
- `ArmCpu`: đủ nhóm Thumb ALU register: `ADC, SBC, ROR, NEG, CMN`.
- `MreRuntime`: `strtoi` API thật; `vm_send_sms` sandbox failure.
- Đồng bộ `VERSION = "0.8.2"` core + Android.
- Thêm `PngDecoder.kt`, tests JVM + validation PNG (`spiderman_v082_*.png`, `thumb_*_regression.txt`, `png_decoder_regression.txt`).
- Giữ hành vi Crazy Taxi frame 4 -> 5.

## 7. Cấu trúc file khác biệt

- v0.8.0: `16` file `vxp-core/*.kt` (chưa có `PngDecoder.kt`), không có `tests/`, `validation/` có 4 txt.
- v0.8.2: thêm `vxp-core/.../PngDecoder.kt`, thêm `tests/jvm/*` (4 file) + `tests/android-graphics-jvm/*` (2 file), `validation/` mở rộng (7 txt + 3 png).
- v0.8.3: thêm 7 CPU regression tests (tổng 11 tests/jvm), `validation/new_vxp/*` + `validation/all_vxp/*`, `ALL_VXP_COMPATIBILITY_v0.8.3.md`, CSV + SHA256 (tổng 116 files).
- v0.8.3-cleanroom: + `NOTICE-CLEANROOM.txt`, `verify_clean_room.sh`, `build/GenericVxpProbe.kt`, docs `CLEAN_ROOM_POLICY/PROVENANCE/COMMERCIAL_DISTRIBUTION_CHECKLIST`, `validation/CLEANROOM_VALIDATION_v0.8.3.md` + `validation/cleanroom_vxp/*` (tổng 90 files).

## 8. Nên dùng bản nào?

- Dùng `v0.8.3-cleanroom` cho mọi tích hợp mới và mọi bản phát hành/phân phối: tương đương compat v0.8.3 nhưng provenance sạch, có checklist thương mại.
- `v0.8.3` thường giữ lại để đối chiếu trước rebase clean-room.
- Chỉ tham khảo `v0.8.0` khi cần đối chiếu sha frame cũ (`7613e735…`, `fbf54d2f…`) hoặc hành vi trước Thumb-ALU fix.
- `v0.8.2` giữ lại để đối chiếu regression Spider-Man dài 23.5M insn trước thay đổi CPU v0.8.3.

## 9. Nguồn đối chiếu

- `VXP-Core-Library-v0.8/README.md`, `CHANGELOG.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.2/README.md`, `CHANGELOG.md`, `docs/TEST_RESULTS.md`, `docs/INTEGRATE_EXISTING_UI.md`
- `VXP-Core-Library-v0.8.3/README.md`, `CHANGELOG.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3-cleanroom/README.md`, `CHANGELOG.md`, `NOTICE-CLEANROOM.txt`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`, `docs/COMMERCIAL_DISTRIBUTION_CHECKLIST.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`, `validation/new_vxp/COMPATIBILITY_v0.8.3.md`
- `vxp-core/.../VxpLibrary.kt: VERSION`, `vxp-core-android/.../AndroidVxpCore.kt: VERSION`
- `validation/spiderman_v08.txt`, `spiderman_v082.txt`, `crazytaxi_v08.txt`, `crazytaxi_v082.txt`
