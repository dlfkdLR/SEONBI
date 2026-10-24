# API 계약 기준

최종 사용자 지시: 첨부 API 명세의 경로와 메서드 유지, 로그인 기능 제외.

`src/api/endpoints.ts`의 30개 경로는 첨부 CSV/PDF에서 옮겼습니다. 비교 검증용 원본 행은 `tests/contracts/api-spec.json`에 보관합니다.

## 연결 상태

현재 화면은 localStorage와 로컬 참조 계산을 사용합니다. 아래 요청 계층은 명세에 맞춰 준비했으나, 화면 전체가 서버와 연결된 상태는 아닙니다. 환경변수만 입력하면 연결이 완료된다고 안내하지 않습니다.

API 명세에 요청/응답 필드가 정의되어 있지 않아 본문은 JSON 값, 응답은 `unknown`으로 유지했습니다. 백엔드 DTO는 확인했지만, 실제 경로/메서드부터 명세와 달라서 그 브랜치의 형식을 확정된 계약으로 강제하지 않았습니다. 서버가 명세와 일치하는 버전으로 통합된 뒤 DTO·OpenAPI로 타입과 UI 어댑터를 연결해야 합니다.

## 확인된 차이

| 기능 | 첨부 API 명세 · 프론트 요청 계층 | 현재 백엔드 코드 |
| --- | --- | --- |
| 평가 기준 추천 | GET | POST |
| 선택지 추천 | GET | POST |
| 필수 조건 설정 | PATCH | PUT |

백엔드 `backend-c`의 [ConfigurationController](https://github.com/dlfkdLR/SEONBI/blob/d5aa9149929041b5a46ad86ac354db838c2bcefd/backend/src/main/java/com/seonbi/web/ConfigurationController.java)를 확인했습니다. 사용자 지시대로 프론트 요청 계층은 왼쪽 열을 유지합니다.

또한 [ResultController](https://github.com/dlfkdLR/SEONBI/blob/d5aa9149929041b5a46ad86ac354db838c2bcefd/backend/src/main/java/com/seonbi/web/ResultController.java)의 확정·내보내기는 스텁입니다. 프론트의 요청 성공을 데이터 영속화나 실제 분석 완료로 간주하면 안 됩니다.

## 명세에서 추가 확인할 부분

- 선택지 × 평가 기준 값의 저장/조회 API가 없습니다. 임의로 `/values` 경로를 추가하지 않았습니다.
- 단일 결정의 전체 구성 조회 API가 없습니다. 임의로 GET `/decisions/{decisionId}`를 추가하지 않았습니다.
- 질문 조기 종료, 답변 목록, 결정 복제 API가 없습니다. 현재 로컬 기능과 서버 API를 구분해야 합니다.
- 본문 필드, 응답 필드, 페이지네이션, 파일 형식은 첨부 API 명세에서 확정되지 않았습니다.
- 진행률 조회는 개발 선택사항이고 선택지 추천은 확인 필요 상태입니다.
- `X-Client-Id`는 로그인 제외 개발기획서의 브라우저 식별 규칙을 유지합니다. 인증 토큰이나 쿠키를 만들지 않습니다.

## 사용 예

```ts
import { api } from './src/api/client';

const result: unknown = await api.request('listOptions', {
  params: { decisionId: '서버가 반환한-결정-ID' },
});

// 응답 스키마 확정 후 검증·변환하여 화면 모델에 연결합니다.
```

`.env.local`의 `VITE_API_BASE_URL`에는 `/api/v1`을 붙이지 않은 서버 주소를 입력합니다. 예: `http://127.0.0.1:8000`.

## 원본 API 목록

| 기능 | 요청 | 경로 | 상태 |
| --- | --- | --- | --- |
| 결정 생성 | POST | `/api/v1/decisions` | 초안 |
| 결정 수정 | PATCH | `/api/v1/decisions/{decisionId}` | 초안 |
| 결정 삭제 | DELETE | `/api/v1/decisions/{decisionId}` | 초안 |
| 선택지 추가 | POST | `/api/v1/decisions/{decisionId}/options` | 초안 |
| 선택지 조회 | GET | `/api/v1/decisions/{decisionId}/options` | 초안 |
| 선택지 수정 | PATCH | `/api/v1/decisions/{decisionId}/options/{optionId}` | 초안 |
| 선택지 삭제 | DELETE | `/api/v1/decisions/{decisionId}/options/{optionId}` | 초안 |
| 평가 기준 추가 | POST | `/api/v1/decisions/{decisionId}/criteria` | 초안 |
| 평가 기준 조회 | GET | `/api/v1/decisions/{decisionId}/criteria` | 초안 |
| 평가 기준 수정 | PATCH | `/api/v1/decisions/{decisionId}/criteria/{criterionId}` | 초안 |
| 평가 기준 삭제 | DELETE | `/api/v1/decisions/{decisionId}/criteria/{criterionId}` | 초안 |
| 필수 조건 설정 | PATCH | `/api/v1/decisions/{decisionId}/conditions` | 초안 |
| 질문 시작 | POST | `/api/v1/decisions/{decisionId}/questions/start` | 초안 |
| 다음 질문 조회 | GET | `/api/v1/decisions/{decisionId}/questions/next` | 초안 |
| 질문 답변 제출 | POST | `/api/v1/decisions/{decisionId}/answers` | 초안 |
| 질문 답변 수정 | PATCH | `/api/v1/decisions/{decisionId}/answers/{answerId}` | 초안 |
| 질문 진행률 조회 | GET | `/api/v1/decisions/{decisionId}/questions/progress` | 개발 선택사항 |
| 질문 종료 판단 | GET | `/api/v1/decisions/{decisionId}/questions/status` | 초안 |
| 평가 기준 추천 | GET | `/api/v1/decisions/{decisionId}/criteria/recommendations` | 초안 |
| 선택지 추천 | GET | `/api/v1/decisions/{decisionId}/options/recommendations` | 확인 필요 |
| 분석 결과 조회 | GET | `/api/v1/decisions/{decisionId}/analysis` | 초안 |
| 가중치 조회 | GET | `/api/v1/decisions/{decisionId}/weights` | 초안 |
| 최종 순위 조회 | GET | `/api/v1/decisions/{decisionId}/rankings` | 초안 |
| 민감도 분석 | GET | `/api/v1/decisions/{decisionId}/sensitivity` | 초안 |
| 가중치 시뮬레이션 | POST | `/api/v1/decisions/{decisionId}/simulations` | 초안 |
| 결정 안정성 분석 | GET | `/api/v1/decisions/{decisionId}/stability` | 초안 |
| 결정 변경 조건 분석 | GET | `/api/v1/decisions/{decisionId}/change-conditions` | 초안 |
| 최종 결정 확정 | POST | `/api/v1/decisions/{decisionId}/final-choice` | 초안 |
| 결정 기록 조회 | GET | `/api/v1/decisions` | 초안 |
| 결정 결과 내보내기 | GET | `/api/v1/decisions/{decisionId}/export` | 초안 |
