# VXP Core Library v0.8.3 — Kotlin-only

Thư viện lõi chạy `.vxp` để nhúng vào UI Android hiện có. Project này **không có Activity, View, Compose UI, JNI, NDK, CMake hay C/C++**.

## Module

- `vxp-core`: lõi Kotlin/JVM trung lập, chạy ELF ARM + RAW_ARM_ZLIB/Gameloft, MRE runtime, heap, file, graphics RGB565, input/timer.
- `vxp-core-android`: host Android Kotlin-only. Bổ sung Flash Lite/SWF renderer bằng `android.graphics`, AVM1 menu/input, system font rasterizer và bộ chuyển `FrameSnapshot -> Bitmap`.

UI ứng dụng chỉ cần phụ thuộc:

```kotlin
implementation(project(":vxp-core-android"))
```

`vxp-core-android` kéo theo `vxp-core`.

## Public API

```kotlin
val session = AndroidVxpCore.open(
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) {
            // frame.pixels = RGB565; tự đưa vào UI hiện tại.
        }

        override fun onState(state: VxpSessionState) {
            // Starting / Running / Stopped / Failed
        }
    }
)

session.start()

// Nokia UI legacy -> core
session.keyDownLegacy(5)   // OK
session.keyUpLegacy(5)
session.keyDownLegacy(6)   // LSK
session.keyDownLegacy(7)   // RSK

session.stop()
session.close()
```

Không gọi `NativeVxpBridge`, không `System.loadLibrary`, không chạy guest ARM như mã native của Android. ARM/Thumb trong `.vxp` luôn là dữ liệu guest được interpreter Kotlin thực thi.

## Backend hiện có

| Dạng VXP | Backend | Trạng thái v0.8.3 |
|---|---|---|
| ELF32 ARM | Kotlin ARM/Thumb + MRE | Có |
| Gameloft raw ARM + zlib | Kotlin ARM/Thumb + MRE | Có |
| Flash Lite `FWS/CWS` | Android Kotlin + SWF/AVM1 | Có, mức compatibility-first |
| Unknown/proprietary khác | Detector | Trả `UNKNOWN` thay vì fallback sang emulator khác |

## Regression thực tế

### The Amazing Spider-Man - The Daily Bugle

User-supplied binary, không kèm trong ZIP.

- Backend: `RAW_ARM_ZLIB`
- regression dài bằng public library API: ~23.5 triệu guest instructions / 425 framebuffer frames
- framebuffer 240×320 RGB565
- không CPU/memory fault trên đường test chính
- `stubbedSymbols = []` trên regression chính
- hỗ trợ PNG image loading/property, Thumb BLX immediate, Thumb ALU register group
- App Manager count query + operator code output hợp lệ
- `vm_malloc(0)` không còn bị tính nhầm là OOM/failure
- file upload này có chuỗi `GL_Demo`/SMS/UNLOCK; core không giả lập mua hàng hoặc gửi SMS thật

### CrazyTaxi_1.0.vxp

- Backend: `FLASH_LITE`
- Stage 176×220, 20 FPS, 35 frames
- Shape 25, Sprite 12, Bitmap JPEG3 5, Button 7
- menu startup ở frame 4
- `OK` chạy AVM1 ButtonCondAction thật -> frame 5
- `AVM1 actions = 10`, `input events = 1`, `playing = true`

Android Flash backend mới được compile-test trên JVM qua test-only `android.graphics` compatibility stubs và dùng cùng parser/player/AVM1 với source Android.

## Quan trọng khi tích hợp UI cũ

**Không hiển thị `bootMessage` sau khi nhận frame đầu tiên.** `onFrame()` là nguồn LCD duy nhất khi session ở `Running`.

Đường đúng:

```text
VXP file
 -> AndroidVxpCore
 -> Kotlin backend (ARM/MRE hoặc Flash Lite)
 -> FrameSnapshot RGB565
 -> controller của bạn
 -> EmulatorScreenCanvas/UI hiện có
```

Không còn:

```text
MreNativeVxpApp -> NativeVxpBridge -> JNI -> vxp_runner.cpp
```

Xem `docs/INTEGRATE_EXISTING_UI.md`.


## Thay đổi 0.8.3

- Sửa ELF `R_ARM_RELATIVE` với symbol index 0, giúp GOT/init-array của VXP GCC được relocate đúng.
- Thêm bootstrap `gcc_entry` và chạy `.init_array` constructors trước `vm_main`.
- Sửa Thumb `BLX register` (LR + ARM/Thumb interworking).
- Sửa Thumb PC-relative high-register semantics: PC = current instruction + 4.
- Thêm Thumb `STRH/LDRH` immediate.
- Thêm ARM `CLZ`, `LDRD/STRD`, `UMULL/UMLAL/SMULL/SMLAL`.
- Sửa ARM Operand2 khi đọc r15: PC = current instruction + 8; khắc phục veneer `ADD pc,r12,pc`.
- `_vm_log_info/_vm_log_error` được đăng ký như API logging trực tiếp.
- `vm_find_first/next/close` giờ enumerate file thật trong sandbox C:/E: với wildcard.
- Sửa UCS2→ASCII NUL termination; RetroMRE có thể nhìn thấy ROM trong sandbox thay vì đường dẫn bị thêm `?`.
- Giữ toàn bộ regression v0.8.2: Spider-Man RAW_ARM_ZLIB và Crazy Taxi Flash Lite/AVM1.

## Compatibility mới ở v0.8.3

- `CatBoxMRE.vxp`: boot + input + timer + framebuffer, vào gameplay 240×320.
- `RetroMRE.vxp`: boot + input + timer + framebuffer, vào menu `RETRO MRE / PIXEL LAUNCHER`.
- `Whisk3D.vxp`: boot + framebuffer, render scene 3D cube/sphere/cone.

Các binary `.vxp` người dùng cung cấp **không nằm trong source ZIP**. Xem `validation/new_vxp/COMPATIBILITY_v0.8.3.md`.
