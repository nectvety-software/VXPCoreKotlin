# VXP-Core Library — Wiki（简体中文）

## 致谢

由 **DOXUANHOP** 维护 — https://qeafivels.com/  
命名空间：`vn.com.doxuanhop.vxpcore.android`。如使用本库，
请附上 https://qeafivels.com/ 链接以示致谢。

## 1. 概述

纯 Kotlin 核心库，用于在现有 Android UI 中运行 `.vxp`。
无 Activity/View/Compose，无 JNI/NDK/CMake/C/C++，无 `System.loadLibrary`。
Guest ARM/Thumb 代码由 Kotlin 解释器执行。

```
VXP -> AndroidVxpCore -> Kotlin 后端（ARM/MRE 或 Flash Lite）
  -> FrameSnapshot RGB565 -> 你的控制器 -> 现有 UI
```

## 2. 版本

| 目录 | 版本 | 产物 | 说明 |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | 首次拆分为库 |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` | `dist/vxp-core-0.8.2.jar` | Thumb ALU、SMS 沙箱、长回归 |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` | `dist/vxp-core-0.8.3.jar` | ELF/GCC 兼容 + 3 个新 ELF 标题 |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room | `dist/vxp-core-0.8.3-cleanroom.jar` | 纯 Kotlin 重构，provenance/合规文档 |
| `VXP-Core-Library-v0.8.4.1/` | `0.8.4.1` clean-room | `dist/vxp-core-0.8.4.1.jar` | SYSTEM/GRAPHICS/FILE_RESOURCE 别名，76/76 observed first-class |
| `VXP-Core-Library-v0.8.4.2/` | `0.8.4.2` clean-room | `dist/vxp-core-0.8.4.2.jar` | 中间版：FILE_RESOURCE 目录 pass |
| `VXP-Core-Library-v0.8.4.4/` | `0.8.4.4` clean-room | `dist/vxp-core-0.8.4.4.jar` | 最新：AUDIO 桥 + 播放精度 + Android 音频集成 |

新项目与发行构建请使用 **v0.8.4.4**。详见 `VERSIONS.md`。

## 3. 后端支持

- `ELF32 ARM` → Kotlin ARM/Thumb + MRE：支持，v0.8.3 新增 GCC `gcc_entry` + `.init_array` 启动。
- Raw ARM + zlib（`RAW_ARM_ZLIB`）→ Kotlin ARM/Thumb + MRE：支持。
- Flash Lite `FWS/CWS` → Android Kotlin + SWF/AVM1：兼容优先。
- 未知格式 → 检测器返回 `UNKNOWN`，不回退到其他模拟器。

## 4. 环境要求

JDK 17+、Gradle 8.x、AGP `8.7.3`、Kotlin `2.0.21`、
Android `compileSdk 35` / `minSdk 23`，仓库 `google()` + `mavenCentral()`。
无需 NDK/CMake。校验：`./verify_kotlin_only.sh`。

## 5. 安装

```kotlin
// settings.gradle.kts
include(":vxp-core")
include(":vxp-core-android")

// app 模块
dependencies {
    implementation(project(":vxp-core-android")) // 自动引入 :vxp-core
}
```

也可直接使用预构建的 `dist/vxp-core-0.8.4.4.jar`。

## 6. 用法

```kotlin
val session = AndroidVxpCore.open(
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) { /* RGB565 -> UI */ }
        override fun onState(state: VxpSessionState) { /* Starting/Running/Stopped/Failed */ }
    }
)
session.start()
session.keyDownLegacy(5); session.keyUpLegacy(5) // OK
session.penDown(x, y); session.penMove(x, y); session.penUp(x, y)
session.stop(); session.close()
```

v0.8.4.4 建议：使用 `open(context=...)` 重载以启用音频焦点/中断，
转发 `onHostPause()/onHostResume()`，使用 `audioSnapshot()/seekAudioTo()/setAudioLooping()` 查询音频状态。

按键：`1 上，2 下，3 左，4 右，5 OK，6 LSK，7 RSK，10 清除，48-57 数字，42 *，35 #`。
首帧之前可显示启动文字；收到 `onFrame()` 后，游戏帧缓冲是唯一的 LCD 来源。

## 7. 验证

- 样本 A（`RAW_ARM_ZLIB`）：23,524,795 指令 / 425 帧 / 440 事件 / 424 定时器，240x320 RGB565，无故障，`stubbedSymbols = []`。
- 样本 B（`FLASH_LITE`）：176x220 @20fps，35 帧，菜单第 4 帧 → OK → 真实 AVM1 跳到第 5 帧。
- v0.8.3 新增 3 个 ELF：游戏样本（218 帧 / 28.3M 指令）、菜单样本（18 帧 / 11.8M 指令，真实 `vm_find_*` + UCS2 NUL 修复）、3D 场景（9 帧 / 33.3M 指令）。
- v0.8.3 CPU 修复：`R_ARM_RELATIVE` sym-0、`gcc_entry`/`.init_array`、Thumb BLX-reg + PC(+4) + STRH/LDRH、ARM CLZ + LDRD/STRD + 长乘法、Operand2 PC(+8)、`_vm_log_*`、`vm_find_*` 通配符。
- clean-room 再验证：218f / 28f / 9f / 82f，菜单流程正常。`verify_clean_room.sh` 通过。
- v0.8.4.1 别名：SYSTEM tick/resolver/callback、`vm_sscanf`、沙箱磁盘；GRAPHICS 屏幕/图像/加载/镜像 + `create_layer_ex`；FILE 双 get-size、严格 open/append、`vm_resource_init`/`vm_res_load`。语料 76/76 first-class。
- v0.8.4.1 定时运行：30.8M/218f、26.8M/39f、33.3M/9f、9.4M/83f，Flash Lite 4→OK(10 AVM1)→5。
- v0.8.4.2 目录 pass：`vm_file_copy/tell/is_eof/get_modify_time`、强化 copy/rename、路径助手、属性元数据、resource-from-file（沙箱复制）。
- v0.8.4.3/0.8.4.4 音频：中立 `MreAudioHost` + Android 后端（WAV→AudioTrack，encoded/MIDI/file→MediaPlayer），play/MIDI/音量/中断 handler，事件循环回调；v0.8.4.4 增加 duration/seek/loop 探测、音频焦点/闪避/生命周期。音频后冒烟：186f/91f/2f，哈希不变，76/76 first-class。

## 8. 安全

`vm_send_sms` 在沙箱中恒返回失败；核心绝不发送真实短信或计费流量。

## 9. 链接

- https://qeafivels.com/
- `../VERSIONS.md`、`../README.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/INTEGRATE_EXISTING_UI.md`、`docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/CLEAN_ROOM_POLICY.md`、`docs/PROVENANCE.md`
- `VXP-Core-Library-v0.8.3-cleanroom/validation/CLEANROOM_VALIDATION_v0.8.3.md`
- `VXP-Core-Library-v0.8.4.1/docs/OBSERVED_COMPATIBILITY_SURFACE.md`
- `VXP-Core-Library-v0.8.4.1/validation/COMPATIBILITY_v0.8.4.1.md`
- `VXP-Core-Library-v0.8.4.4/docs/OBSERVED_COMPATIBILITY_SURFACE.md`
- `VXP-Core-Library-v0.8.4.4/validation/COMPATIBILITY_v0.8.4.4.md`
- `VXP-Core-Library-v0.8.4.4/validation/AUDIO_v0.8.4.4.md`
