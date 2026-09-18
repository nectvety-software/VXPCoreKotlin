# VXP-Core Library (Kotlin-only)

A Kotlin-only core library for running MediaTek `.vxp` packages inside an existing Android UI.
No `Activity / View / Compose`, no `JNI / NDK / CMake / C / C++`, no `System.loadLibrary`.
Guest ARM/Thumb code in `.vxp` is data executed by a Kotlin interpreter, never as Android native code.

```
VXP file
 -> AndroidVxpCore
 -> Kotlin backend (ARM/MRE or Flash Lite)
 -> FrameSnapshot RGB565
 -> your controller
 -> existing EmulatorScreenCanvas / UI
```

## Credits

Maintained by **DOXUANHOP**.

- Website: https://qeafivels.com/
- Namespace: `vn.com.doxuanhop.vxpcore.android`
- If you use this library in an app or article, please credit with a link to https://qeafivels.com/.

## Versions in this repo

| Folder | Version (`VxpCoreLibrary.VERSION` / `AndroidVxpCore.VERSION`) | Artifact | Notes |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | First library split, UI removed from deliverable |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` (core + Android facade in sync) | `dist/vxp-core-0.8.2.jar` | Latest, Thumb ALU + SMS sandbox + long regression |

See `VERSIONS.md` for the full Vietnamese version matrix, and
`VXP-Core-Library-v0.8.2/CHANGELOG.md` for details.
Use `v0.8.2` for all new integrations.

## Supported backends

| VXP type | Backend | Status |
|---|---|---|
| `ELF32 ARM` | Kotlin ARM/Thumb + MRE | Supported |
| Gameloft raw ARM + zlib (`RAW_ARM_ZLIB`) | Kotlin ARM/Thumb + MRE | Supported |
| Flash Lite `FWS / CWS` | Android Kotlin + SWF/AVM1 (`android.graphics`) | Compatibility-first |
| Unknown / proprietary | Detector | Returns `UNKNOWN`, no MREmu fallback |

Validated:

- *The Amazing Spider-Man - The Daily Bugle* (`RAW_ARM_ZLIB`): 23,524,795 instructions / 425 frames / 440 events / 424 timers, 240x320 RGB565, no CPU/memory fault, `stubbedSymbols = []` (v0.8.2).
- *CrazyTaxi_1.0.vxp* (`FLASH_LITE`): stage 176x220, 20 FPS, 35 frames, 25 shapes / 12 sprites / 5 JPEG3 / 7 buttons, startup menu frame 4, `OK` runs a real AVM1 `ButtonCondAction` to frame 5.

User-supplied commercial binaries are test-only and are not bundled in the ZIP/JAR.

## Requirements / related installs

| Tool | Version required | Notes |
|---|---|---|
| JDK | 17+ (`jvmToolchain(17)`, `jvmTarget = "17"`) | `vxp-core` is pure Kotlin/JVM |
| Gradle | 8.x recommended | Wrapper not bundled; use your Android project Gradle |
| Android Gradle Plugin | `8.7.3` | See root `build.gradle.kts` |
| Kotlin | `2.0.21` (`kotlin.jvm` / `kotlin.android`) | See root `build.gradle.kts` |
| Android SDK | `compileSdk 35`, `minSdk 23` | See `vxp-core-android/build.gradle.kts` |
| Repositories | `google()`, `mavenCentral()` | `FAIL_ON_PROJECT_REPOS` mode |
| GitHub Desktop (optional) | any recent | Only for cloning/publishing this repo |

No NDK, CMake, `abiFilters`, `jniLibs`, or `externalNativeBuild` is needed.
Verify with:

```bash
./verify_kotlin_only.sh
# OK: vxp-core + vxp-core-android are Kotlin-only (no JNI/NDK/C/C++).
```

## Installation

1. Copy `vxp-core/` and `vxp-core-android/` from `VXP-Core-Library-v0.8.2/` into your Android project.
2. Register modules in `settings.gradle.kts`:

```kotlin
include(":vxp-core")
include(":vxp-core-android")
```

3. Depend only on the Android host (it pulls `vxp-core` automatically):

```kotlin
dependencies {
    implementation(project(":vxp-core-android"))
}
```

Or drop in the prebuilt artifact:

```kotlin
dependencies {
    implementation(files("libs/vxp-core-0.8.2.jar"))
}
```

## Usage

```kotlin
val session = AndroidVxpCore.open(
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) {
            // frame.pixels = RGB565; render into your existing UI.
            // Callbacks may arrive on a worker thread.
        }
        override fun onState(state: VxpSessionState) {
            // Starting / Running / Stopped / Failed
        }
    }
)
session.start()

// Nokia legacy keys from your existing controller:
session.keyDownLegacy(5) // OK
session.keyUpLegacy(5)
session.keyDownLegacy(6) // LSK
session.keyDownLegacy(7) // RSK

// Touch (main path for MRE ARM backend):
session.penDown(x, y)
session.penMove(x, y)
session.penUp(x, y)

session.stop()
session.close()
```

Legacy key map: `1 UP, 2 DOWN, 3 LEFT, 4 RIGHT, 5 OK, 6 LSK, 7 RSK, 10 CLEAR, 48-57 digits, 42 *, 35 #`.

Important: show boot text only before the first frame. Once `onFrame()` fires while `Running`,
the game framebuffer is the only LCD source. Do not overlay instruction counters on it.
The old path `MreNativeVxpApp -> NativeVxpBridge -> JNI -> vxp_runner.cpp` is removed.

See `VXP-Core-Library-v0.8.2/docs/INTEGRATE_EXISTING_UI.md`.

## Project layout (v0.8.2)

```
VXP-Core-Library-v0.8.2/
  vxp-core/            # ARM CPU, ELF, MRE runtime, heap, file, graphics, PNG decoder
  vxp-core-android/    # AndroidVxpCore session, Flash Lite backend, Rgb565BitmapAdapter, text rasterizer
  tests/jvm/           # SpiderMan, Thumb ALU/BLX, PNG decoder regressions
  tests/android-graphics-jvm/  # Crazy Taxi backend test with android.graphics stubs
  validation/          # real-run logs, frame sha256, PNG screenshots
  docs/                # TEST_RESULTS.md, INTEGRATE_EXISTING_UI.md
  dist/vxp-core-0.8.2.jar
```

## Safety

`vm_send_sms` is a sandboxed compatibility API that always returns failure.
The core never sends real SMS, Intents, or network billing requests.
Strings such as `GL_Demo / UNLOCK / SMS` in test binaries are runtime data only.

## Links

- Project site: https://qeafivels.com/
- Version matrix: `./VERSIONS.md`
- v0.8.2 README: `./VXP-Core-Library-v0.8.2/README.md`
- Integration guide: `./VXP-Core-Library-v0.8.2/docs/INTEGRATE_EXISTING_UI.md`
- Test results: `./VXP-Core-Library-v0.8.2/docs/TEST_RESULTS.md`
