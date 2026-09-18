# VXP-Core v0.8.4.2 — FILE_RESOURCE focused validation

This release remains a clean-room Kotlin-only runtime. No proprietary vendor SDK header/library/catalog is required to build it.

## Implemented compatibility surface

- open/read/write/seek hardening inherited from v0.8.4.1.
- directory/path helpers: `vm_get_default_folder_path`, `vm_get_filename`, `vm_get_path`.
- file state helpers: `vm_file_tell`, `vm_file_is_eof`, `vm_file_get_modify_time`.
- copy/rename edge handling with guest attribute metadata migration.
- external resource pack access: `vm_get_resource_offset_from_file`, `vm_load_resource_from_file`, `vm_resource_get_data_from_file`.
- resource lifetime: `vm_res_delete`, `vm_res_deinit`.

The path/resource-from-file ABI additions are synthetic compatibility profiles until a user-supplied corpus exercises those exact call shapes. They are first-class handlers, but they are not counted as corpus-confirmed behavior.

## JVM regression

`SystemGraphicsFileResourceRegression.kt`: PASS.

Coverage includes partial read/EOF, tell, append-after-seek, invalid ranges, path traversal, filename/parent/default path, cross-drive rename, read-only/hidden/system/archive attributes, modification time, named external resource packs, raw resource slices, bounds checks and resource deallocation.

## CPU/image regressions

PASS: Thumb ALU, Thumb STRH/LDRH immediate, Thumb BLX register/immediate, Thumb PC semantics, ARM CLZ, ARM LDRD/STRD, ARM long multiply, ARM Operand2 PC semantics, pure Kotlin PNG decode.

## Corpus smoke

Short timed smoke after the FILE_RESOURCE patch:

| Workload | Backend | Instructions | Frames | Result |
|---|---|---:|---:|---|
| CatBoxMRE.vxp | ELF_ARM | 3,717,190 | 12 | PASS |
| RetroMRE.vxp | ELF_ARM | 2,860,810 | 4 | PASS |
| Whisk3D.vxp | ELF_ARM | 4,129,624 | 1 | PASS |

All three produced a 240×320 framebuffer and stopped only because the short host timeout expired.

## Observed symbol surface

```text
CatBoxMRE.vxp: observed=60 first_class=60 missing=0
RetroMRE.vxp: observed=32 first_class=32 missing=0
Whisk3D.vxp: observed=42 first_class=42 missing=0
TOTAL unique_observed=76 covered=76 missing=0
```

This is corpus coverage, not a claim of complete MRE compatibility.
