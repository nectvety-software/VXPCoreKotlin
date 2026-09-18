# VXP Core v0.8.3 Clean-Room validation

This validation was run after rebasing on the independently written v0.8.3 Kotlin implementation and removing the SDK-derived catalog workflow.

## Source/build checks

- `verify_clean_room.sh`: PASS
- `vxp-core` Kotlin/JVM compilation: PASS
- No C/C++/JNI/NDK runtime files in package: PASS
- No proprietary SDK-derived catalog/module in package: PASS

## CPU/image regressions

- Thumb ADC/SBC/ROR/NEG/CMN: PASS
- Thumb BLX register: PASS
- Thumb BLX immediate: PASS
- Thumb STRH/LDRH immediate: PASS
- Thumb PC semantics: PASS
- ARM CLZ: PASS
- ARM LDRD/STRD: PASS
- ARM long multiply: PASS
- ARM Operand2 PC semantics: PASS
- Pure Kotlin PNG decode: PASS

## Representative VXP runs

User-supplied binaries were used for testing only and are not included in the source archive.

| Workload | Backend | Result |
|---|---|---|
| CatBoxMRE | ELF_ARM | 30,163,160 guest instructions, 218 framebuffer frames, stable timed run |
| RetroMRE | ELF_ARM | 18,960,170 guest instructions, 28 framebuffer frames, stable timed run |
| Whisk3D | ELF_ARM | 33,296,269 guest instructions, 9 framebuffer frames, stable timed run |
| The Amazing Spider-Man - The Daily Bugle | RAW_ARM_ZLIB | 9,480,936 guest instructions, 82 framebuffer frames, no unresolved compatibility symbols on tested path |
| CrazyTaxi | FLASH_LITE | menu frame 4; OK executes AVM1 actions and advances to frame 5 |

ELF workloads still report a small number of GCC/C++ runtime/unwind symbols on some paths; these are not proprietary vendor SDK APIs and did not fault during the timed runs above.
