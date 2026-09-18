# VXP-Core v0.8.4.4 compatibility validation

## Clean-room runtime

- Kotlin/JVM guest runtime only.
- No JNI/NDK/C/C++ guest execution.
- No proprietary vendor headers/libraries are bundled.
- Android audio code is isolated in `vxp-core-android`.

## Observed VXP surface

```text
CatBoxMRE.vxp: observed=60 first_class=60 missing=0
RetroMRE.vxp:  observed=32 first_class=32 missing=0
Whisk3D.vxp:   observed=42 first_class=42 missing=0
TOTAL unique_observed=76 covered=76 missing=0
```

This is corpus coverage, not a claim of universal VXP compatibility.

## Corpus smoke after v0.8.4.4 audio changes

| Workload | Backend | Frames | Framebuffer | Result |
|---|---|---:|---|---|
| CatBoxMRE | ELF_ARM | 186 | 240×320 | PASS |
| RetroMRE | ELF_ARM | 91 | 240×320 | PASS |
| Whisk3D | ELF_ARM | 2 | 240×320 | PASS |

The framebuffer hashes on the tested paths remain unchanged from the previous expected outputs.

## Regression families

- AUDIO playback accuracy: PASS.
- SYSTEM/GRAPHICS/FILE_RESOURCE: PASS.
- ARM CLZ/LDRD/STRD/long multiply/PC operand: PASS.
- Thumb ALU/BLX/halfword/PC semantics: PASS.
- Pure Kotlin PNG decode: PASS.
- Android audio host/facade syntax check against API stubs: PASS.
