# VXP-Core Library — Wiki（日本語）

## クレジット

メンテナ: **DOXUANHOP** — https://qeafivels.com/  
Namespace: `vn.com.doxuanhop.vxpcore.android`。利用時は
https://qeafivels.com/ へのリンク表記をお願いします。

## 1. 概要

既存 Android UI に MediaTek `.vxp` を組み込むための Kotlin-only コア。
Activity/View/Compose なし、JNI/NDK/CMake/C/C++ なし、
`System.loadLibrary` なし。Guest ARM/Thumb は Kotlin インタプリタが実行します。

```
VXP -> AndroidVxpCore -> Kotlin バックエンド（ARM/MRE または Flash Lite）
  -> FrameSnapshot RGB565 -> 自作コントローラ -> 既存 UI
```

## 2. バージョン

| フォルダ | バージョン | 成果物 | 備考 |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | ライブラリ初分割 |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` | `dist/vxp-core-0.8.2.jar` | Thumb ALU、SMS サンドボックス、長時間回帰 |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` | `dist/vxp-core-0.8.3.jar` | 最新：ELF/GCC 互換 + 新規 ELF 3 作品 |

新規導入は **v0.8.3** を使用。詳細は `VERSIONS.md`。

## 3. バックエンド

- `ELF32 ARM` → Kotlin ARM/Thumb + MRE：対応。v0.8.3 で GCC `gcc_entry` + `.init_array` 起動を追加。
- Raw ARM + zlib（`RAW_ARM_ZLIB`、Gameloft）→ Kotlin ARM/Thumb + MRE：対応。
- Flash Lite `FWS/CWS` → Android Kotlin + SWF/AVM1：互換優先。
- 不明形式 → 検出器が `UNKNOWN` を返し、MREmu へフォールバックしません。

## 4. 必要環境

JDK 17+、Gradle 8.x、AGP `8.7.3`、Kotlin `2.0.21`、
Android `compileSdk 35` / `minSdk 23`、リポジトリ `google()` + `mavenCentral()`。
NDK/CMake 不要。検証：`./verify_kotlin_only.sh`。

## 5. 導入

```kotlin
// settings.gradle.kts
include(":vxp-core")
include(":vxp-core-android")

// app モジュール
dependencies {
    implementation(project(":vxp-core-android")) // :vxp-core を自動取得
}
```

ビルド済み `dist/vxp-core-0.8.3.jar` の利用も可能です。

## 6. 使い方

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

キー：`1 上、2 下、3 左、4 右、5 OK、6 LSK、7 RSK、10 クリア、48-57 数字、42 *、35 #`。
起動文字は最初のフレーム前のみ表示し、`onFrame()` 以降はゲームの
フレームバッファを唯一の LCD ソースにしてください。

## 7. 検証

- スパイダーマン（`RAW_ARM_ZLIB`）：23,524,795 命令 / 425 フレーム / 440 イベント / 424 タイマ、240x320 RGB565、フォルトなし、`stubbedSymbols = []`。
- Crazy Taxi（`FLASH_LITE`）：176x220 @20fps、全 35 フレーム、メニュー 4 枚目 → OK → 実 AVM1 で 5 枚目へ。
- v0.8.3 新規 ELF 3 作品：`CatBoxMRE` ゲームプレイ（218 フレーム / 28.3M 命令）、`RetroMRE` メニュー（18 フレーム / 11.8M 命令、実 `vm_find_*` + UCS2 NUL 修正）、`Whisk3D` 3D シーン（9 フレーム / 33.3M 命令）。
- v0.8.3 CPU 修正：`R_ARM_RELATIVE` sym-0、`gcc_entry`/`.init_array`、Thumb BLX-reg + PC(+4) + STRH/LDRH、ARM CLZ + LDRD/STRD + ロング乗算、Operand2 PC(+8)、`_vm_log_*`、`vm_find_*` ワイルドカード。

## 8. 安全性

`vm_send_sms` はサンドボックス内で常に失敗を返します。実際の SMS や課金通信は行いません。

## 9. リンク

- https://qeafivels.com/
- `../VERSIONS.md`、`../README.md`
- `VXP-Core-Library-v0.8.3/docs/INTEGRATE_EXISTING_UI.md`、`docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`
