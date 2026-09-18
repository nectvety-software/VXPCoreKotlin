# Changelog

## 0.8.4.1 — SYSTEM / GRAPHICS / FILE_RESOURCE clean-room pass

### SYSTEM

- Đưa `vm_get_tick` thành first-class alias của monotonic guest tick.
- Thêm `vm_get_sym_entry` resolver callable cho GCC-built VXP.
- Thêm first-class callback aliases quan sát trong VXP: `vm_reg_key_callback`, `vm_reg_system_event_callback`, `vm_reg_touch_callback`.
- Thêm spelling alias `vm_get_removable_driver` bên cạnh `vm_get_removeable_driver`.
- Thêm `vm_sscanf` implementation Kotlin độc lập cho nhóm conversion cơ bản.
- `vm_get_disk_free_space` lấy dung lượng usable của C:/E: sandbox thay vì hằng số giả.

### GRAPHICS

- Thêm first-class aliases `vm_graphic_get_screen_w/h`.
- Thêm image buffer aliases `vm_graphic_get_image_buffer` / `vm_graphic_get_img_buffer`.
- Thêm image property alias `vm_graphic_get_image_property`.
- Thêm `vm_graphic_load_img`, `vm_graphic_release_image`.
- Thêm `vm_graphic_get_font_height`, `vm_graphic_get_text_width`.
- Đưa `vm_graphic_create_layer_ex` ra khỏi generic fallback và đăng ký trực tiếp.
- `vm_graphic_mirror` có software mirror thật cho layer/canvas handle do core sở hữu, đồng thời giữ safe no-op cho calling pattern chưa xác định.

### FILE_RESOURCE

- Thêm `vm_file_get_file_size` dual compatibility: handle hoặc UCS2 path.
- Cải thiện open semantics: read-only không tạo directory; append seek tới EOF.
- Thêm first-class `vm_resource_init` và `vm_res_load` cho các tên alias đã quan sát trong ELF VXP.
- Giữ và regression lại file read/write/seek/attributes/find-first/next/close và resource blob mapping.

### Clean-room diagnostics

- Thêm `tools/observed_vxp_surface.py`; tool chỉ đọc binary VXP người dùng cung cấp và runtime source, không cần vendor SDK.
- Corpus ELF hiện tại: 76 unique observed `vm_*` names, 76 first-class handlers, 0 missing.
- Thêm `SystemGraphicsFileResourceRegression.kt`.

### Regression

- CatBoxMRE: PASS — 218 framebuffer frames trong timed run cuối.
- RetroMRE: PASS — 39 frames, menu/launcher ổn định.
- Whisk3D: PASS — 9 frames, scene 3D ổn định.
- Spider-Man Daily Bugle: PASS — 83 frames, `stubbedSymbols=[]` trên test path.
- CrazyTaxi: PASS — frame 4 menu → OK → AVM1 → frame 5.
- Clean-room/Kotlin-only checks: PASS.

## 0.8.3 Clean-Room

- Rebased release on independently written Kotlin implementation.
- Removed SDK-derived catalog workflow.
- ELF/GCC bootstrap, ARM/Thumb compatibility, free-list heap, RGB565 graphics, sandbox filesystem/resources.
