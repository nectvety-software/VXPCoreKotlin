# VXP-Core v0.8.4.3 compatibility validation

v0.8.4.3 is an AUDIO-focused release built on the v0.8.4.2 clean-room Kotlin runtime.

## JVM regression suite

```text
ARM CLZ: PASS
ARM LDRD/STRD: PASS
ARM long multiply: PASS
ARM Operand2 PC semantics: PASS
AUDIO regression: PASS
Pure Kotlin PNG decoder: PASS
SYSTEM/GRAPHICS/FILE_RESOURCE regression: PASS
Thumb ALU: PASS
Thumb BLX register: PASS
Thumb BLX immediate: PASS
Thumb STRH/LDRH immediate: PASS
Thumb PC semantics: PASS
```

See `regression_v0.8.4.3.txt` for the captured output.

## Observed VXP surface

```text
CatBoxMRE.vxp: observed=60 first_class=60 missing=0
RetroMRE.vxp:  observed=32 first_class=32 missing=0
Whisk3D.vxp:   observed=42 first_class=42 missing=0
TOTAL unique_observed=76 covered=76 missing=0
```

This is corpus coverage only.

## Final ELF smoke after AUDIO patch

| Workload | Backend | Instructions | Frames | Events | Result |
|---|---|---:|---:|---:|---|
| CatBoxMRE | ELF_ARM | 9,001,687 | 160 | 79 | PASS — 240x320 framebuffer |
| RetroMRE | ELF_ARM | 64,572,356 | 91 | 92 | PASS — 240x320 framebuffer |
| Whisk3D | ELF_ARM | 8,148,936 | 2 | 2 | PASS — 240x320 framebuffer |

The frame counts are timed-run observations, not fixed expected counts. Framebuffer hashes remained stable for the captured terminal frames in this run.

## Remaining generic toolchain helpers

Some ELF workloads still resolve generic GCC/C++ finalization/unwind helpers through fallback paths. They are not AUDIO APIs and are unchanged by this release.

## Clean-room status

- Kotlin/JVM guest runtime only.
- Android audio integration lives only in `vxp-core-android`.
- No commercial VXP binary is bundled in the release archive.
- No proprietary vendor header/library/catalog is required to build the core.
