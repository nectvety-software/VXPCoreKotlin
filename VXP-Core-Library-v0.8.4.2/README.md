# VXP Core Library v0.8.4.2 — Clean-Room Kotlin Edition

Thư viện lõi `.vxp` độc lập để nhúng vào UI Android hiện có. Runtime là **Kotlin-only / clean-room**: không runtime emulator bên thứ ba, JNI, NDK, CMake, C/C++ guest runtime, proprietary vendor SDK headers/libraries, hay catalog sinh từ SDK proprietary.

> Đây là chính sách kỹ thuật/provenance, không phải tư vấn pháp lý.

## Mục tiêu v0.8.4.2

Bản này tập trung vào hai nhóm tương thích:

1. **SYSTEM / GRAPHICS** — đưa toàn bộ biến thể symbol đã quan sát trực tiếp trong corpus VXP hiện có thành first-class handler thay vì generic fallback.
2. **FILE_RESOURCE** — nâng hành vi file/path/resource, bao gồm alias thực tế giữa các toolchain, open/append an toàn, path-size, resource init/load và sandbox I/O.

Không dùng header/catalog vendor để tạo danh sách API. Tool `tools/observed_vxp_surface.py` chỉ quét các tên `vm_*` có mặt trực tiếp trong binary `.vxp` người dùng cung cấp và đối chiếu với handler Kotlin.

## Kiến trúc

- `vxp-core`: Kotlin/JVM trung lập — ELF32 ARM, RAW_ARM_ZLIB, ARM/Thumb interpreter, guest memory/heap/event loop, compatibility dispatcher, RGB565 graphics, sandbox filesystem/resources.
- `vxp-core-android`: Kotlin Android host — Flash Lite/SWF/AVM1 renderer, Android text rasterizer, RGB565 `FrameSnapshot -> Bitmap` adapter.

UI chỉ cần:

```kotlin
implementation(project(":vxp-core-android"))
```

`vxp-core-android` kéo theo `vxp-core`.

## Public API

```kotlin
val session = AndroidVxpCore.open(
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) {
            // RGB565 framebuffer thật của guest.
        }
    }
)

session.start()
session.keyDownLegacy(5) // OK
session.keyUpLegacy(5)
```

Core version:

```kotlin
VxpCoreLibrary.VERSION // 0.8.4.2
AndroidVxpCore.VERSION // 0.8.4.2
```

## SYSTEM compatibility v0.8.4.2

Các symbol/alias được đưa thành first-class handler dựa trên binary và trace của VXP đã test:

- `vm_get_tick` + `vm_get_tick_count`
- `vm_get_sym_entry`
- `vm_reg_key_callback` / `vm_reg_keyboard_callback`
- `vm_reg_system_event_callback` / `vm_reg_sysevt_callback`
- `vm_reg_touch_callback` / `vm_reg_pen_callback`
- `vm_get_removable_driver` / `vm_get_removeable_driver`
- `vm_sscanf` subset độc lập: `%d/%u/%x/%i/%o/%s/%c/%f/%n`
- disk free-space đọc từ sandbox drive thay vì giá trị hard-code.

## GRAPHICS compatibility v0.8.4.2

- `vm_graphic_get_screen_w/h` aliases.
- `vm_graphic_get_image_buffer` / `vm_graphic_get_img_buffer`.
- `vm_graphic_get_image_property` / `vm_graphic_get_img_property`.
- `vm_graphic_load_img` / `vm_graphic_load_image`.
- `vm_graphic_release_image`.
- `vm_graphic_get_font_height`.
- `vm_graphic_get_text_width`.
- `vm_graphic_create_layer_ex` được đăng ký first-class theo calling pattern đã quan sát.
- `vm_graphic_mirror` có software path thật khi đối số đầu là image/layer handle của core; calling pattern chưa xác định vẫn là safe no-op.
- Giữ RGB565 layer/canvas, clip, blit, PNG image, text và multi-layer flush từ v0.8.3.

## FILE_RESOURCE compatibility v0.8.4.2

Bản 0.8.4.2 giữ toàn bộ hardening open/read/write/seek của 0.8.4.1 và mở rộng thêm:

- `vm_file_copy`: copy file trong sandbox C:/E:, hỗ trợ cross-drive, preserve timestamp/guest attributes, same-path no-op và từ chối copy đè directory/read-only destination.
- `vm_file_rename`: same-path no-op, chặn directory tự move vào cây con của chính nó, remap metadata khi rename và hỗ trợ cross-drive khi host filesystem cho phép.
- `vm_file_tell` và `vm_file_is_eof`: dùng chung cursor thật của `RandomAccessFile`.
- `vm_file_get_modify_time`: compatibility profile dùng Unix seconds; hỗ trợ out-parameter hoặc direct-return fallback.
- `vm_get_default_folder_path`, `vm_get_filename`, `vm_get_path`: chỉ xử lý guest path, không bao giờ trả host path Android/JVM.
- Attribute model host-side ổn định giữa Linux/Android: READ_ONLY/HIDDEN/SYSTEM/ARCHIVE không phụ thuộc hoàn toàn vào permission semantics của host.
- `vm_get_resource_offset` và `vm_get_resource_offset_from_file`.
- `vm_load_resource_from_file`: hỗ trợ named resource archive và raw `[offset,size)` slice từ file sandbox; dữ liệu được copy vào guest heap, không mmap host file.
- `vm_resource_get_data_from_file`: bounded copy trực tiếp từ external resource file vào guest memory.
- `vm_res_delete` / `vm_res_deinit`: quản lý lifetime của resource được cấp phát từ external file.
- resource-from-file giới hạn 32 MiB, kiểm tra path/range/pointer trước side effect và vẫn bị khóa trong C:/E: sandbox.
- Các ABI mới chưa xuất hiện trong corpus hiện tại được đánh dấu **synthetic compatibility profile**; không được tính là corpus-confirmed chỉ vì regression tổng hợp PASS.

Regression tập trung xem `validation/FILE_RESOURCE_v0.8.4.2.md`.

## Clean-room compatibility surface

Chạy:

```bash
python3 tools/observed_vxp_surface.py \
  CatBoxMRE.vxp RetroMRE.vxp Whisk3D.vxp
```

Regression hiện tại:

```text
CatBoxMRE : observed 60 / first-class 60 / missing 0
RetroMRE  : observed 32 / first-class 32 / missing 0
Whisk3D   : observed 42 / first-class 42 / missing 0
Unique    : observed 76 / covered 76 / missing 0
```

Đây là **corpus coverage**, không phải tuyên bố mọi API của nền tảng đều đã hoàn chỉnh.

## Regression VXP

- CatBoxMRE: ELF_ARM, gameplay 240×320 ổn định.
- RetroMRE: ELF_ARM, menu/launcher + file enumeration ổn định.
- Whisk3D: ELF_ARM, scene 3D 240×320 ổn định.
- Spider-Man Daily Bugle: RAW_ARM_ZLIB, title/menu/demo flow không CPU/memory fault trên path test.
- CrazyTaxi: FLASH_LITE, menu frame 4 → OK/AVM1 → frame 5.

Xem `validation/COMPATIBILITY_v0.8.4.2.md`.

## Ranh giới clean-room

- Không bundle commercial `.vxp` trong release.
- Không dùng/copy vendor SDK headers, libraries, docs dump hoặc generated SDK catalog.
- Không dùng/copy vendor SDK headers, libraries, docs dump hoặc generated SDK catalog; không dùng source của emulator khác.
- Compatibility patch phải dựa trên binary metadata/import names, guest trace, I/O regression hoặc public non-proprietary specs.

Xem `docs/CLEAN_ROOM_POLICY.md` và `docs/PROVENANCE.md`.
