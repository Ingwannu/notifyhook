# ADR 0001: 훅별 사용자 지정 HTTP 헤더(API 키·토큰)

- 상태: 채택 (1.0.36)
- 관련 코드: `model/HttpHeader.kt`, `model/HttpHeaderValidator.kt`, `model/HttpHeaderMasking.kt`,
  `service/HookDeliverySender.kt`, `ui/HeaderEditorAdapter.kt`, `ui/HookEditActivity.kt`, `data/HookStorage.kt`

## 배경

웹훅을 받는 서버 대부분은 `Authorization: Bearer <토큰>`이나 `X-API-Key` 같은 헤더로 인증한다.
기존에는 `Content-Type` 외에 헤더를 지정할 방법이 없어서, 인증이 필요한 서버로는 보낼 수 없었다.

제약:
- 헤더 값에는 비밀(키·토큰)이 들어간다. 화면, 로그, 프리셋 파일로 새면 안 된다.
- OkHttp는 비ASCII 문자나 줄바꿈이 든 헤더를 전송 시점에 예외로 거부한다. 예외는 네트워크 오류로 분류되어 재시도 큐에 계속 남는다.
- 기존 저장 데이터(훅, 재시도 큐, 로그)와 이전 프리셋 파일을 그대로 읽을 수 있어야 한다.

## 검토한 대안

| 항목 | 대안 | 판단 |
| --- | --- | --- |
| 헤더 모델 | ① 인증 방식 전용 필드(Bearer/API Key/Basic) ② 이름-값 목록 + 민감 플래그 | ② 채택. 서버마다 헤더 이름이 달라 범용 목록이 필요하다. 자주 쓰는 형식은 편집 화면의 "빠른 추가"(Bearer, X-API-Key)로 제공한다 |
| 헤더 값 템플릿 | ① 고정 값만 ② `{title}` 등 템플릿 허용 | ① 채택. 알림 내용이 헤더에 들어가면 비ASCII·줄바꿈 때문에 전송 시점에야 실패하고, 알림 내용이 인증 헤더 쪽으로 섞이는 경로가 생긴다. 필요해지면 ASCII 강제 인코딩과 함께 별도로 검토한다 |
| 저장 방식 | ① 앱 전용 SharedPreferences 평문(백업 제외) ② Android Keystore AES-GCM 암호화 ③ EncryptedSharedPreferences | ① 채택. 루팅되지 않은 기기에서는 앱 전용 저장소를 다른 앱이 읽을 수 없고, 백업·기기 이전 대상에서 이미 빠져 있다. ②는 Keystore 키가 무효화되면(잠금 화면 변경, 일부 제조사 버그) 훅 설정을 통째로 잃을 위험이 있다. ③은 androidx.security-crypto가 지원 중단(deprecated)되었다 |
| 재시도 시 헤더 | ① 요청 생성 시점 헤더를 HookDelivery에 고정 ② 재시도 때 현재 훅의 헤더를 다시 읽기 | ① 채택. URL은 생성 시점 값으로 고정되는데 헤더만 최신 값을 쓰면, 사용자가 URL을 다른 서버로 바꾼 뒤 이전 URL로 가는 재시도에 새 서버의 키가 실려 나갈 수 있다. 대신 키를 바꿔도 이미 큐에 있는 요청은 이전 키로 간다. 401 같은 4xx는 재시도하지 않으므로(ADR 0002) 오래 남지는 않는다 |
| 검증 규칙 | ① OkHttp에 넣어 보고 예외 처리 ② OkHttp 문자 규칙만 복제 ③ 이름은 RFC 9110 token, 값은 OkHttp 규칙 + 관리 헤더 차단 | ③ 채택. OkHttp보다 같거나 엄격하므로 저장된 헤더는 반드시 전송된다. 원인별로 오류 문구를 보여 줄 수 있다 |

## 결정

- `Hook.headers: List<HttpHeader(name, value, sensitive)>`를 추가한다. 같은 이름의 헤더를 여러 개 둘 수 있다(`addHeader`).
- 검증(`HttpHeaderValidator`): 최대 50개. 이름은 token 문자만, 최대 256자. 값은 탭과 ASCII 0x20..0x7E만, 최대 8KB.
  `Content-Type`(화면의 선택 항목과 충돌), `Content-Length`, `Transfer-Encoding`(OkHttp가 계산)은 지정할 수 없다.
  저장(`HookStorage.saveHook`), 프리셋 가져오기, 즉시 전송, 재시도 전송에서 모두 같은 규칙을 쓴다.
- 민감 판단(`HttpHeaderMasking.shouldMask`): 사용자 플래그, 또는 이름에 auth/key/token/secret/password/cookie/session/signature/credential이 들어가는 경우.
  - 편집 화면: 값을 비밀번호 입력란처럼 가린다(보기 토글 제공). 이름이 민감해 보이면 사용자가 스위치를 만지기 전까지 자동으로 켠다.
  - 로그(`HookLog.requestHeaders`)와 테스트 결과: 이름은 보여 주고 값은 `********`로 고정 마스킹한다. 길이도 노출하지 않는다.
  - 프리셋 내보내기: 민감 값이 있으면 "값 빼고 내보내기(기본)/값 포함"을 묻는다. 값을 빼고 내보낸 프리셋을 가져오면 훅을 꺼진 상태로 추가하고 안내한다.
  - URL이 `http://`인데 민감 헤더가 있으면 URL 입력란 아래에 평문 전송 경고를 띄운다(차단하지 않음, 사설망 서버 때문).
- 프리셋 형식 버전은 1을 유지한다. `headers` 필드가 없으면 헤더 없음으로 읽는다. 이전 앱 버전은 모르는 필드를 무시하므로 헤더만 빠진 채 가져온다.

## 결과

장점
- 인증이 필요한 웹훅 서버로 보낼 수 있다. 테스트 전송도 같은 헤더로 보내 결과가 실제 전송과 같다.
- 저장된 헤더 때문에 전송 시점 예외가 나지 않고, 키가 로그·공유 파일로 새는 기본 경로가 막힌다.

단점과 영향
- 헤더 값과 재시도 큐는 기기 안에 평문으로 저장된다. 루팅된 기기나 디버그 빌드의 `run-as`로는 읽을 수 있다.
- 한글 등 비ASCII 헤더 값은 쓸 수 없다(대부분의 API 키·토큰은 ASCII라 실사용 영향은 작다).
- 이름 휴리스틱 때문에 비밀이 아닌 헤더(예: `X-Session-Id`)도 로그에서 가려질 수 있다.
