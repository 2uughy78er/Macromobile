# Input Mirror — 검증용 MVP

Android에서 **한 창의 터치를 다른 창에 그대로 복제**하는 것이 실제로 가능한지 확인하는
최소 앱입니다. 본 프로젝트(게임 입력 미러링)로 넘어가기 전 단계입니다.

> **이 앱은 아직 게임용이 아닙니다.** 한 화면 안의 네 영역으로 "주입이 통하는가"만
> 확인합니다. 이게 검증된 뒤에야 실제 앱을 대상으로 확장합니다.

---

## 먼저: 원래 요구사항은 그대로는 불가능합니다

"게임 A를 조작하면 백그라운드의 게임 B·C도 따라 한다"는 **비루팅 Android에서 불가능합니다.**

이유는 권한이 아니라 플랫폼 구조입니다.

- `dispatchGesture`는 공식 문서상 **"dispatch custom gestures to the screen"** — 앱이 아니라
  **화면 좌표**에 입력을 주입합니다. 패키지를 지정해 보낼 수 없습니다.
- 그 좌표의 **맨 위 창**이 입력을 받습니다.
- **백그라운드 앱은 렌더링도 하지 않고 터치도 받지 않습니다.**

즉 대상 게임이 화면에 보이지 않으면 **입력이 갈 곳 자체가 없습니다.**

### 그래서 전제를 바꿉니다

**모든 인스턴스가 동시에 화면에 보여야 합니다.**

| 환경 | 동시에 보이는 앱 |
|---|---|
| 기본 분할화면 | 2개 |
| 삼성 멀티윈도우 + 팝업뷰 | 3~4개 |
| 삼성 DeX | 여러 개 |

이 전제라면 좌표 변환·입력 종류·화면 보정·큐 처리·로그는 모두 구현 가능합니다.

---

## 이 MVP가 확인하는 것

```
┌─────────────────┬─────────────────┐
│  MASTER         │  TARGET 1       │
│  (여기를 만짐)   │                 │
├─────────────────┼─────────────────┤
│  TARGET 2       │  TARGET 3       │
└─────────────────┴─────────────────┘
```

MASTER 영역을 만지면 그 입력이 접근성 서비스를 통해 TARGET 영역에 **실제로 주입됩니다.**
TARGET에 그려지는 선은 시늉이 아니라 **시스템이 실제로 전달한 터치**를 받아 그린 것입니다.

**TARGET에 선이 그려지면 주입이 통한 것이고, 안 그려지면 막힌 것입니다.**

확인할 수 있는 것:

- ACTION_DOWN / MOVE / UP 이 순서대로 전달되는가
- 좌표 변환이 정확한가 (화면 좌표와 영역 내 좌표를 함께 표시)
- 지연이 얼마나 되는가 (ms 단위로 기록)
- 한 번에 몇 개 대상까지 동시에 보낼 수 있는가 (`getMaxStrokeCount()`를 시스템에 직접 물어봄)
- 드래그를 이어 보내는 방식(streaming)이 실제로 되는가

---

## 쓰는 법

1. APK 설치
2. **설정 → 접근성 → 설치된 서비스 → `Input Mirror`** 켜기
3. 앱에서 **테스트 화면 열기**
4. **▶ 미러링 시작**
5. 왼쪽 위 MASTER 영역을 터치하거나 드래그

아래에 마지막 기록이 한 줄로 뜹니다.

```
09:31:02.417  ACTION_MOVE  540,960  TARGET 1: 1080,240 | TARGET 2: 270,1200  18ms  SUCCESS
```

## 크래시가 나면 — 진단 화면

앱이 죽으면 그 순간의 보고서가 기기 안에 파일로 남습니다. 다음에 앱을 켜면 메인 화면
맨 위에 알림이 뜨고, **진단 · 크래시 기록** 에서 전체를 볼 수 있습니다. PC나 ADB 없이
기기만으로 원인을 확인할 수 있습니다.

보고서에 들어 있는 것: 시각 / 스레드 / 컴포넌트 / 마지막 단계 / 예외 클래스 / 예외 메시지
/ 스택 트레이스 / 기기 / OS 버전.

**크래시를 숨기지 않습니다.** 기록기는 보고서를 남긴 뒤 원래 핸들러에 그대로 넘기므로
앱은 평소처럼 죽습니다. 예외를 삼켜 억지로 화면을 띄우면 원인이 가려지고, 그 화면은
어차피 믿을 수 없기 때문입니다.

### 단계 기록

테스트 화면이 열리기까지 거치는 단계가 모두 기록됩니다.

```
TEST_SCREEN_CLICK → TEST_SCREEN_NAVIGATION_START → TEST_SCREEN_ACTIVITY_CREATE
→ TEST_SCREEN_COMPOSE_START → ACCESSIBILITY_SERVICE_CHECK → DISPLAY_METRICS_READ
→ WINDOW_METRICS_READ → OVERLAY_INITIALIZE → GESTURE_CONTROLLER_INITIALIZE
→ TEST_SCREEN_READY
```

마지막으로 남은 줄이 곧 크래시 직전 지점입니다. 기록할 때마다 파일에 바로 쓰므로
프로세스가 죽어도 마지막 줄이 남습니다.

## 테스트 화면의 STEP 1~10

예전에는 좌표 변환·제스처 전송기·오버레이 뷰·로그를 테스트 화면 하나에서 **동시에**
초기화했습니다. 그래서 그중 하나가 예외를 던지면 화면이 통째로 죽었고, 무엇이 죽였는지
알 길이 없었습니다. 이제 단계로 쪼개 하나씩 켭니다. 각 단계는 아래 단계를 포함합니다.

| STEP | 켜지는 것 |
|---|---|
| 1 | 빈 테스트 화면 (Compose 만) |
| 2 | 접근성 서비스 상태 확인 |
| 3 | DisplayMetrics 읽기 |
| 4 | WindowMetrics · 인셋 읽기 |
| 5 | 테스트 뷰 생성 (그리지 않음) |
| 6 | 4분할 영역 그리기 |
| 7 | 화면 좌표 변환 (View → Screen) |
| 8 | MASTER 터치 수집 (주입 없음) |
| 9 | 제스처 전송기 + TARGET 1개 |
| 10 | TARGET 3개 전체 (최종 형태) |

기본은 STEP 10 입니다. 죽으면 한 단계씩 내려가며 범인을 좁힙니다.

---

## 전송 방식 두 가지

| 방식 | 동작 | 쓰임 |
|---|---|---|
| **즉시 전송** | 손가락이 움직이는 동안 구간을 이어서 보냄 | 실제 미러링에 필요한 방식. 지연 확인용 |
| **완료 후 전송** | 손을 뗀 뒤 경로 전체를 한 번에 보냄 | 좌표 변환이 맞는지 확인할 때 |

즉시 전송은 `StrokeDescription.continueStroke()`로 스트로크를 이어붙입니다. 앞 구간이
끝나야 다음을 보낼 수 있어서, 손가락이 아주 빠르면 점이 일부 뭉쳐집니다.

## 대상이 여럿일 때

대상마다 스트로크를 만들어 **하나의 제스처에 묶어 한 번에** 보냅니다. 그래야 대상들이
같은 순간에 입력을 받습니다. 담을 수 있는 스트로크 수는 시스템이 정하며
(`GestureDescription.getMaxStrokeCount()`), 그게 곧 동시 대상 수의 상한입니다.
메인 화면에서 이 기기의 실제 값을 확인할 수 있습니다.

---

## 검증 결과를 기록해주세요

이 MVP를 실기기에서 돌려보고 아래를 확인하면 본 프로젝트로 넘어갈 수 있습니다.

- [ ] TARGET 영역에 선이 그려지는가 (= 주입이 통하는가)
- [ ] 좌표가 정확히 대응하는가
- [ ] 즉시 전송의 지연이 몇 ms인가
- [ ] 대상 3개를 동시에 보낼 수 있는가
- [ ] 실패 기록(FAIL)이 나오는가, 나온다면 어떤 사유인가

그다음 단계는 **분할화면에서 실제 앱 두 개**를 대상으로 같은 시험을 하는 것입니다.
거기서 통하면 게임으로 넘어갑니다.

---

## 아직 넘지 못한 벽

실기기 검증 전이라 **아직 모르는 것**을 적어둡니다.

1. **게임이 주입 입력을 거부할 수 있습니다.** `setFilterTouchesWhenObscured`, `FLAG_SECURE`,
   안티치트 중 하나만 걸려도 안 됩니다. 게임마다 다릅니다.
2. **같은 게임을 여러 개 띄우는 것 자체가 어렵습니다.** 한 패키지는 한 번만 설치됩니다.
   듀얼 메신저·클론 앱·보조 사용자 계정 모두 제약이 있습니다.
3. **멀티터치는 이 구조로 기록할 수 없습니다.** 손가락 하나만 따라갑니다.

---

## 기술 스택

Kotlin · Jetpack Compose · Material 3 · AccessibilityService · DataStore

MediaProjection은 **쓰지 않습니다.** 이 검증에 필요하지 않습니다.
네트워크 권한도 요청하지 않습니다. ROOT와 ADB도 쓰지 않습니다.

## 구조

```
app/src/main/java/com/macromobile/inputmirror/
├─ model/       Region, FitMode, MirrorMode, MirrorSettings
├─ input/
│   ├─ CoordinateTransformer.kt  창 ↔ 창 좌표 변환 (상대좌표 + 레터박스)
│   ├─ MirrorPathPlanner.kt      경로 계산 (순수 로직, 테스트로 고정)
│   ├─ GestureDispatcher.kt      큐 + 순서 보장 + 다중 스트로크 전송
│   └─ InputEvent.kt             TouchPoint, TouchStroke, MirrorRecord
├─ diag/
│   ├─ DiagStage.kt              단계 이름 (TEST_SCREEN_CLICK … TEST_SCREEN_READY)
│   ├─ DiagLog.kt                단계 기록 + 파일 저장
│   ├─ CrashRecorder.kt          크래시 보고서 (막지 않고 기록만)
│   └─ TestStep.kt               STEP 1~10 구분
├─ service/     MirrorAccessibilityService (입력 주입 전담)
├─ storage/     SettingsRepository, MirrorLogStore
└─ ui/          MainScreen, MirrorTestScreen, SettingsScreen, LogScreen, PermissionScreen
```

## 빌드

GitHub Actions가 push마다 debug APK를 만듭니다.
**Actions → Build APK → 실행 선택 → Artifacts → `input-mirror-debug-apk`**

debug 서명 키를 저장소에 고정해 두었으므로, 새 APK는 **앱을 지우지 않고 덮어쓰기 설치**하면
됩니다. 빌드 로그의 `Print debug APK signing fingerprint` 단계에서 지문을 확인할 수 있습니다.

---

## 컨텍스트 주의 (Android 11+ / Android 16)

`Context.getDisplay()` 와 `WindowManager.getCurrentWindowMetrics()` 는 **디스플레이에
연결된 컨텍스트**에서만 쓸 수 있습니다. Activity, `createWindowContext()`,
`createDisplayContext()` 로 만든 것이 여기 해당합니다. Application 컨텍스트로 부르면
`UnsupportedOperationException` 이 납니다.

이 앱은 한때 `EnvironmentInfo.collect()` 에 Application 컨텍스트를 넘겨 테스트 화면이
열리자마자 죽었습니다. 지금은 그 함수가 **컨텍스트를 받지 않고 View 만 받습니다.**
View 의 컨텍스트는 언제나 Activity 이므로 같은 실수를 할 수 없습니다. 회전값도
`Context.getDisplay()` 대신 `View.getDisplay()` 로 읽습니다 — 이쪽은 예외 대신 null 을
줍니다.

새 코드에서 화면·창 정보를 읽을 일이 생기면 **반드시 View 나 Activity 를 통해** 읽으세요.

## 사용 책임

게임 다중 인스턴스 조작은 대부분의 게임 이용약관에서 금지합니다. 사용에 따른 책임은
사용자에게 있습니다.
