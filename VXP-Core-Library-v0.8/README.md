# VXP Core Library v0.8.0 — Kotlin-only

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

| Dạng VXP | Backend | Trạng thái v0.8 |
|---|---|---|
| ELF32 ARM | Kotlin ARM/Thumb + MRE | Có |
| Gameloft raw ARM + zlib | Kotlin ARM/Thumb + MRE | Có |
| Flash Lite `FWS/CWS` | Android Kotlin + SWF/AVM1 | Có, mức compatibility-first |
| Unknown/proprietary khác | Detector | Trả `UNKNOWN` thay vì fallback MREmu |

## Regression thực tế

### The Amazing Spider-Man - The Daily Bugle

User-supplied binary, không kèm trong ZIP.

- Backend: `RAW_ARM_ZLIB`
- chạy 6 giây bằng public library API
- ~6.9 triệu guest instructions
- 76 framebuffer frames trong lần test cuối
- framebuffer 240×320 RGB565
- không CPU/memory fault
- `stubbedSymbols = []` ở profile compatibility hiện tại
- `vm_malloc(0)` không còn bị tính nhầm là OOM/failure

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
