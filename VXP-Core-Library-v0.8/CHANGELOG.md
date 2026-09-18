# Changelog

## 0.8.0

- Chuyển thành thư viện lõi, bỏ toàn bộ app/demo UI khỏi deliverable.
- `vxp-core`: Kotlin/JVM thuần cho ELF ARM và RAW_ARM_ZLIB.
- `vxp-core-android`: Kotlin Android host, không JNI/NDK/C++.
- Public `VxpCoreLibrary`, `VxpSession`, `AndroidVxpCore`, `AndroidVxpSession`.
- Frame output duy nhất là `FrameSnapshot` RGB565.
- Legacy Nokia key helpers để nối trực tiếp controller UI hiện có.
- Port Flash Lite renderer khỏi AWT/Swing sang `android.graphics`.
- Crazy Taxi: Shape/PlaceObject2/RemoveObject2/JPEG3/timeline/ButtonCondAction/AVM1 Start hoạt động.
- Spider-Man: regression RAW_ARM_ZLIB chạy 6 giây không fault.
- `vm_malloc(0)` không còn bị ghi nhận là allocation failure.
- Đăng ký trực tiếp background-audio suspend/resume compatibility calls.
- Đăng ký `vm_graphic_mirror` compatibility no-op để title dùng API này không rơi vào unknown stub path.
