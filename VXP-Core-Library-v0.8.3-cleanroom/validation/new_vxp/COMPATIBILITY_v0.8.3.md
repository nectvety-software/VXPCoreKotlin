# VXP-Core Library v0.8.3 — new VXP compatibility

Test inputs are user-provided and are **not** bundled in this library archive.

| File | Backend | Result | Frames | Notes |
|---|---|---|---:|---|
| CatBoxMRE.vxp | ELF_ARM | PASS — gameplay rendered | 212 | 240×320, timers/input/event loop active |
| RetroMRE.vxp | ELF_ARM | PASS — menu rendered | 36 | `RETRO MRE / PIXEL LAUNCHER`, keypad input path active |
| Whisk3D.vxp | ELF_ARM | PASS — 3D scene rendered | 9 | cube/sphere/cone scene, text and file APIs active |

## Core fixes required by these titles

- ELF `R_ARM_RELATIVE` with symbol index 0.
- GCC `gcc_entry` bootstrap and `.init_array` constructors.
- Thumb `BLX register` LR/interworking semantics.
- Thumb PC reads as current instruction + 4 in high-register operations.
- Thumb immediate `STRH/LDRH`.
- ARM `CLZ`.
- ARM doubleword `LDRD/STRD`.
- ARM long multiply `UMULL/UMLAL/SMULL/SMLAL`.
- ARM Operand2 reads of PC as current instruction + 8.

## Remaining imported runtime symbols

These files may still reference GCC/C++ unwind/finalization symbols such as
`__register_frame_info`, `__deregister_frame_info`, `__fini_array_start/end`,
`__libc_fini`, or C++ EH helpers. The tested execution paths do not require
those helpers and no CPU/memory fault occurred during the compatibility runs.

## RetroMRE filesystem enumeration

Regression bổ sung đặt một file test `demo.gb` trong sandbox `E:`. `vm_find_first("E:\\*")` trả handle hợp lệ, `vm_ucs2_to_ascii` giữ NUL đúng, `vm_file_get_attributes("E:\\demo.gb")` nhận file, và RetroMRE hiển thị `DEMO.GB` trong danh sách. File ROM test không nằm trong ZIP.
