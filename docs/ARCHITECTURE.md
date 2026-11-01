# NotifyHook 아키텍처

NotifyHook은 Android 알림을 감지해, 사용자가 만든 템플릿으로 HTTP POST/PUT 웹훅을 보내는 앱이다.
이 문서는 모듈 구조, 데이터 흐름, 기술 스택과 선택 이유, 빌드·배포 방법을 정리한다.
개별 결정의 배경은 [`docs/adr/`](adr/)에 있다.

## 1. 디렉터리 구조와 모듈 책임

```
app/src/main/java/com/notifyhook/app/
├── NotifyHookApp.kt          앱 진입점. 알림 채널 생성
├── model/                    순수 Kotlin 모델과 검증 규칙(Android 의존 없음, JVM 단위 테스트 대상)
│   ├── Hook.kt               훅 설정(대상 앱, URL, 메서드, 헤더, 본문 템플릿, 정규식 규칙)
│   ├── HttpHeader.kt         사용자 지정 헤더(이름, 값, 민감 여부)
│   ├── HttpHeaderValidator   헤더 이름/값 규칙. "저장 가능 = 전송 가능"을 보장
│   ├── HttpHeaderMasking     로그·테스트 결과·내보내기용 민감 헤더 판단과 마스킹
│   ├── HookDelivery.kt       한 번의 전송 요청 스냅샷(재시도 큐에 저장되는 단위)
│   ├── HookLog.kt            전송 결과 로그
│   └── Regex*/HookUrlValidator/BodyFileSaveState  보조 모델·검증기
├── data/
│   ├── HookStorage.kt        SharedPreferences(JSON) 저장소: 훅, 로그, 재시도 큐, 프리셋 내보내기/가져오기
│   ├── GlobalVariableStore   정규식 전역 변수 저장소
│   └── TemplateEngine.kt     {변수} 치환(한 번만 훑는 방식)과 JSON 이스케이프
├── service/
│   ├── NotificationListener  알림 수신 → 중복 제거 → 대상 훅 선택 → 본문 생성 → 즉시 전송
│   ├── HookDeliverySender    OkHttp 요청 생성·실행(실제 전송, 재시도, 화면 테스트가 공유)
│   ├── DeliveryRetryPolicy   재시도 여부(상태 코드 분류, 24시간 기간), 백오프 계산
│   ├── HookDeliveryWorker    WorkManager로 재시도 큐를 비우는 작업
│   ├── DeliveryWorkScheduler 재시도 작업 예약/취소
│   ├── HookDeliveryRequestValidator  보낼 수 없는 요청(URL/메서드/헤더 오류) 판별
│   ├── ForegroundService     전송 대기·감지 중임을 알리는 포그라운드 서비스 + 워치독 알람
│   └── HookExecutionPolicy / NotificationDedupKey / NotificationListenerAccess
├── receiver/                 부팅·앱 업데이트·워치독 시 서비스와 재시도 작업 복구
├── ui/
│   ├── MainActivity          탭(훅/로그), 권한 배너, 프리셋 가져오기
│   ├── HooksFragment         훅 목록, 켜기/끄기, 삭제, 프리셋 내보내기(민감 헤더 포함 여부 확인)
│   ├── LogsFragment          전송 로그 목록/상세(헤더는 마스킹된 요약)
│   ├── HookEditActivity      훅 편집, 정규식 규칙 편집기, 테스트 전송
│   ├── HeaderEditorAdapter   헤더 편집 목록
│   └── adapter/              목록 어댑터
└── util/TextReadLimiter.kt   파일·응답 본문을 정해진 길이까지만 읽기
```

의존 방향: `ui` → `service`/`data` → `model`. `model`은 Android에 의존하지 않아 JVM 단위 테스트로 검증한다.

## 2. 주요 데이터 흐름

### 알림 → 웹훅 전송

1. `NotificationListener.onNotificationPosted`: 자기 앱/진행 중 알림과 **그룹 요약 알림**(FLAG_GROUP_SUMMARY)은 무시하고, 키·시각·제목·내용으로 3초 안의 중복을 거른다.
   그룹 요약은 시스템이 자동으로 만드는 빈 요약(`ranker_group`)이거나 개별 알림 내용을 반복하므로 보내지 않는다.
2. 활성화된 훅 중 대상 앱이 맞는 훅을 고른다. 훅 목록은 저장소 revision이 바뀔 때만 다시 읽는다.
3. 훅마다 `HookDeliveryRequestValidator.requestProblem`으로 URL/메서드/헤더를 확인하고, 문제가 있으면 로그만 남기고 건너뛴다.
4. `TemplateEngine`이 본문 템플릿을 치환한다(정규식 로컬/전역 변수 포함). 전역 변수 갱신은 이때 저장된다.
5. `HookDelivery`(URL, 메서드, Content-Type, **헤더**, 본문 스냅샷)를 만들어 재시도 큐에 **먼저 저장**한다.
   전송 도중 프로세스가 죽어도 WorkManager가 이어서 보낼 수 있게 하기 위해서다.
6. `HookDeliverySender.send`로 즉시 전송한다.
   - 성공(2xx): 큐에서 지우고 성공 로그를 남긴다.
   - 실패: `DeliveryRetryPolicy.noRetryReason`이 재시도 여부를 정한다.
     재시도 대상(네트워크 오류, 408/425/429, 5xx이고 생성 후 24시간 이내)이면 백오프 시각으로 큐를 갱신하고 WorkManager를 예약한다.
     그 외(4xx, 3xx 리다이렉트, 형식 오류, 기간 초과)는 큐에서 지우고 사유를 로그에 남긴다.
7. `HookDeliveryWorker`는 네트워크가 연결되면 기한이 된 요청을 최대 4개씩 같은 규칙으로 보낸다.

### 훅 편집 → 테스트 전송

`HookEditActivity`는 입력값으로 `HookDelivery`를 만들고 `TemplateEngine.processFields`(테스트용 샘플 알림 값)와
`HookDeliverySender.newCall/execute`를 그대로 사용한다. 따라서 테스트 결과가 실제 전송과 같다.
테스트는 전역 변수를 저장하지 않는다.

### 프리셋 내보내기/가져오기

- `.notif` 파일(JSON, `format = "notifyhook-preset"`, `version = 1`)로 훅 하나를 내보낸다.
- 민감 헤더 값이 있으면 내보내기 전에 "값 빼고 내보내기/값 포함"을 묻는다. 기본은 값 제외다.
- 가져올 때 URL, 정규식, 헤더를 검증한다. 민감 헤더 값이 비어 있으면 훅을 **꺼진 상태로** 추가한다.
- 헤더 필드가 없는 이전 프리셋도 그대로 가져올 수 있다(헤더 없음으로 처리).

## 3. 저장 데이터

| SharedPreferences 파일 | 키 | 내용 |
| --- | --- | --- |
| `notifyhook_prefs` | `hooks` | `List<Hook>` JSON(헤더 값 포함) |
| | `hooks_revision` | 훅 변경 카운터(리스너 캐시 무효화용) |
| | `logs` | 최근 200개 `HookLog`(민감 헤더 값은 마스킹된 요약만) |
| | `pending_deliveries` | 재시도 큐 `List<HookDelivery>`(헤더 값 포함) |
| `notifyhook_global_vars` | `global_variables` | 정규식 전역 변수 |

두 파일 모두 `backup_rules.xml`, `data_extraction_rules.xml`에서 클라우드 백업·기기 이전 대상에서 빠져 있다.
API 키가 Google 백업으로 나가지 않는 것은 이 설정 덕분이므로 **이 제외 규칙을 지우면 안 된다**.

Gson 직렬화 규칙(수정 시 반드시 지킬 것):
- 저장 모델(`Hook`, `HookDelivery`, `HookLog`, `HttpHeader`)은 **모든 생성자 인자에 기본값**을 둔다.
  Kotlin이 무인자 생성자를 만들어야 Gson이 새로 추가된 필드에 기본값을 넣는다. 그렇지 않으면 이전 버전 데이터에서 null이 들어온다.
- R8은 `model.**`, `HookStorage`의 내부 DTO 필드, Gson `TypeToken` 하위 클래스의 제네릭 시그니처를 보존해야 한다(`app/proguard-rules.pro`).
  릴리스 빌드는 이 규칙에 의존하므로, 저장 형식을 바꾼 뒤에는 디버그뿐 아니라 **릴리스 APK도 실제로 실행해** 확인한다. 자세한 내용은 [ADR 0004](adr/0004-gson-models-and-r8.md).

## 4. 기술 스택과 선택 이유

원래 개발할 때 남긴 결정 기록이 없어서, 아래 이유는 현재 코드와 제약에서 확인할 수 있는 근거로 정리했다.

| 기술 | 용도 | 선택 이유 / 트레이드오프 |
| --- | --- | --- |
| Kotlin + Coroutines | 앱 전체, 알림 처리 병렬화 | `Dispatchers.IO.limitedParallelism(4)`로 동시 전송 수를 제한하면서 코드를 단순하게 유지. 대신 콜백 기반 API(OkHttp Call)는 직접 취소를 연결해야 한다 |
| Android View + ViewBinding + Material Components | 화면 | 화면 수가 적고 기존 구현이 View 기반. Compose로 옮기면 빌드·의존성 부담이 커지지만 얻는 것이 적다 |
| NotificationListenerService | 알림 감지 | 다른 앱 알림을 읽는 유일한 공식 API. 사용자가 알림 접근 권한을 직접 허용해야 한다 |
| Foreground Service(specialUse) + AlarmManager 워치독 | 백그라운드 유지 | 제조사 배터리 최적화로 리스너가 끊기는 것을 줄이기 위함. 상시 알림이 보이는 비용이 있다 |
| WorkManager 2.9 | 재시도 큐 처리 | 네트워크 연결 조건, 재부팅 후 복구, Doze 대응을 직접 구현하지 않기 위함. 실행 시점이 정확하지 않을 수 있다 |
| OkHttp 4.12 | HTTP 전송 | 타임아웃별 클라이언트 재사용, 커넥션 풀, 엄격한 헤더 검증. 리다이렉트 자동 추적은 끈다([ADR 0002](adr/0002-delivery-retry-and-redirect-policy.md)) |
| Gson 2.10 | SharedPreferences JSON, 프리셋 파일 | 데이터 양이 작아 DB(Room) 없이 단순하게 저장. 대신 Kotlin null 안전성을 보장하지 않으므로 위 직렬화 규칙이 필요하다 |
| SharedPreferences | 저장소 | 훅 수십 개, 로그 200개 규모라 충분. 재시도 큐가 매우 커지면(서버 장기 장애 + 알림 폭주) 메모리 부담이 생길 수 있다 |
| R8(minify + shrinkResources, full mode) | 릴리스 난독화·축소 | APK 크기 축소와 난독화. Gson처럼 리플렉션·제네릭 시그니처에 기대는 코드는 keep 규칙이 필요하고, 빠지면 디버그에서는 멀쩡하고 릴리스에서만 실패한다 |
| JUnit 4 + OkHttp MockWebServer | 단위 테스트 | 모델·정책은 순수 JVM 테스트, 전송 경로는 실제 HTTP 서버를 띄워 헤더/리다이렉트/재시도 분류를 검증 |

## 5. 빌드·테스트·배포

필요 도구: JDK 17, Android SDK(platform 34, build-tools 34), Gradle 8.5(래퍼 포함).

```bash
./gradlew testDebugUnitTest          # 단위 테스트
./gradlew assembleDebug              # 디버그 APK
./build-obfuscated-release.sh        # 난독화 릴리스 APK + mapping.txt → dist/release-<버전>/
```

- 빌드 전에는 `app/build.gradle.kts`의 `versionCode`/`versionName`을 올린다(`AGENTS.md` 규칙).
- 릴리스 서명 정보는 `keystore.properties`, Gradle 속성(`notifyhook.signing.*`), 환경 변수(`NOTIFYHOOK_*`) 순서로 찾는다.
  비밀번호는 저장소에 넣지 않는다.
- 릴리스 문제를 분석할 때는 같은 버전의 `mapping.txt`가 필요하다.

## 6. 알려진 제약과 후속 과제

- 헤더 값과 재시도 큐는 앱 전용 저장소에 평문으로 저장된다(백업 제외). Keystore 암호화를 하지 않은 이유는 [ADR 0001](adr/0001-custom-http-headers.md).
- 헤더 값에는 템플릿 변수를 쓸 수 없다(고정 값만). 같은 ADR 참고.
- URL 쿼리에 키를 넣으면(`?key=...`) 로그의 URL에 그대로 보인다. 키는 헤더로 보내는 것을 권장한다.
- 1.0.35 이하 릴리스 빌드가 내보낸 프리셋은 키가 난독화되어 있어(`{"a":..}`) 가져올 수 없다. [ADR 0004](adr/0004-gson-models-and-r8.md).
- 본문 치환은 Content-Type과 관계없이 JSON 문자열 규칙으로 이스케이프한다(기존 동작 유지).
- 재시도 큐 개수 상한은 없다. 24시간 기간 제한으로 간접적으로만 묶인다.
