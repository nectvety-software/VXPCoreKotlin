# VXP-Core Library — Wiki (English)

## Credits

Maintained by **DOXUANHOP** — https://qeafivels.com/  
Namespace: `vn.com.doxuanhop.vxpcore.android`. If you reuse this library,
please credit with a link to https://qeafivels.com/.

## 1. Overview

Kotlin-only core for running MediaTek `.vxp` packages inside an existing
Android UI. No Activity/View/Compose, no JNI/NDK/CMake/C/C++, no
`System.loadLibrary`. Guest ARM/Thumb code is interpreted by Kotlin.

```
VXP file -> AndroidVxpCore -> Kotlin backend (ARM/MRE or Flash Lite)
  -> FrameSnapshot RGB565 -> your controller -> existing UI
```

## 2. Versions

| Folder | Version | Artifact | Notes |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | First library split |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` | `dist/vxp-core-0.8.2.jar` | Latest: Thumb ALU, SMS sandbox, long regression |

Use **v0.8.2** for new integrations. See `VERSIONS.md`.

## 3. Backends

- `ELF32 ARM` → Kotlin ARM/Thumb + MRE: supported.
- Raw ARM + zlib (`RAW_ARM_ZLIB`, Gameloft) → Kotlin ARM/Thumb + MRE: supported.
- Flash Lite `FWS/CWS` → Android Kotlin + SWF/AVM1: compatibility-first.
- Unknown → detector returns `UNKNOWN`, no MREmu fallback.

## 4. Requirements

JDK 17+, Gradle 8.x, AGP `8.7.3`, Kotlin `2.0.21`,
Android `compileSdk 35` / `minSdk 23`, repos `google()` + `mavenCentral()`.
No NDK/CMake needed. Verify: `./verify_kotlin_only.sh`.

## 5. Installation

```kotlin
// settings.gradle.kts
include(":vxp-core")
include(":vxp-core-android")

// app module
dependencies {
    implementation(project(":vxp-core-android")) // pulls :vxp-core
}
```

Or use the prebuilt `dist/vxp-core-0.8.2.jar` as a file dependency.

## 6. Usage

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

Keys: `1 UP, 2 DOWN, 3 LEFT, 4 RIGHT, 5 OK, 6 LSK, 7 RSK, 10 CLEAR, 48-57 digits, 42 *, 35 #`.
Show boot text only before the first frame; after `onFrame()` the game
framebuffer is the only LCD source.

## 7. Validation

- Spider-Man (`RAW_ARM_ZLIB`): 23,524,795 insn / 425 frames / 440 events / 424 timers, 240x320 RGB565, no fault, `stubbedSymbols = []`.
- Crazy Taxi (`FLASH_LITE`): 176x220 @20fps, 35 frames, menu frame 4 → OK → frame 5 via real AVM1 action.

## 8. Safety

`vm_send_sms` always fails in sandbox; the core never sends SMS or billing traffic.

## 9. Links

- https://qeafivels.com/
- `../VERSIONS.md`, `../README.md`
- `VXP-Core-Library-v0.8.2/docs/INTEGRATE_EXISTING_UI.md`, `docs/TEST_RESULTS.md`
