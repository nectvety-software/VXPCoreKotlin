# VXP Core Library v0.8.3 — Test Results

## The Amazing Spider-Man - The Daily Bugle

User-supplied binary; không đóng trong thư viện.

Regression chính:

- backend: `RAW_ARM_ZLIB`
- instructions: `23,524,795`
- frames: `425`
- events: `440`
- timer callbacks: `424`
- CPU/memory fault: none
- stubbed symbols on this path: `[]`
- framebuffer: 240×320 RGB565

Các lỗi đã được tìm và sửa trong chuỗi test: PNG load/property, Thumb BLX immediate, `vm_appmgr_get_installed_list`, `vm_query_operator_code`, Thumb ALU `NEG/ADC/SBC/ROR/CMN`.

Binary này có chuỗi `GL_Demo`, `UNLOCK`, SMS billing. Core chỉ mô phỏng runtime; `vm_send_sms` trả failure và không gửi SMS thật/không bypass billing.

## CrazyTaxi_1.0.vxp

- backend: `FLASH_LITE`
- stage: 176×220
- FPS: 20
- frames: 35
- shapes: 25
- buttons: 7
- startup menu: frame 4
- `OK`: AVM1 ButtonCondAction -> `GoToLabel("start")` -> frame 5
- actions: 10
- input events: 1
- playing: true

## CPU/image regression

- Thumb ALU ADC/SBC/ROR/NEG/CMN: PASS
- Thumb BLX immediate `F001 E9A4`: PASS
- pure Kotlin PNG 1×1 RGB565 decode: PASS
- Kotlin-only source verification: PASS

## v0.8.3 — ELF/GCC compatibility regression

- CatBoxMRE: PASS, gameplay framebuffer 240×320, 218 frames.
- RetroMRE: PASS, Pixel Launcher menu 240×320, 18 frames.
- Whisk3D: PASS, 3D scene 240×320, 9 frames.
- Spider-Man regression after CPU changes: PASS, 78 frames, 9,425,458 instructions, no stubs on tested path.
- Crazy Taxi Flash Lite regression: PASS, menu frame 4 → OK → frame 5.

CPU regressions pass for ARM PC Operand2, ARM long multiply, ARM LDRD/STRD,
ARM CLZ, Thumb BLX register, Thumb immediate halfword load/store, and Thumb PC semantics.
