# VXP Core Library v0.8.3 — Clean-Room Kotlin Edition

Thư viện lõi `.vxp` độc lập để nhúng vào UI Android hiện có. Đây là bản **clean-room / Kotlin-only**: không có runtime emulator bên thứ ba, JNI, NDK, CMake, C/C++ guest runtime, proprietary vendor SDK header/library hay catalog sinh từ SDK proprietary.

> Ghi chú: đây là chính sách kỹ thuật/provenance, không phải tư vấn pháp lý. Khi thương mại hóa, vẫn nên rà soát license của toàn bộ dependency và yêu cầu pháp lý tại thị trường phát hành.

## Kiến trúc

- `vxp-core`: Kotlin/JVM trung lập — ELF32 ARM, RAW_ARM_ZLIB, ARM/Thumb interpreter, guest memory/heap/event loop, compatibility dispatcher, RGB565 graphics, sandbox filesystem.
- `vxp-core-android`: Kotlin Android host — Flash Lite/SWF/AVM1 renderer, Android text rasterizer, RGB565 `FrameSnapshot -> Bitmap` adapter.

UI chỉ cần:

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
            // frame.pixels = RGB565; đưa vào UI hiện tại.
        }

        override fun onState(state: VxpSessionState) {
            // Starting / Running / Stopped / Failed
        }
    }
)

session.start()
session.keyDownLegacy(5) // OK
session.keyUpLegacy(5)
session.stop()
session.close()
```

## Clean-room compatibility model

Core không dùng catalog lấy từ SDK proprietary. Khi nạp một VXP, loader đọc trực tiếp những dữ liệu nằm trong chính binary người dùng cung cấp: ELF headers, relocations, imports, symbol names, raw payload/container metadata và resource data. Các `vm_*` handler là implementation Kotlin độc lập dựa trên hành vi quan sát được trong regression traces.

Xem:

- `docs/CLEAN_ROOM_POLICY.md`
- `docs/PROVENANCE.md`

## Backend

| Dạng VXP | Backend |
|---|---|
| ELF32 ARM | Kotlin ARM/Thumb + compatibility runtime |
| Raw ARM + zlib | Kotlin ARM/Thumb + compatibility runtime |
| Flash Lite `FWS/CWS` | Kotlin/Android SWF + AVM1 |
| Unknown | trả `UNKNOWN`, không fallback sang emulator khác |

## Regression v0.8.3

Các binary game/app không được đóng gói trong source ZIP. Trong quá trình phát triển, core đã được regression với nhiều kiểu workload: Flash Lite/AVM1, raw ARM/zlib, GCC-built ELF, RGB565 framebuffer, file enumeration và 3D software-rendered scenes.

Những sửa lỗi chính của v0.8.3 gồm:

- ELF `R_ARM_RELATIVE` với symbol index 0.
- GCC entry/bootstrap và `.init_array` constructors.
- Thumb `BLX register`, PC-relative semantics, `STRH/LDRH` immediate.
- ARM `CLZ`, `LDRD/STRD`, long multiply, PC operand semantics.
- Free-list heap với split/coalesce/realloc.
- RGB565 layers/canvas/blit/image loading.
- sandbox C:/E: file I/O + find-first/next/close.
- UCS2 path NUL termination.

## Ranh giới phát hành

Thư viện chỉ là engine. Người dùng tự nạp `.vxp` họ có quyền sử dụng. Không bundle ROM/VXP thương mại trong APK hoặc library release.
