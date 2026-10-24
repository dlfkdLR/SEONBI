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
- PostgreSQL 통합 테스트와 OpenAPI 생성 타입.

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

1. `ConfigurationHooks.CriterionAdded` Bean에서 A의 상태 전이 서비스를 호출한다.
   B는 status/question_limit을 직접 바꾸지 않는다. 연결 전에는 QUESTIONING/ANALYZED의
   기준 추가를 INVALID_STATE로 거절하고 트랜잭션을 되돌린다. 테스트에서만 가짜 훅을 주입해
   호출 계약을 검증한다. DRAFT의 기준 추가는 정상 동작한다.
2. `ConfigurationHooks.Recommendations` Bean에서 A의 AI 계층을 호출한다.
   기준 추천은 연결 전·장애·쿼터 초과 시 템플릿 반환. P2 선택지 추천은 연결 전 503
   AI_UNAVAILABLE. 호출 상한과 사용량 기록은 A의 AI 계층에서 처리한다.

계약 이름은 PR 검토 제안이며 팀 합의가 끝났다는 뜻이 아니다.

## OpenAPI 타입 재생성

`./gradlew test`가 backend/build/openapi.json을 만든다.
저장한 backend/openapi.json과 frontend/src/api/schema.d.ts는 B API 범위이다.

```sh
npx --yes openapi-typescript@7.10.1 backend/openapi.json -o frontend/src/api/schema.d.ts
```

프론트 쿼리 훅·화면은 C와 프론트 담당 범위이므로 포함하지 않는다.
