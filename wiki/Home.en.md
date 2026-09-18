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
| `VXP-Core-Library-v0.8.2/` | `0.8.2` | `dist/vxp-core-0.8.2.jar` | Thumb ALU, SMS sandbox, long regression |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` | `dist/vxp-core-0.8.3.jar` | ELF/GCC compat + 3 new ELF titles |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room | `dist/vxp-core-0.8.3-cleanroom.jar` | Latest: Kotlin-only rebase, provenance/compliance docs |

Use **v0.8.3-cleanroom** for new integrations and any distribution build. See `VERSIONS.md`.

## 3. Backends

- `ELF32 ARM` → Kotlin ARM/Thumb + MRE: supported, v0.8.3 adds GCC `gcc_entry` + `.init_array` bootstrap.
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

Or use the prebuilt `dist/vxp-core-0.8.3-cleanroom.jar` as a file dependency.

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
- v0.8.3 ELF titles: `CatBoxMRE` gameplay (218 frames / 28.3M instr), `RetroMRE` menu (18 frames / 11.8M instr, real `vm_find_*` + UCS2 NUL fix), `Whisk3D` 3D scene (9 frames / 33.3M instr).
- v0.8.3 CPU fixes: `R_ARM_RELATIVE` sym-0, `gcc_entry`/`.init_array`, Thumb BLX-reg + PC(+4) + STRH/LDRH, ARM CLZ + LDRD/STRD + long multiply, Operand2 PC(+8), `_vm_log_*`, `vm_find_*` wildcard.
- v0.8.3-cleanroom re-validation: CatBoxMRE 30.1M / 218f, RetroMRE 19.0M / 28f, Whisk3D 33.3M / 9f, Spider-Man 9.5M / 82f no unresolved symbols, Crazy Taxi 4→5. `verify_clean_room.sh` PASS.
- Clean-room policy: no SDK headers/libs/catalogs, no MREmu, no JNI/NDK/C/C++; see `docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`, `docs/COMMERCIAL_DISTRIBUTION_CHECKLIST.md`.

## 8. Safety

`vm_send_sms` always fails in sandbox; the core never sends SMS or billing traffic.

## 9. Links

- https://qeafivels.com/
- `../VERSIONS.md`, `../README.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/INTEGRATE_EXISTING_UI.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`
- `VXP-Core-Library-v0.8.3-cleanroom/validation/CLEANROOM_VALIDATION_v0.8.3.md`
