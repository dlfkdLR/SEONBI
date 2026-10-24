# 백엔드 B 구현 및 연결 지점

기준: origin/dev 74730d1의 개발기획서 v0.5와 역할/백엔드-B.md.

## 포함 범위

- Java 21 / Spring Boot 3 / JPA / Gradle Wrapper 및 의존성 잠금.
- Compose(PostgreSQL 16.4 + API), 헬스 체크, 클라이언트 ID 검사, 공통 오류.
- 8장 엔티티·리포지토리 13개(외래키 CASCADE, CHECK, UNIQUE, 인덱스).
- B 담당 API 0, 9~27, 49의 컨트롤러·DTO 및 구성 서비스.
- 결정 소유 조회, 소프트 삭제, 구성 복제, 커서 페이지네이션.
- 값·동순위 검증, readiness, 필수 조건 제외 판정, version 증가.
- 기준 삭제 시 답변 무효화, score→rank 미리 채운 순위, 척도 변경 경고.
- 기준 추천의 카테고리별 템플릿 fallback.
- PostgreSQL 통합 테스트와 백엔드 OpenAPI 명세 파일.

기존 backend/app Python 코드는 변경하지 않았다. A의 이동 작업과 충돌하지 않도록
Spring 소스는 backend/src에 추가했다. A·C 담당 API 스텁은 이번 B 전용 변경에서 제외했다.

## 실행

```sh
cp backend/.env.example backend/.env
docker compose up --build
```

GET /api/health, /swagger-ui, /v3/api-docs를 확인한다.
DB 모델 변경이 포함되므로 **DB 초기화 필요**. 기존 데이터가 필요 없는 팀 로컬 DB에서만
`docker compose down -v` 후 다시 실행한다.

Java 21과 Docker가 있으면 backend에서 `./gradlew test spotlessCheck`로 검증한다.
통합 테스트의 docker-java API 버전은 Docker 29와 호환되는 1.44를 사용한다.

## A·C가 검토할 공통 계약

- `@ClientId UUID clientId`: /api/v1/** 헤더 검사. 헬스 체크·OpenAPI 문서는 제외.
- `FlowError(HttpStatus, String, String, Map<String,Object>)`: error.code/message/details.
- `DecisionResolver.get(decisionId, clientId)`: 삭제되지 않은 내 결정만 조회.
- `ensureNotFinalized(decision)`: 구성 수정 전 검사.
- `bumpVersion(decision)`: 활성 트랜잭션 필수. 변경 트랜잭션 안에서 호출.
- `Ids.next()`: UUID v7 생성.
- `ConfigurationService.computeReadiness` / `computeExclusions`: A에서 사용.
- `createOption` / `createCriterion` / `appendCondition`: C의 자유 입력 확정에서 재사용.
  C는 전체 확정을 하나의 트랜잭션으로 묶는다. 기준·조건은 origin="free_text"로 전달한다.
- 엔티티 연관관계는 JPA 객체, JSON 필드는 Map/List, 시간은 Instant.

## 아직 A 구현과 연결해야 하는 부분

이 PR은 A의 상태 전이·AI 기능을 구현하지 않는다.

1. `ConfigurationHooks.CriterionAdded` Bean의 `String onCriterionAdded(Decision, UUID)`에서 A의 상태 전이 서비스를 호출한다.
   반환값을 statusChanged에 그대로 사용한다. 상태가 바뀌지 않으면 null이다.
   B는 status/question_limit을 직접 바꾸지 않는다. 연결 전에는 QUESTIONING/ANALYZED의
   기준 추가를 INVALID_STATE로 거절하고 트랜잭션을 되돌린다. 테스트에서만 가짜 훅을 주입해
   호출 계약을 검증한다. DRAFT의 기준 추가는 정상 동작한다.
2. `ConfigurationHooks.Recommendations` Bean에서 A의 AI 계층을 호출한다.
   기준 추천은 연결 전·장애·쿼터 초과 시 템플릿 반환. P2 선택지 추천은 연결 전 503
   AI_UNAVAILABLE. 호출 상한과 사용량 기록은 A의 AI 계층에서 처리한다.

계약 이름은 PR 검토 제안이며 팀 합의가 끝났다는 뜻이 아니다.

## OpenAPI 산출물 소유

- B는 백엔드 API와 `backend/openapi.json`까지 관리한다.
- `./gradlew test`가 만든 `backend/build/openapi.json`을 확인해 `backend/openapi.json`에 반영한다.
- `frontend/src/api/schema.d.ts` 생성·커밋과 프론트 API 계층은 C 담당이다.
- B는 프론트 타입 파일을 생성하거나 커밋하지 않는다. API 변경 시 C가 갱신한 명세로 타입을 재생성한다.
- PR #1에서 B가 추가했던 타입 파일은 소유 범위를 분명히 하기 위해 제거했다.

C의 타입 재생성 명령 예시:

```sh
npx --yes openapi-typescript@7.10.1 backend/openapi.json -o frontend/src/api/schema.d.ts
```

## PR #1 추가 검수 반영

- 406/415는 HTTP 상태를 유지하고 Content-Type application/json을 명시해 공통 오류 본문을 반환한다.
- require(조건, 필드명)으로 서비스 입력 검증에 필드별 details를 제공한다.
- 단계형 score의 범위 축소가 기존 점수를 무효하게 만들면 400으로 거절하고 전체 변경을 롤백한다.
- 기준 추가는 A의 String onCriterionAdded 반환 계약 사용. 기준 삭제 시 답변 무효화는 B만 수행한다.
- 목록은 결정 조회·선택지 수 집계·최종 선택 투영의 3쿼리로 처리한다. 목록당 엔티티 조회 반복을 제거했다.
- 필수 결정 컬럼 4개에 NOT NULL 추가. DB 초기화 필요.
- 문서의 오류 코드 표·19/21/29번·8.1 및 17.8을 PR 검토 대상으로 함께 정리했다. 병합은 하지 않는다.
