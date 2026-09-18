# Tích hợp vào UI hiện có

## 1. Thêm module

Copy hai thư mục `vxp-core/` và `vxp-core-android/` vào project Android hiện tại, rồi thêm vào `settings.gradle.kts`:

```kotlin
include(":vxp-core")
include(":vxp-core-android")
```

Trong module app:

```kotlin
dependencies {
    implementation(project(":vxp-core-android"))
}
```

Không cần `externalNativeBuild`, `ndk`, `abiFilters`, `jniLibs` hoặc `CMakeLists.txt` cho VXP core mới.

## 2. Adapter controller tối thiểu

```kotlin
private var vxpSession: AndroidVxpSession? = null
private val bitmapAdapter = Rgb565BitmapAdapter()

fun loadVxp(bytes: ByteArray, name: String) {
    vxpSession?.close()

    vxpSession = AndroidVxpCore.open(
        bytes = bytes,
        fileName = name,
        storageRoot = File(context.filesDir, "vxp_runtime"),
        listener = object : VxpCoreListener {
            override fun onFrame(frame: FrameSnapshot) {
                // Callback có thể đến từ worker thread.
                val bitmap = bitmapAdapter.update(frame)
                mainHandler.post {
                    currentBitmap.value = bitmap
                    isBooting.value = false
                    isRunning.value = true
                }
            }

            override fun onState(state: VxpSessionState) {
                mainHandler.post {
                    when (state) {
                        is VxpSessionState.Failed -> showError(state.message)
                        else -> Unit
                    }
                }
            }
        },
        textRasterizer = AndroidTextRasterizer()
    ).also { it.start() }
}
```

## 3. Phím Nokia 225 UI hiện tại

Nếu UI hiện có đã chuyển shell code thành legacy 1..7 thì gửi thẳng:

```kotlin
fun onKeyDown(legacy: Int) = vxpSession?.keyDownLegacy(legacy)
fun onKeyUp(legacy: Int) = vxpSession?.keyUpLegacy(legacy)
fun onKeyRepeat(legacy: Int) = vxpSession?.keyRepeatLegacy(legacy)
```

Mapping:

```text
1 UP
2 DOWN
3 LEFT
4 RIGHT
5 OK
6 LSK
7 RSK
10 CLEAR
48..57 digits
42 *
35 #
```

Không gửi lại qua JNI/native bridge.

## 4. Touch

```kotlin
vxpSession?.penDown(x, y)
vxpSession?.penMove(x, y)
vxpSession?.penUp(x, y)
```

Flash Lite v0.8 hiện ưu tiên keypad/button actions; pen path chính dùng cho MRE ARM backend.

## 5. Tránh lỗi màn debug đen

UI chỉ hiển thị boot text trước frame đầu tiên. Sau `onFrame(frame)` phải đổi sang framebuffer game.

Không dùng instruction counter/debug overlay làm nội dung framebuffer.

## 6. Audio Android v0.8.4.4

Để bật tích hợp `AudioManager`/audio focus, ưu tiên overload có `Context`:

```kotlin
vxpSession = AndroidVxpCore.open(
    context = context,
    bytes = bytes,
    fileName = name,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = listener,
    textRasterizer = AndroidTextRasterizer()
).also { it.start() }
```

Nối lifecycle Activity/Fragment vào session để audio tạm dừng/khôi phục đúng trạng thái:

```kotlin
override fun onPause() {
    vxpSession?.onHostPause()
    super.onPause()
}

override fun onResume() {
    super.onResume()
    vxpSession?.onHostResume()
}
```

Các điều khiển host có sẵn:

```kotlin
val audio = vxpSession?.audioSnapshot()
vxpSession?.seekAudioTo(1_500)
vxpSession?.setAudioLooping(true)
```

Overload `AndroidVxpCore.open(...)` cũ không nhận `Context` vẫn được giữ để tương thích source, nhưng không có tích hợp `AudioManager`/audio focus đầy đủ. Volume MRE chỉ điều chỉnh gain của playback hiện tại, không thay đổi volume hệ thống của người dùng.
