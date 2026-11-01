# ADR 0004: Gson 저장 모델 규칙과 R8 keep 규칙

- 상태: 채택 (1.0.36)
- 관련 코드: `app/proguard-rules.pro`, `model/HookDelivery.kt`, `model/HookLog.kt`, `data/HookStorage.kt`

## 배경

0. **릴리스 빌드 즉시 종료(가장 심각)**: Gson 2.10.1은 R8 규칙을 포함하지 않는다. AGP 8의 기본값인 R8 full mode는 keep 규칙에 걸리지 않은 클래스의
   Signature 속성을 지우므로, `object : TypeToken<List<Hook>>() {}`의 타입 인자가 사라진다. 저장된 데이터가 하나라도 생기면(훅 저장 직후)
   `IllegalStateException: TypeToken must be created with a type argument`로 앱이 종료되고, 이후 실행할 때마다 다시 종료된다.
   1.0.36 작업 중 기존 HEAD 소스를 같은 방식으로 릴리스 빌드해 에뮬레이터(Android 14)에서 재현했다.
1. **릴리스 프리셋 파일 난독화**: `HookStorage`의 프리셋 DTO(`HookPresetFile`, `RawHook`, `RawRegexRule`, `RawRegexVariable`)는
   `data` 패키지에 있어 keep 규칙(`model.**`, `service.**`) 밖이었다. 1.0.7 릴리스 빌드의 `mapping.txt`에서 `RawHook.url → f` 같은 이름 변경이 확인되었고,
   `HookPresetFile.format` 필드는 mapping에 없어 상수 전파로 제거된 것으로 보인다. 그래서 릴리스 앱이 내보낸 `.notif` 파일은 `{"a":1,"c":{"f":"https://..."}}` 같은 키로 저장되고,
   빌드마다 바뀌는 이름 때문에 다른 버전에서 가져올 수 없다.
2. **새 필드와 Gson**: Gson은 무인자 생성자가 없으면 Unsafe로 객체를 만든다. 이때 저장된 JSON에 없는 필드는 Kotlin non-null 타입이어도 null이 된다.
   `HookDelivery`, `HookLog`는 `hookId`, `hookName`에 기본값이 없어 무인자 생성자가 없었다.
   업데이트 전에 저장된 재시도 큐를 읽으면 새 필드(`requestHeaders`)가 null이 되어 전송 시 NPE가 난다.

## 검토한 대안

DTO 보존
1. DTO를 `model` 패키지로 옮기기: 저장 형식 전용 클래스가 도메인 모델과 섞인다.
2. 필드마다 `@SerializedName` 지정: 필드를 추가할 때마다 빠뜨리기 쉽고, 상수 필드 제거 최적화는 막지 못한다.
3. `-keep class com.notifyhook.app.data.HookStorage$* { <fields>; <init>(...); }`: 내부 DTO를 한 번에 보존하고 새 DTO도 자동으로 포함된다.

Gson 생성자
1. 읽을 때마다 `?: emptyList()`로 방어: 모든 사용처에 흩어지고, 컴파일러는 non-null로 보아 경고만 낸다.
2. 커스텀 TypeAdapter: 모델마다 코드가 늘어난다.
3. 저장 모델의 모든 생성자 인자에 기본값을 둬 무인자 생성자를 만들기: 기존 JSON에 없는 필드도 기본값이 들어간다.

## 결정

- Gson이 2.11부터 포함하는 규칙과 같은 규칙을 직접 넣는다: `-keepattributes Signature`,
  `-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken`, `... class * extends com.google.gson.reflect.TypeToken`.
  Gson 버전을 올리는 방법도 있지만, 다른 동작 변화 없이 문제만 고치려고 규칙 추가를 택했다.
- DTO 보존은 대안 3을 쓴다(`proguard-rules.pro`).
- 저장 모델(`Hook`, `HookDelivery`, `HookLog`, `HttpHeader`)은 모든 생성자 인자에 기본값을 둔다.
  `StoredModelCompatibilityTest`가 이전 형식 JSON을 읽어 이 규칙을 검증한다.

## 결과

- 릴리스 APK에서 훅 저장, 알림 전송, 프리셋 내보내기/가져오기가 정상 동작하는 것을 에뮬레이터에서 확인했다.
- 1.0.36 이후 릴리스 빌드의 프리셋은 소스 코드의 필드 이름(`format`, `hook.url` 등)으로 저장되고 버전이 달라도 가져올 수 있다.
- 1.0.35 이하 릴리스 빌드가 내보낸 프리셋은 이미 난독화된 키로 저장되어 있어 가져올 수 없다. 해당 앱에서 다시 내보낼 방법도 없으므로 훅을 직접 다시 만들어야 한다.
- 저장 모델에 필드를 추가할 때는 반드시 기본값을 주고, 호환성 테스트에 이전 형식 JSON 사례를 추가한다.
