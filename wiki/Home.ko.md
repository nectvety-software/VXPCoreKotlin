# VXP-Core Library — Wiki (한국어)

## 크레딧

관리자: **DOXUANHOP** — https://qeafivels.com/  
네임스페이스: `vn.com.doxuanhop.vxpcore.android`. 라이브러리 사용 시
https://qeafivels.com/ 링크 표기를 부탁드립니다.

## 1. 개요

기존 Android UI에 MediaTek `.vxp`를 내장하기 위한 Kotlin-only 코어.
Activity/View/Compose 없음, JNI/NDK/CMake/C/C++ 없음,
`System.loadLibrary` 없음. Guest ARM/Thumb 코드는 Kotlin 인터프리터가 실행합니다.

```
VXP -> AndroidVxpCore -> Kotlin 백엔드(ARM/MRE 또는 Flash Lite)
  -> FrameSnapshot RGB565 -> 컨트롤러 -> 기존 UI
```

## 2. 버전

| 폴더 | 버전 | 산출물 | 비고 |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | 최초 라이브러리 분리 |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` | `dist/vxp-core-0.8.2.jar` | Thumb ALU, SMS 샌드박스, 장기 회귀 |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` | `dist/vxp-core-0.8.3.jar` | 최신: ELF/GCC 호환 + 신규 ELF 3종 |

신규 연동은 **v0.8.3** 사용. 자세한 내용은 `VERSIONS.md`.

## 3. 백엔드

- `ELF32 ARM` → Kotlin ARM/Thumb + MRE: 지원. v0.8.3에서 GCC `gcc_entry` + `.init_array` 부트스트랩 추가.
- Raw ARM + zlib(`RAW_ARM_ZLIB`, Gameloft) → Kotlin ARM/Thumb + MRE: 지원.
- Flash Lite `FWS/CWS` → Android Kotlin + SWF/AVM1: 호환성 우선.
- 알 수 없음 → 탐지기가 `UNKNOWN` 반환, MREmu 폴백 없음.

## 4. 요구 사항

JDK 17+, Gradle 8.x, AGP `8.7.3`, Kotlin `2.0.21`,
Android `compileSdk 35` / `minSdk 23`, 저장소 `google()` + `mavenCentral()`.
NDK/CMake 불필요. 검증: `./verify_kotlin_only.sh`.

## 5. 설치

```kotlin
// settings.gradle.kts
include(":vxp-core")
include(":vxp-core-android")

// app 모듈
dependencies {
    implementation(project(":vxp-core-android")) // :vxp-core 자동 포함
}
```

미리 빌드된 `dist/vxp-core-0.8.3.jar` 사용도 가능합니다.

## 6. 사용법

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

키: `1 UP, 2 DOWN, 3 LEFT, 4 RIGHT, 5 OK, 6 LSK, 7 RSK, 10 CLEAR, 48-57 숫자, 42 *, 35 #`.
부팅 문구는 첫 프레임 전에만 표시하고, `onFrame()` 이후에는 게임
프레임버퍼를 유일한 LCD 소스로 사용하세요.

## 7. 검증

- 스파이더맨(`RAW_ARM_ZLIB`): 23,524,795 명령 / 425 프레임 / 440 이벤트 / 424 타이머, 240x320 RGB565, 결함 없음, `stubbedSymbols = []`.
- Crazy Taxi(`FLASH_LITE`): 176x220 @20fps, 35 프레임, 메뉴 4번 → OK → 실제 AVM1로 5번 프레임 이동.
- v0.8.3 신규 ELF 3종: `CatBoxMRE` 게임플레이(218 프레임 / 28.3M 명령), `RetroMRE` 메뉴(18 프레임 / 11.8M 명령, 실제 `vm_find_*` + UCS2 NUL 수정), `Whisk3D` 3D 장면(9 프레임 / 33.3M 명령).
- v0.8.3 CPU 수정: `R_ARM_RELATIVE` sym-0, `gcc_entry`/`.init_array`, Thumb BLX-reg + PC(+4) + STRH/LDRH, ARM CLZ + LDRD/STRD + long multiply, Operand2 PC(+8), `_vm_log_*`, `vm_find_*` 와일드카드.

## 8. 안전

`vm_send_sms`는 샌드박스에서 항상 실패를 반환하며, 실제 SMS/결제 전송을 하지 않습니다.

## 9. 링크

- https://qeafivels.com/
- `../VERSIONS.md`, `../README.md`
- `VXP-Core-Library-v0.8.3/docs/INTEGRATE_EXISTING_UI.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`
