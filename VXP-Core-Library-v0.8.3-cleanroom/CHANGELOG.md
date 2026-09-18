# VXP Core Library v0.8.3 Clean-Room

- Rebased release on v0.8.3 Kotlin-only implementation.
- Removed SDK-derived catalog workflow and proprietary-SDK wording from source/docs.
- Added clean-room contribution/provenance policy.
- Kept compatibility handlers only where independently implemented from observed guest behavior and regression tests.
- No JNI/NDK/C/C++/third-party-emulator runtime.

# Changelog

## 0.8.3

- Sửa ELF `R_ARM_RELATIVE` khi relocation dùng symbol index 0; GOT/init-array của VXP GCC được relocate đúng.
- Hỗ trợ bootstrap `gcc_entry`: truyền resolver `vm_get_sym_entry`, `.init_array` và constructor count trước khi vào `vm_main`.
- Sửa Thumb `BLX register` để ghi LR và chuyển ARM/Thumb đúng.
- Sửa Thumb high-register PC semantics: đọc PC = current+4.
- Thêm Thumb `STRH/LDRH` immediate offset.
- Thêm ARM `CLZ`, `LDRD/STRD`, `UMULL/UMLAL/SMULL/SMLAL`.
- Sửa ARM Operand2 PC semantics: đọc PC = current+8, đặc biệt veneer `ADD pc,r12,pc`.
- Bổ sung `_vm_log_info/_vm_log_error` như logging API trực tiếp thay vì generic stub.
- Bổ sung `vm_find_first/vm_find_next/vm_find_close` thật trên filesystem sandbox, hỗ trợ wildcard `*`/`?`.
- Sửa `vm_ucs2_to_ascii`/charset convert: UCS2 NUL phải thành ASCII NUL, không được thành `?`; lỗi này từng biến `E:\demo.gb` thành `E:\demo.gb?`.
- Regression thực tế: CatBoxMRE vào gameplay; RetroMRE vào menu; Whisk3D render scene 3D.

## 0.8.2

- Hoàn thiện Thumb ALU register group: `ADC`, `SBC`, `ROR`, `NEG`, `CMN`.
- Thêm regression cho opcode Spider-Man `0x4241 = NEG r1,r0` và các ALU liên quan.
- Giữ các patch v0.8.1: PNG image loading/property, Thumb BLX immediate, App Manager count query, operator-code output.
- Chuyển `strtoi` từ generic stub sang API Kotlin thật.
- `vm_send_sms` trở thành sandboxed compatibility API trả failure; core tuyệt đối không gửi SMS thật.
- Đồng bộ version `0.8.2` giữa core và Android facade.
- Regression dài Spider-Man: >24 triệu guest instructions / >700 frames không CPU/memory fault trên uploaded demo VXP.
- Crazy Taxi Flash Lite: giữ menu frame 4 -> AVM1 Start -> frame 5.

## 0.8.0

- Chuyển thành thư viện lõi, bỏ app/demo UI khỏi deliverable.
- Kotlin-only ARM/Thumb + MRE + Flash Lite Android host.
