# NotifyHook

Android 알림을 감지해 지정한 URL로 HTTP POST/PUT 웹훅을 보내는 앱입니다.

- 대상 앱 선택, 본문 템플릿(`{title}`, `{text}`, `{app_name}`, 정규식 변수 `{var_이름}`, 전역 변수 `{global.이름}` 등)
- **HTTP 헤더**: `Authorization: Bearer <토큰>`, `X-API-Key` 같은 인증 헤더나 임의 헤더 추가.
  민감 헤더 값은 화면·로그에서 가려지고 프리셋 내보내기 때 기본적으로 빠집니다.
- 실패한 전송은 재시도 큐에 저장해 네트워크 오류·408/425/429·5xx일 때 24시간 동안 재시도합니다.
  4xx와 리다이렉트(3xx)는 설정을 고쳐야 하므로 재시도하지 않습니다.
- 훅 하나를 `.notif` 프리셋 파일로 내보내고 가져올 수 있습니다.

## 빌드

JDK 17, Android SDK(platform 34)가 필요합니다. `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_HOME`을 설정하세요.

```bash
./gradlew testDebugUnitTest      # 단위 테스트
./gradlew assembleDebug          # 디버그 APK
./build-obfuscated-release.sh    # 난독화 릴리스 APK + mapping.txt (서명 정보 필요)
```

빌드하기 전에 `app/build.gradle.kts`의 버전을 올립니다(`AGENTS.md`).

## 문서

- [아키텍처, 데이터 흐름, 기술 스택](docs/ARCHITECTURE.md)
- [아키텍처 결정 기록(ADR)](docs/adr/README.md)
