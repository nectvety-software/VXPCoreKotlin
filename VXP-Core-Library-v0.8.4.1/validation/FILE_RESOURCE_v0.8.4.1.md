# VXP-Core v0.8.4.1 — FILE_RESOURCE focused validation

This pass keeps the clean-room Kotlin runtime and concentrates on guest file/resource behavior. No proprietary MRE SDK headers, libraries or generated SDK catalog are required.

## File I/O behavior covered

- `vm_file_open`
  - explicit read/write capability derived from the guest mode bits;
  - read-only open never creates the target or parent directory;
  - directory targets and sandbox traversal (`..`) are rejected;
  - append mode starts at EOF and remains append-only for every subsequent write.
- `vm_file_read`
  - validates handle, signed length, guest destination and optional bytes-read pointer before I/O;
  - partial reads return the exact byte count;
  - EOF is success with zero bytes;
  - zero-byte calls still require a valid readable handle.
- `vm_file_write`
  - validates handle, signed length, guest source and optional bytes-written pointer before side effects;
  - read-only handles fail;
  - append-after-seek still writes at EOF.
- `vm_file_seek`
  - SET/CUR/END supported;
  - seeking after EOF is allowed;
  - negative and arithmetic-overflow targets fail instead of being clamped to zero.
- Per-call file I/O is capped at 32 MiB to prevent malformed guest requests from forcing unbounded host allocations.

## Resource loading behavior covered

- `vm_resource_init`, `vm_res_init`, `vm_load_resource`, and `vm_res_load` remain first-class handlers.
- Named lookup validates entry offset/size against the currently installed raw resource blob before returning a guest pointer.
- ASCII resource names remain exact and case-sensitive; an obvious UCS2 buffer is accepted as a compatibility fallback.
- Invalid optional size-output pointers are rejected before returning a resource pointer.
- `vm_resource_get_data` checks the full `[offset, offset + size)` range and supports a zero-byte probe.
- ELF `.vm_res` parsing now terminates cleanly on malformed directory data, removes duplicate names and converts absolute file offsets to `RESOURCE_BASE`-relative guest pointers.
- Replacing the installed resource archive with a smaller blob clears the old mapped tail bytes.

## Focused API regression

`tests/jvm/SystemGraphicsFileResourceRegression.kt` now exercises:

- create/write/close/reopen/read;
- partial read + EOF;
- SET/END seek and rejected negative CUR seek;
- negative read/write length;
- read-only write rejection;
- zero-byte invalid-handle rejection;
- append-after-seek semantics;
- invalid output pointer with no file side effect;
- missing read-only path and traversal rejection;
- ASCII + UCS2 named resource lookup;
- missing resource + invalid size pointer;
- raw resource range checks;
- ELF `.vm_res` load and mapped bytes;
- stale resource-tail clearing.

Result:

```text
[OK] v0.8.4.1 SYSTEM/GRAPHICS/FILE_RESOURCE regression passed
```

## Core regression suite after FILE_RESOURCE changes

All existing JVM CPU/image regressions passed:

```text
[OK] ARM CLZ regression
[OK] ARM LDRD/STRD regression
[OK] ARM long multiply regression
[OK] ARM Operand2 PC regression
[OK] pure Kotlin PNG decode
[OK] Thumb ALU regression
[OK] Thumb BLX register regression
[OK] Thumb BLX immediate regression
[OK] Thumb immediate STRH/LDRH regression
[OK] Thumb high-register PC semantics regression
```

## Representative ELF VXP smoke run after patch

Each file below is user-supplied test input and is not bundled in this release archive. A 6-second library smoke run produced frames without a CPU/memory fault:

| Workload | Backend | Instructions | Frames | Events | Result |
|---|---|---:|---:|---:|---|
| CatBoxMRE.vxp | ELF_ARM | 9,988,010 | 186 | 92 | PASS |
| RetroMRE.vxp | ELF_ARM | 64,572,356 | 91 | 92 | PASS |
| Whisk3D.vxp | ELF_ARM | 8,148,936 | 2 | 2 | PASS |

Generic GCC/C++ finalization/unwind helpers may still appear in `stubbedSymbols`; this FILE_RESOURCE pass does not change those non-vendor toolchain fallbacks.

## Observed clean-room symbol surface

```text
CatBoxMRE.vxp: observed=60 first_class=60 missing=0
RetroMRE.vxp: observed=32 first_class=32 missing=0
Whisk3D.vxp: observed=42 first_class=42 missing=0
TOTAL unique_observed=76 covered=76 missing=0
```

## Provenance checks

```text
OK: clean-room Kotlin-only source checks passed.
OK: vxp-core + vxp-core-android are Kotlin-only (no JNI/NDK/C/C++).
```
