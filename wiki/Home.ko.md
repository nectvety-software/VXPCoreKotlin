# VXP-Core Library — Wiki (한국어)

## 크레딧

관리자: **DOXUANHOP** — https://qeafivels.com/  
네임스페이스: `vn.com.doxuanhop.vxpcore.android`. 라이브러리 사용 시
https://qeafivels.com/ 링크 표기를 부탁드립니다.

## 1. 개요

기존 Android UI에 `.vxp`를 내장하기 위한 Kotlin-only 코어.
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
| `VXP-Core-Library-v0.8.3/` | `0.8.3` | `dist/vxp-core-0.8.3.jar` | ELF/GCC 호환 + 신규 ELF 3종 |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room | `dist/vxp-core-0.8.3-cleanroom.jar` | Kotlin-only 재구성, provenance/준수 문서 |
| `VXP-Core-Library-v0.8.4.1/` | `0.8.4.1` clean-room | `dist/vxp-core-0.8.4.1.jar` | SYSTEM/GRAPHICS/FILE_RESOURCE 별칭, 76/76 observed first-class |
| `VXP-Core-Library-v0.8.4.2/` | `0.8.4.2` clean-room | `dist/vxp-core-0.8.4.2.jar` | 중간판: FILE_RESOURCE 디렉터리 pass |
| `VXP-Core-Library-v0.8.4.4/` | `0.8.4.4` clean-room | `dist/vxp-core-0.8.4.4.jar` | 최신: AUDIO 브리지＋재생 정확도＋Android 오디오 통합 |

신규 연동·배포 빌드는 **v0.8.4.4** 사용. 자세한 내용은 `VERSIONS.md`.

## 3. 백엔드

- `ELF32 ARM` → Kotlin ARM/Thumb + MRE: 지원. v0.8.3에서 GCC `gcc_entry` + `.init_array` 부트스트랩 추가.
- Raw ARM + zlib(`RAW_ARM_ZLIB`) → Kotlin ARM/Thumb + MRE: 지원.
- Flash Lite `FWS/CWS` → Android Kotlin + SWF/AVM1: 호환성 우선.
- 알 수 없음 → 탐지기가 `UNKNOWN` 반환, 다른 에뮬레이터 폴백 없음.

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

미리 빌드된 `dist/vxp-core-0.8.4.4.jar` 사용도 가능합니다.

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

v0.8.4.4 권장: `open(context=...)` 오버로드로 오디오 포커스/인터럽트 활성화,
`onHostPause()/onHostResume()` 전달, `audioSnapshot()/seekAudioTo()/setAudioLooping()`으로 오디오 상태 조회.

키: `1 UP, 2 DOWN, 3 LEFT, 4 RIGHT, 5 OK, 6 LSK, 7 RSK, 10 CLEAR, 48-57 숫자, 42 *, 35 #`.
부팅 문구는 첫 프레임 전에만 표시하고, `onFrame()` 이후에는 게임
프레임버퍼를 유일한 LCD 소스로 사용하세요.

## 7. 검증

- 샘플 A(`RAW_ARM_ZLIB`): 23,524,795 명령 / 425 프레임 / 440 이벤트 / 424 타이머, 240x320 RGB565, 결함 없음, `stubbedSymbols = []`.
- 샘플 B(`FLASH_LITE`): 176x220 @20fps, 35 프레임, 메뉴 4번 → OK → 실제 AVM1로 5번 프레임 이동.
- v0.8.3 신규 ELF 3종: 게임플레이(218 프레임 / 28.3M 명령), 메뉴(18 프레임 / 11.8M 명령, 실제 `vm_find_*` + UCS2 NUL 수정), 3D 장면(9 프레임 / 33.3M 명령).
- v0.8.3 CPU 수정: `R_ARM_RELATIVE` sym-0, `gcc_entry`/`.init_array`, Thumb BLX-reg + PC(+4) + STRH/LDRH, ARM CLZ + LDRD/STRD + long multiply, Operand2 PC(+8), `_vm_log_*`, `vm_find_*` 와일드카드.
- clean-room 재검증: 218f / 28f / 9f / 82f, 메뉴 흐름 정상. `verify_clean_room.sh` PASS.
- v0.8.4.1 별칭 패스: SYSTEM tick/resolver/callback·`vm_sscanf`·샌드박스 용량, GRAPHICS 화면/이미지/로드/미러＋`create_layer_ex`, FILE 이중 조회·엄격 open/append·`vm_resource_init`/`vm_res_load`. 코퍼스 76/76 first-class.
- v0.8.4.1 timed run: 30.8M/218f, 26.8M/39f, 33.3M/9f, 9.4M/83f, Flash Lite 4→OK(10 AVM1)→5.
- v0.8.4.2 디렉터리 pass: `vm_file_copy/tell/is_eof/get_modify_time`, 강화 copy/rename, 경로 헬퍼, 속성 메타데이터, resource-from-file(샌드박스 복사).
- v0.8.4.3/0.8.4.4 오디오: 중립 `MreAudioHost`＋Android 백엔드(WAV→AudioTrack, encoded/MIDI/file→MediaPlayer), play/MIDI/볼륨/인터럽트 handler, 이벤트 루프 콜백; v0.8.4.4는 duration/seek/loop 측정, 오디오 포커스/더킹/수명주기 추가. 오디오 후 스모크: 186f/91f/2f, 해시 불변, 76/76 first-class.

## 8. 안전

`vm_send_sms`는 샌드박스에서 항상 실패를 반환하며, 실제 SMS/결제 전송을 하지 않습니다.

## 9. 링크

- https://qeafivels.com/
- `../VERSIONS.md`, `../README.md`
- `VXP-Core-Library-v0.8.3/validation/ALL_VXP_COMPATIBILITY_v0.8.3.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/INTEGRATE_EXISTING_UI.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`
- `VXP-Core-Library-v0.8.3-cleanroom/validation/CLEANROOM_VALIDATION_v0.8.3.md`
- `VXP-Core-Library-v0.8.4.1/docs/OBSERVED_COMPATIBILITY_SURFACE.md`
- `VXP-Core-Library-v0.8.4.1/validation/COMPATIBILITY_v0.8.4.1.md`
- `VXP-Core-Library-v0.8.4.4/docs/OBSERVED_COMPATIBILITY_SURFACE.md`
- `VXP-Core-Library-v0.8.4.4/validation/COMPATIBILITY_v0.8.4.4.md`
- `VXP-Core-Library-v0.8.4.4/validation/AUDIO_v0.8.4.4.md`
