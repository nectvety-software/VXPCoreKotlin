# VXP-Core Library v0.8.3 — Compatibility Matrix

Các file `.vxp` dưới đây là input do người dùng cung cấp; binary game/app không được đóng gói trong thư viện.

| VXP | Backend | Kết quả | Frame / runtime kiểm thử | Ghi chú |
|---|---|---|---:|---|
| CrazyTaxi_1.0.vxp | FLASH_LITE | PASS — menu → gameplay scene | menu 4 → frame 5 | AVM1 `ButtonCondAction`, `GoToLabel("start")`, `Play` |
| The Amazing Spider-Man - The Daily Bugle | RAW_ARM_ZLIB | PASS — title/menu/demo flow | 78 frames / 9.4M instr | no CPU/memory fault, `stubbedSymbols=[]` trên regression v0.8.3 |
| CatBoxMRE.vxp | ELF_ARM | PASS — gameplay | 218 frames / 28.3M instr | input, timer, resource, file và graphics active |
| RetroMRE.vxp | ELF_ARM | PASS — menu | 18 frames / 11.8M instr | `RETRO MRE / PIXEL LAUNCHER`; file enumeration đã test bằng `demo.gb` trong sandbox E: |
| Whisk3D.vxp | ELF_ARM | PASS — 3D scene | 9 frames / 33.3M instr | render sphere/cube/cone scene 240×320 |

## Các lỗi lõi được phát hiện và sửa nhờ 3 ELF VXP mới

1. `R_ARM_RELATIVE` với symbol index 0 từng bị bỏ qua.
2. VXP GCC cần chạy `gcc_entry` và `.init_array`, không được gọi thẳng `vm_main`.
3. Thumb `BLX register` phải ghi LR và switch state đúng.
4. Thumb high-register đọc PC theo `instruction + 4`.
5. Thumb immediate `STRH/LDRH` chưa được decode.
6. ARM `CLZ` bị rơi nhầm vào generic data-processing.
7. ARM `LDRD/STRD` chưa có.
8. ARM long multiply `UMULL/UMLAL/SMULL/SMLAL` chưa có.
9. ARM Operand2 đọc r15 phải dùng `instruction + 8`; sai 4 byte làm hỏng GCC `__aeabi_*` veneers.

## Remaining linked GCC/C++ helpers

Một số ELF vẫn import các symbol finalization/unwind như `__fini_array_start/end`,
`__register_frame_info`, `__deregister_frame_info`, `__libc_fini` hoặc C++ EH helpers.
Các execution path đã test không gọi tới phần exception/finalization này và không phát sinh CPU/memory fault.

- RetroMRE regression also validates real sandbox `vm_find_first/next/close` and UCS2→ASCII NUL termination.
