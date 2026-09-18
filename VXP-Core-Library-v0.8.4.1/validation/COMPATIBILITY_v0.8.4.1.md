# VXP-Core v0.8.4.1 compatibility validation

This validation uses the clean-room Kotlin runtime only. User-supplied `.vxp` binaries are test inputs and are not included in the library archive.

| Workload | Backend | Result | Timed-run evidence |
|---|---|---|---|
| CatBoxMRE.vxp | ELF_ARM | PASS — gameplay framebuffer | 30,787,098 instructions; 218 frames; 122 events; 106 timer callbacks |
| RetroMRE.vxp | ELF_ARM | PASS — launcher/menu | 26,838,009 instructions; 39 frames; 50 events; 34 timer callbacks |
| Whisk3D.vxp | ELF_ARM | PASS — 3D scene | 33,296,269 instructions; 9 frames; 15 events |
| Spider-Man Daily Bugle | RAW_ARM_ZLIB | PASS — tested demo/menu path | 9,432,148 instructions; 83 frames; 98 events; `stubbedSymbols=[]` |
| CrazyTaxi_1.0.vxp | FLASH_LITE | PASS — menu → next scene | menu frame 4; OK executes 10 AVM1 actions; frame 5; `playing=true` |

## Clean-room observed symbol coverage

`tools/observed_vxp_surface.py` scans only printable `vm_*` names present inside supplied ELF VXP binaries and compares them with first-class handlers in `MreRuntime.kt`.

```text
CatBoxMRE.vxp: observed=60 first_class=60 missing=0
RetroMRE.vxp:  observed=32 first_class=32 missing=0
Whisk3D.vxp:   observed=42 first_class=42 missing=0
TOTAL unique_observed=76 covered=76 missing=0
```

This is corpus coverage only; it is not a claim of complete compatibility with every VXP ever produced.

## Remaining non-vendor ELF helpers

Some GCC/C++ ELF workloads still resolve generic toolchain finalization/unwind names through fallback paths, e.g. `__libc_fini`, `__register_frame_info`, `__gnu_Unwind_Find_exidx` and C++ EH helpers. The tested paths did not fault on these helpers.

## Runtime regression

`SystemGraphicsFileResourceRegression.kt` passes:

- SYSTEM callback aliases, tick and resolver.
- GRAPHICS image-buffer aliases and software mirror.
- FILE read/write/path-size via sandbox.
- RESOURCE init/load aliases and mapped bytes.
- `vm_sscanf` basic conversion.
