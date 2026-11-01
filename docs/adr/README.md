# Architecture Decision Records

| 번호 | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-custom-http-headers.md) | 훅별 사용자 지정 HTTP 헤더(API 키·토큰) | 채택 (1.0.36) |
| [0002](0002-delivery-retry-and-redirect-policy.md) | 전송 재시도 분류, 재시도 기간, 리다이렉트 미추적 | 채택 (1.0.36) |
| [0003](0003-single-pass-template-and-shared-send-path.md) | 템플릿 한 번 치환과 테스트·실제 전송 경로 통합 | 채택 (1.0.36) |
| [0004](0004-gson-models-and-r8.md) | Gson 저장 모델 규칙과 R8 keep 규칙(릴리스 종료 버그 포함) | 채택 (1.0.36) |

새 ADR은 `NNNN-제목.md`로 추가하고, 배경 → 검토한 대안 → 결정 → 결과(장단점·영향) 순서로 쓴다.
