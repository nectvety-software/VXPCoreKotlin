# Changelog

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
