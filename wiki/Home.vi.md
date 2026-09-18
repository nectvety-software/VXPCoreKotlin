# VXP-Core Library — Wiki (Tiếng Việt)

## Ghi công

Duy trì bởi **DOXUANHOP** — https://qeafivels.com/  
Namespace: `vn.com.doxuanhop.vxpcore.android`. Khi dùng lại thư viện,
vui lòng ghi công kèm link https://qeafivels.com/.

## 1. Tổng quan

Lõi Kotlin-only chạy gói MediaTek `.vxp` trong UI Android có sẵn.
Không Activity/View/Compose, không JNI/NDK/CMake/C/C++, không
`System.loadLibrary`. Mã guest ARM/Thumb do interpreter Kotlin thực thi.

```
VXP -> AndroidVxpCore -> backend Kotlin (ARM/MRE hoặc Flash Lite)
  -> FrameSnapshot RGB565 -> controller của bạn -> UI hiện có
```

## 2. Phiên bản

| Thư mục | Version | Artifact | Ghi chú |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | Tách thư viện lần đầu |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` | `dist/vxp-core-0.8.2.jar` | Thumb ALU, SMS sandbox, regression dài |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` | `dist/vxp-core-0.8.3.jar` | Tương thích ELF/GCC + 3 title ELF mới |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room | `dist/vxp-core-0.8.3-cleanroom.jar` | Rebase Kotlin-only, docs provenance/compliance |
| `VXP-Core-Library-v0.8.4.1/` | `0.8.4.1` clean-room | `dist/vxp-core-0.8.4.1.jar` | Mới nhất: alias SYSTEM/GRAPHICS/FILE_RESOURCE, 76/76 observed first-class |

Tích hợp mới và mọi bản phân phối dùng **v0.8.4.1**. Xem `VERSIONS.md`.

## 3. Backend

- `ELF32 ARM` → ARM/Thumb Kotlin + MRE: có, v0.8.3 thêm bootstrap GCC `gcc_entry` + `.init_array`.
- Raw ARM + zlib (`RAW_ARM_ZLIB`, Gameloft) → ARM/Thumb Kotlin + MRE: có.
- Flash Lite `FWS/CWS` → Kotlin Android + SWF/AVM1: mức compatibility-first.
- Lạ → detector trả `UNKNOWN`, không fallback MREmu.

## 4. Yêu cầu

JDK 17+, Gradle 8.x, AGP `8.7.3`, Kotlin `2.0.21`,
Android `compileSdk 35` / `minSdk 23`, repo `google()` + `mavenCentral()`.
Không cần NDK/CMake. Kiểm tra: `./verify_kotlin_only.sh`.

## 5. Cài đặt

```kotlin
// settings.gradle.kts
include(":vxp-core")
include(":vxp-core-android")

// module app
dependencies {
    implementation(project(":vxp-core-android")) // tự kéo :vxp-core
}
```

Hoặc dùng `dist/vxp-core-0.8.4.1.jar` dựng sẵn.

## 6. Sử dụng

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
session.keyDownLegacy(5); session.keyUpLegacy(5) // OK
session.penDown(x, y); session.penMove(x, y); session.penUp(x, y)
session.stop(); session.close()
```

Phím: `1 UP, 2 DOWN, 3 LEFT, 4 RIGHT, 5 OK, 6 LSK, 7 RSK, 10 CLEAR, 48-57 số, 42 *, 35 #`.
Chỉ hiện boot text trước frame đầu; sau `onFrame()` thì framebuffer game
là nguồn LCD duy nhất.

## 7. Kiểm chứng

- Spider-Man (`RAW_ARM_ZLIB`): 23.524.795 insn / 425 frames / 440 events / 424 timers, 240x320 RGB565, không fault, `stubbedSymbols = []`.
- Crazy Taxi (`FLASH_LITE`): 176x220 @20fps, 35 frames, menu frame 4 → OK → frame 5 bằng AVM1 thật.
- v0.8.3 thêm 3 title ELF: `CatBoxMRE` gameplay (218 frames / 28.3M instr), `RetroMRE` menu (18 frames / 11.8M instr, `vm_find_*` thật + fix UCS2 NUL), `Whisk3D` scene 3D (9 frames / 33.3M instr).
- Fix CPU v0.8.3: `R_ARM_RELATIVE` sym-0, `gcc_entry`/`.init_array`, Thumb BLX-reg + PC(+4) + STRH/LDRH, ARM CLZ + LDRD/STRD + nhân dài, Operand2 PC(+8), `_vm_log_*`, `vm_find_*` wildcard.
- Re-validation clean-room: CatBoxMRE 30.1M/218f, RetroMRE 19.0M/28f, Whisk3D 33.3M/9f, Spider-Man 9.5M/82f, Crazy Taxi 4→5. `verify_clean_room.sh` PASS.
- v0.8.4.1 alias pass: SYSTEM tick/resolver/callback, `vm_sscanf`, disk sandbox; GRAPHICS screen/image/load/mirror + `create_layer_ex`; FILE dual get-size, open/append chặt, `vm_resource_init`/`vm_res_load`. Corpus 76/76 first-class.
- v0.8.4.1 timed runs: CatBoxMRE 30.8M/218f, RetroMRE 26.8M/39f, Whisk3D 33.3M/9f, Spider-Man 9.4M/83f `stubbedSymbols=[]`, Crazy Taxi 4→OK(10 AVM1)→5.

## 8. An toàn

`vm_send_sms` luôn trả failure trong sandbox; core không gửi SMS hay billing thật.

## 9. Liên kết

- https://qeafivels.com/
- `../VERSIONS.md`, `../README.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/INTEGRATE_EXISTING_UI.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`
- `VXP-Core-Library-v0.8.3-cleanroom/validation/CLEANROOM_VALIDATION_v0.8.3.md`
- `VXP-Core-Library-v0.8.4.1/docs/OBSERVED_COMPATIBILITY_SURFACE.md`
- `VXP-Core-Library-v0.8.4.1/validation/COMPATIBILITY_v0.8.4.1.md`
