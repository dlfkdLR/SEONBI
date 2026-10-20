# 백엔드 B — 기반·구성

> 기준 문서: `docs/개발기획서.md` (v0.4). 이 문서와 기획서가 다르면 **기획서가 맞다**. 기획서에 없는 내용은 여기서 정하지 않는다.

---

## 1. 한 줄 요약

**팀 모두가 올라탈 바닥을 만든다.** 실행 환경(Docker Compose), FastAPI 골격과 헬스 체크(0번), 공통 처리(`X-Client-Id`·오류 형식·DB 세션), 8장 DB 모델 전부, 그리고 결정 구성 API(9~27, 49)를 맡는다.

**왜 B가 먼저 끝나야 하나**

- A(엔진·질문·분석)와 C(자유 입력·결과)의 API는 모두 `{decisionId}` 아래에 있다. 결정 조회 조건(`get_decision`)과 오류 형식이 없으면 아무도 라우터를 못 만든다.
- 8장 모델은 B가 한꺼번에 만든다(15.1). 모델이 없으면 A·C는 DB에 아무것도 저장할 수 없다.
- 질문 시작(31번)은 선택지·기준·값이 있어야 동작한다. 그 데이터를 넣는 API가 B의 14~27번이다.
- 프론트는 1명이라 병목이다. B가 **7장 예시 응답을 돌려주는 스텁 라우터**를 먼저 올려야 프론트가 MSW 없이 바로 붙는다(7.1, 15장 병렬 개발 원칙).

---

## 2. 담당 범위

| 맡는 것 | 맡지 않는 것 (담당) |
|---|---|
| 팀 공통 실행 환경: `docker-compose.yml`, `backend/Dockerfile`, `pyproject.toml`·`uv.lock`, `.env.example`, `.gitignore` (11.2~11.3) | 배포·서버·HTTPS (범위 밖, 12.1) |
| FastAPI 골격, `main.py`, 라우터 등록, 헬스 체크 `GET /api/health`(0번, 15.1) | 로그인·세션·사용자 테이블·레이트 리밋 (범위 밖, 3.1·12.1) |
| `core/`: 설정, `X-Client-Id` 의존성, 공통 오류 형식·핸들러 (7.1) | 상태 전이 서비스 (A, 4.2) — B는 호출만 |
| `db/`: async 엔진·세션·Base, 로컬 `create_all` | 분석 엔진 `engine/` (A) |
| **8장 모델 전부** + 이후 모델 변경 관리 (15.1) | 질문·답변 API 31~38, 분석 API 39~46 (A) |
| 결정 API 9~13, 49 | `LLMClient`·LLM 호출 6종·AI 호출 상한 (A) |
| 선택지·기준·값·조건 API 14~27 | 자유 입력 API 28~30, 결과 API 47~48, P1 확장 라우터 (C) |
| `decisions.version` 증가 = 분석 캐시 무효화 (8.2) | 프론트 API 계층(openapi 타입, TanStack Query 훅) (C) — B는 **지원만** |
| `readiness` 계산 (7.3 11번) | 화면 (프론트) |
| API 통합 테스트 기반 (14장) | 엔진 단위·시뮬레이션 테스트 (A) |

---

## 3. 담당 파일·폴더

13.1 구조 기준. 다른 담당과 파일이 겹치지 않게 영역별로 나눈다(15.1 충돌 방지).

| 경로 | 내용 |
|---|---|
| `docker-compose.yml` | `db`(postgres:16.4, 볼륨 `seonbi-pgdata`, `pg_isready` healthcheck) + `api` |
| `backend/Dockerfile` | `python:3.12-slim` + uv, `uv sync --frozen`, `uvicorn --reload` |
| `backend/pyproject.toml`, `backend/uv.lock` | 패키지 정의·버전 고정 (둘 다 커밋) |
| `backend/.env.example` | 키 없이 도는 기본값 (`LLM_MODEL_FAST=fake:default` 등, 11.3) |
| `.gitignore` | `.env`, 가상환경, 캐시 등. `.env.example`은 제외하지 않음 |
| `backend/app/main.py` | 앱 생성, 라우터 등록, `GET /api/health`(Base URL 밖), 시작 시 `create_all`(local만), 오류 핸들러 등록 |
| `backend/app/core/` | 설정(`DATABASE_URL` 등), `X-Client-Id` 의존성, 오류 응답·핸들러, `get_decision` |
| `backend/app/db/` | async 엔진(`asyncpg`), 세션 의존성, `Base` |
| `backend/app/models/` | 8.1 테이블 전부 |
| `backend/app/schemas/` | B 담당 API(9~27, 49)의 요청·응답 Pydantic 모델, camelCase alias 공통 베이스 |
| `backend/app/routers/` | `decisions.py`, `options.py`, `criteria.py`, `values.py`, `conditions.py` |
| `backend/app/services/` | 위 라우터용 서비스(결정·선택지·기준·값·조건), `version` 증가, 제외 판정, readiness |
| `backend/tests/` | `conftest.py`(테스트 DB·클라이언트·`X-Client-Id` 픽스처), B 담당 API 통합 테스트 |

`backend/alembic/`은 DB 구조가 거의 확정된 뒤에 B가 도입한다(11.2).

---

## 4. 담당 API

공통: 0번을 뺀 모든 경로는 `/api/v1` 아래. `{decisionId}`가 있는 모든 API는 `get_decision`으로 조회하고 없거나 다른 클라이언트면 `404 NOT_FOUND`. `X-Client-Id` 없음·형식 오류는 `400 CLIENT_ID_REQUIRED`. 바디 형식 오류는 `400 VALIDATION_ERROR`(7.1). 상태별 제약은 아래 표(4.2 API별 상태 제약)를 따른다.

| # | 메서드 | 경로 | 우선 | 참조 | 핵심 검증 · 상태 제약 · 오류 |
|---|---|---|---|---|---|
| 0 | GET | `/api/health` | P0 | 7.2, 7.3 0번, 15.1 | **Base URL 밖**, `X-Client-Id` 불필요. `200 {"status": "ok", "db": "ok"}`, DB 연결 실패 시 `503 {"status": "error", "db": "error"}` |
| 9 | POST | `/decisions` | P0 | 7.3 9번, 3.2 | title 1~100자, description ≤1,000자, category enum. options 0~8개 함께 생성. `client_id` 저장, status `DRAFT`, `rng_seed` 생성 → `201`. 응답의 `options`는 **Option 객체**(11번과 같은 형식: `id`·`name`·`description`·`url`·`excluded`·`excludedReasons`) |
| 10 | GET | `/decisions` | P0 | 7.3 10번, 3.2 D-05 | 내 `client_id` + `deleted_at IS NULL`만. 필터는 `?status=` 하나, `&cursor=&limit=20`, `{items, nextCursor}`. `optionCount`, `finalChoice` 포함 |
| 11 | GET | `/decisions/{decisionId}` | P0 | 7.3 11번 | 구성 전체(options·criteria·values·rankings·conditions) + 선택지별 `excluded`/`excludedReasons` + `readiness` |
| 12 | PATCH | `/decisions/{decisionId}` | P0 | 7.3 12번, 3.2 D-03 | `{title?, description?, category?}`. FINALIZED면 409. **version을 올리지 않는다**(분석 입력이 아님, 8.2) |
| 13 | DELETE | `/decisions/{decisionId}` | P0 | 7.3 13번, 4.1, 8.2 | **어느 상태에서든 가능**(FINALIZED 포함). `deleted_at`만 채우는 소프트 삭제, 하위 데이터는 그대로 둔다 → `204`. 실제 행 삭제(이때 FK CASCADE 동작)는 30일 후 정리 작업으로 P2 |
| 49 | POST | `/decisions/{decisionId}/duplicate` | P1 | 7.3 49번, 4.2 | **어떤 상태든 가능**. 제목 생략 시 원제목 + " (복사본)". 복사: 제목·설명·카테고리·선택지·기준(척도 고정 해제)·값·순위·필수 조건. 복사 안 함: 질문·답변·분석·최종 선택·자유 입력. 응답은 11번 형식, `DRAFT` |
| 14 | POST | `/options` | P0 | 7.3 14번, 3.3 | name ≤60자(8.1), 결정당 최대 8개(`409 LIMIT_EXCEEDED`), 이름 중복 불가. **QUESTIONING/ANALYZED에서 추가하면 `rank` 기준마다 새 선택지를 맨 아래 순위 그룹에 자동 배치하고 `"warning": "RANKING_UPDATED"`**. version +1 |
| 15 | GET | `/options` | P0 | 7.3 15번 | `{items: [Option]}` |
| 16 | PATCH | `/options/{optionId}` | P0 | 7.3 16번, 4.3 | 이름 중복 불가. version +1 |
| 17 | DELETE | `/options/{optionId}` | P0 | 7.3 17번, 4.3 | 관련 값 삭제, version +1. **QUESTIONING/ANALYZED에서 삭제해 2개 미만이 되면 `409 INVALID_STATE`** |
| 18 | POST | `/options/recommendations` | **P2** | 7.3 18번, 3.3, 6.2, 12.2 | **A의 LLM 호출 `recommend_options`를 부른다**. 결과는 저장하지 않음. 고르면 14번으로 추가. AI 호출 상한에 포함, 넘으면 `429 AI_QUOTA_EXCEEDED`. 실패 시 기능 숨김(6.2). 맨 마지막에 |
| 19 | POST | `/criteria` | P0 | 7.3 19번, 3.4, 4.2 | name ≤40자·중복 불가, type enum. **결정당 최대 7개, 8개째는 `409 LIMIT_EXCEEDED`**. rating: `levels` 3~7개·각 1~12자·중복 불가(생략 시 기본 5단계), `inputMode`는 rating만(`score` 기본), direction 고정 `higher_better`. boolean: `true_better`. 선택 필드: `scaleMin`·`scaleMax`(numeric만), `source`(`user` 기본 \| `ai_recommended`. `free_text`는 29번에서 서버가 채움). **QUESTIONING/ANALYZED에서 추가하면 A의 상태 전이 서비스로 `QUESTIONING`으로 바꾸고 응답에 `"statusChanged": "QUESTIONING"`**. version +1 |
| 20 | GET | `/criteria` | P0 | 7.3 20번 | 기준 목록 |
| 21 | PATCH | `/criteria/{criterionId}` | P0 | 7.3 21번, 4.3 | `scale_frozen`인데 `scaleMin/scaleMax`·`levels` 개수가 바뀌면 `"warning": "SCALE_CHANGED_AFTER_ANSWERS"`(라벨 이름만 바꾸면 영향 없음). `inputMode` 변경 시 그 기준 값 초기화, score→rank는 기존 점수 순서로 `"prefilledRanking"` 반환. version +1 |
| 22 | DELETE | `/criteria/{criterionId}` | P0 | 7.3 22번, 4.3 | 관련 답변 `is_valid=false` 처리, `{ "invalidatedAnswers": n }` 반환. version +1 |
| 23 | POST | `/criteria/recommendations` | P1 | 7.3 23번, 6.2, 12.2 | **A의 `recommend_criteria` 호출**. 3~6개, 저장하지 않음. 정상은 `"source": "ai"`. **AI 호출 상한 초과나 LLM 실패 시 오류 대신 카테고리별 기본 기준 목록(6.2) + `"source": "template"`** |
| 24 | GET | `/values` | P0 | 7.3 24번 | `{items, rankings, missing}` |
| 25 | PUT | `/values` | P0 | 7.3 25번, 3.4 | 부분 저장 허용. numeric=숫자, rating(score)=1~L 정수, rating(rank)=결정의 **모든 선택지(제외 포함)가 정확히 한 번**, boolean=true/false. 저장 위치 `value_num`/`value_bool`/`value_rank`. 응답은 24번과 같음. version +1 |
| 26 | GET | `/conditions` | P0 | 7.3 26번 | 조건 목록 |
| 27 | PUT | `/conditions` | P0 | 7.3 27번, 3.5 | **목록 전체 교체**. `threshold`(criterionId·operator `<=`/`>=`/`==`·value), `required_tag`(label ≤60자·`satisfiedBy` → `condition_satisfactions`). 항목별 선택 필드 `source`(`user` 기본 \| `ai_recommended`, `free_text`는 29번에서 서버가 채움). 응답에 `excludedOptions`(사유 문장), 전부 제외면 `"warning": "ALL_OPTIONS_EXCLUDED"`. 선택지는 지우지 않음. version +1 |

**API별 상태 제약 (4.2 표 중 B 담당 부분)** — 조회 API(10·11·15·20·24·26)는 모든 상태에서 된다.

| 작업 | `DRAFT` | `QUESTIONING` | `ANALYZED` | `FINALIZED` |
|---|---|---|---|---|
| 구성 수정 (12, 14~17, 19~22, 25, 27) | 가능 | 가능 (4.3 규칙) | 가능 (기준 추가 시 `QUESTIONING`) | `409 DECISION_FINALIZED` |
| 삭제 (13)·복제 (49) | 가능 | 가능 | 가능 | 가능 |

QUESTIONING/ANALYZED에서 B가 따로 챙길 규칙: 14번 `RANKING_UPDATED` 자동 배치, 17번 2개 미만 `409 INVALID_STATE`, 19번 `statusChanged`(위 표). `QUESTIONING → DRAFT` 전이는 없다(4.2).

---

## 5. DB 모델 목록

8.1의 테이블은 **B가 처음에 한꺼번에 전부 만든다.** A·C가 쓰는 테이블도 B가 만들고, 이후 컬럼 변경도 B를 거친다(15.1). 공통: PK `uuid`(앱에서 UUID v7), 시간 `timestamptz`, 결정 하위는 FK CASCADE(8장). 단 결정 삭제(13번)는 `deleted_at`만 채우는 소프트 삭제라 CASCADE가 동작하지 않는다. CASCADE는 P2 정리 작업에서 행을 실제로 지울 때만 쓰인다(4.1, 8.2).

| 테이블 | 주 사용 | 비고 |
|---|---|---|
| `decisions` | B (상태 변경은 A) | `client_id` 인덱스(client_id, updated_at DESC), `status`·`category` CHECK, `rng_seed`, `question_limit`, `version`(분석 캐시 키로만 씀, 8.1), `deleted_at` |
| `options` | B | UNIQUE(decision_id, name), `position` |
| `criteria` | B (척도 고정은 A, C의 29번도 생성) | UNIQUE(decision_id, name), `levels` jsonb, `input_mode`, `scale_min/max`, `scale_frozen`, `source`(`user`/`ai_recommended`/`free_text`) |
| `option_values` | B | PK(option_id, criterion_id), `value_num`/`value_bool`/`value_rank` |
| `conditions` | B (C의 29번도 생성) | `kind` threshold / required_tag, `source` |
| `condition_satisfactions` | B | (condition_id, option_id, satisfied) |
| `questions` | A (C의 29번도 가상 질문 생성) | UNIQUE(decision_id, seq), `spec`·`display` jsonb, `phrasing_source`, **`origin`**(`engine` / `free_text`, 질문 수 계산에서 `free_text`는 뺌), `info_gain` |
| `answers` | A (기준 삭제 시 B가 `is_valid=false`) | `question_id` UNIQUE, `preference_kind` |
| `free_text_inputs` | C | status PENDING/CONFIRMED/REJECTED/IRRELEVANT |
| `analysis_results` | A | `decision_version`이 현재 `version`과 다르면 무효 |
| `estimate_snapshots` | A (P1) | 검증·그래프용 |
| `final_choices` | C | 결정당 하나, `recommendation_snapshot` |
| `ai_usage_logs` | A | INDEX(client_id, created_at), `provider`, 원문 저장 안 함 |

사용자 테이블은 없다(v0.4). 가중치·입자는 저장하지 않는다(8.2). `decisions.version`은 분석 캐시 키 용도뿐이다(8.1). 올리는 규칙은 6.2 참고.

---

## 6. 다른 담당과 맞물리는 곳

아래 시그니처는 모두 **제안 (팀 합의 후 확정)** 이다. 15.1에 따라 함수 시그니처를 먼저 합의하고 구현한다.

### 6.1 B가 제공하는 공통 의존성

| 이름 (제안) | 하는 일 | 쓰는 사람 |
|---|---|---|
| `get_session()` | async DB 세션 의존성 | A·B·C |
| `get_client_id(x_client_id: str = Header(...)) -> UUID` | 헤더 없음·UUID 아님 → `400 CLIENT_ID_REQUIRED` | A·B·C |
| `get_decision(decision_id, client_id, session) -> Decision` | `WHERE id = :id AND client_id = :cid AND deleted_at IS NULL`, 없으면 `404 NOT_FOUND` (12.2) | A·B·C |
| `ApiError(status, code, message, details=None)` + 핸들러 | 7.1 공통 오류 형식 `{error: {code, message, details}}`로 변환. Pydantic 검증 오류도 `400 VALIDATION_ERROR`로 변환 | A·B·C |
| `ensure_not_finalized(decision)` | FINALIZED면 `409 DECISION_FINALIZED`. 13번 삭제·49번 복제에는 쓰지 않는다(4.2) | A·B·C |
| `bump_version(decision)` | `decisions.version += 1`, `updated_at` 갱신. **같은 트랜잭션 안에서** 호출. 12번에서는 부르지 않음(8.2) | A·B·C |
| `CamelModel` | `alias_generator`로 camelCase 변환하는 Pydantic 베이스 (7.1) | A·B·C |
| `compute_readiness(decision)` | `canStartQuestions`, `missing`(`OPTIONS_LT_2`, `CRITERIA_LT_2`, `VALUES_INCOMPLETE`, `RANKING_INCOMPLETE`) | B(11번), A(31번 `422 NOT_READY` 판단) |
| `compute_exclusions(decision)` | 조건으로 선택지별 `excluded`·사유 계산 | B(11·27번), A(엔진 입력), C |

### 6.2 `version` 증가 규칙 (8.2)

- `version`은 분석 캐시 키로만 쓴다(8.1). 선택지·기준·값·조건·**답변**이 바뀌면 같은 트랜잭션에서 `+1`.
- B 담당: 14·16·17·19·21·22·25·27번.
- **12번(제목·설명·카테고리)은 올리지 않는다.** 분석 결과에 영향이 없기 때문이다(7.3 12번, 8.2).
- A 담당: 답변 제출·수정(37·38번). `bump_version`을 같이 쓴다.
- C 담당: 29번 확정으로 선택지·기준·조건·가상 질문 답변이 생기면 올린다. B의 생성 서비스를 쓰면 그 안에서 올라간다(6.5).
- 분석 캐시는 `analysis_results.decision_version != decisions.version`이면 무효. 재계산은 A의 분석 쪽이 한다. B는 **번호만 올린다.**

### 6.3 상태 전이는 A의 서비스로만

- 상태는 서버가 관리하고, B는 `decisions.status`를 **직접 바꾸지 않는다**(4.2).
- 19번 기준 추가(QUESTIONING/ANALYZED): A의 상태 전이 서비스를 호출한다. 제안: `transition_on_criterion_added(session, decision, criterion_id)`. `question_limit` 조정(ANALYZED → 추가 라운드 `답변 수 + 3`, QUESTIONING → `max(현재 상한, 답변 수 + 3)`)과 새 기준 우선 질문은 A가 처리한다(4.2 추가 질문 라운드). B는 status·`question_limit`을 직접 건드리지 않는다.
- 9번 생성 시 `DRAFT`로 만드는 것은 초기값이라 B가 넣는다.
- 척도 고정(`scale_min/max`, `scale_frozen`)은 31번 질문 시작에서 A가 한다. B는 21번에서 frozen 여부만 보고 경고를 붙인다.

### 6.4 LLM 호출 (A ↔ B)

- AI 호출 상한은 사용자가 직접 부르는 18·23·28번만 센다(12.2). B 담당 중에는 18·23번이 여기에 들어간다. 상한 판단과 `ai_usage_logs` 기록은 A의 계층이 처리한다.
- 23번: A의 `recommend_criteria(...)`를 호출. 입력은 결정 제목·목적·카테고리·선택지 이름(6.2). **상한 초과나 LLM 실패여도 오류를 내지 않고** 카테고리별 기본 기준 목록(6.2)을 `"source": "template"`로 돌려준다(정상은 `"source": "ai"`, 7.3 23번).
- 18번(P2): A의 `recommend_options(...)` 호출. 상한 초과는 `429 AI_QUOTA_EXCEEDED`, LLM 실패 시 기능 숨김(6.2, 12.2).
- A의 LLM 계층이 늦으면 23번은 기본 기준 목록(`"source": "template"`)을 돌려주는 상태로 먼저 연결한다.

### 6.5 C·프론트와

- C의 29번 확정은 B의 생성 서비스를 재사용한다(7.3 29번: 추출 항목 형식이 `criterion`=19번 요청, `constraint`=27번 항목, `option`=14번 요청과 같음). 제안: `create_criterion(..., source)`, `create_option(...)`, 조건 추가용 서비스. 그래서 이 서비스들은 다음을 안에서 처리해야 한다.
  - 중복 검사·개수 제한(선택지 8개, 기준 7개 → `409 LIMIT_EXCEEDED`)·`version` +1
  - `source = free_text` 저장(19·27번 요청의 `source`는 `user`/`ai_recommended`만 받는다. `free_text`는 29번에서 서버가 채운다, 7.3 19·27번)
  - 기준 추가 시 19번과 같은 상태 규칙(`statusChanged`), 선택지 추가 시 14번과 같은 `rank` 자동 배치
- 29번의 `preference` 항목은 가상 `slider_pair` 질문(`questions.origin = free_text`)·답변으로 들어간다. B는 `origin` 컬럼을 모델에 넣어 두기만 한다(8.1).
- 프론트 API 계층은 C 담당이다. B는 기반이 끝난 뒤 OpenAPI 스키마가 7장과 맞는지 확인하고, 훅 작성을 **돕기만** 한다.
- 7장 스키마를 바꾸는 PR에는 프론트 담당이 리뷰어로 들어간다(15.1).

---

## 7. 할 일 체크리스트

의존 순서대로 적었다. 위에서부터 진행한다.

### 7.1 공통 환경
- [ ] 모노레포 폴더 구조 만들기 (13.1)
- [ ] `backend/pyproject.toml` 작성 (Python 3.12, FastAPI, SQLAlchemy 2.0 async, asyncpg, Pydantic v2, pytest, httpx, ruff, mypy) → `uv.lock` 생성·커밋
- [ ] `backend/Dockerfile` (`python:3.12-slim` + uv, `uv sync --frozen`)
- [ ] `docker-compose.yml` (`db` postgres:16.4 + healthcheck + 볼륨 `seonbi-pgdata`, `api` depends_on service_healthy, `backend/app` 마운트, `--reload`, 포트 8000·5432)
- [ ] `backend/.env.example` (`DATABASE_URL`, `LLM_MODEL_FAST=fake:default`, `LLM_MODEL_QUALITY`, `AI_DAILY_QUOTA_PER_CLIENT=50`, `ENGINE_PARTICLES=4000`)
- [ ] `.gitignore` (`.env` 제외, `.env.example`은 커밋)
- [ ] 팀원 전원이 `docker compose up --build` 한 번 돌려 보기

### 7.2 골격·공통 처리
- [ ] `main.py` + `GET /api/health`(0번: Base URL 밖, `X-Client-Id` 불필요, DB 확인 → `200`/`503`, 7.3)
- [ ] `core/` 설정 로딩
- [ ] `db/` async 엔진·세션·Base, local에서만 시작 시 `create_all`
- [ ] 공통 오류 형식 + 핸들러 (`ApiError`, 검증 오류 → `VALIDATION_ERROR`)
- [ ] `get_client_id` 의존성 (`CLIENT_ID_REQUIRED`)
- [ ] `get_decision` 의존성 (12.2 조회 조건, 404)
- [ ] `CamelModel`, `ensure_not_finalized`, `bump_version`
- [ ] 공통 의존성 시그니처를 A·C와 합의하고 기록

### 7.3 DB 모델
- [ ] 8.1 테이블 13개 전부 모델로 작성 (CHECK·UNIQUE·INDEX·CASCADE 포함). `decisions.question_limit`, `questions.origin`, `criteria.source`·`conditions.source`, `ai_usage_logs.provider` 빠뜨리지 않기
- [ ] UUID v7 생성 함수
- [ ] `create_all`로 테이블 생성 확인, A·C에 공유

### 7.4 스텁 라우터
- [ ] 7.2의 9~49번 전 경로를 라우터에 등록하고 **7.3 예시 응답을 그대로** 반환 (A·C 담당 경로 포함, 해당 담당이 구현하면 교체). 프론트는 MSW 없이 이 스텁에 붙는다(7.1)
- [ ] 스텁에도 `X-Client-Id` 검사 적용 (0번 헬스 체크는 제외)
- [ ] OpenAPI 스키마가 나오는지 확인 → 프론트·C에 알림

### 7.5 결정 구성 API
- [ ] 9~13번 결정 CRUD (9번 응답 options는 Option 객체, 10번 `status` 필터·커서 페이지네이션, 12번은 version 안 올림, 13번은 어느 상태든 `deleted_at`만 채움)
- [ ] 14~17번 선택지 (개수 제한·중복·FINALIZED 409, QUESTIONING/ANALYZED에서 14번 `rank` 자동 배치 + `RANKING_UPDATED`, 17번 2개 미만 `INVALID_STATE`)
- [ ] 19~22번 기준 (8개째 `LIMIT_EXCEEDED`, levels·inputMode 검증, `scaleMin/scaleMax`·`source` 선택 필드, 19번 상태 전이 연결, 21번 경고·prefilledRanking, 22번 답변 무효화)
- [ ] 24~25번 값 (유형별 검증, rank 검증, missing 계산)
- [ ] 26~27번 조건 (전체 교체, 항목별 `source`, 제외 판정, `ALL_OPTIONS_EXCLUDED`)
- [ ] `readiness`·`excluded` 계산을 11번에 연결
- [ ] 모든 구성 쓰기 경로에 `bump_version` 연결 확인 (12번 제외)
- [ ] C의 29번이 쓸 생성 서비스(`source = free_text`, 개수 제한·상태 규칙·version 포함)를 C와 맞춰 두기
- [ ] 23번 기준 추천 (A의 `recommend_criteria` 연결, 상한 초과·실패 시 기본 목록 + `"source": "template"`)
- [ ] 49번 결정 복제 (P1)
- [ ] 18번 선택지 추천 (P2, 마지막)

### 7.6 테스트 기반
- [ ] `conftest.py`: 테스트 DB(컨테이너), httpx `AsyncClient`, `X-Client-Id` 헤더 픽스처, 결정 생성 헬퍼
- [ ] `docker compose run --rm api uv run pytest`로 돌아가는지 확인
- [ ] 공통 테스트: `CLIENT_ID_REQUIRED`, 다른 클라이언트 ID 404, 오류 형식 (14장)
- [ ] B 담당 API 테스트: 검증 오류, `LIMIT_EXCEEDED`(선택지 9개째·기준 8개째), `DECISION_FINALIZED`, FINALIZED에서도 13·49번 성공, 기준 추가 시 `statusChanged`, `RANKING_UPDATED`, 선택지 2개 미만 `INVALID_STATE`, 구성 쓰기 시 version 증가·12번은 그대로, readiness
- [ ] 헬스 체크 테스트: `X-Client-Id` 없이 `200`
- [ ] 노트북 시나리오(2.2) 구성을 API로 끝까지 하는 테스트

### 7.7 이후 지원
- [ ] DB 구조가 거의 확정되면 Alembic 도입 (`create_all` 대체)
- [ ] A·C의 모델 변경 요청 받아 반영 (PR에 "DB 초기화 필요")
- [ ] C의 프론트 API 계층(openapi 타입·훅) 작업 지원
- [ ] 15.1에 따라 2명 체제가 되면 C 범위(28~30, 47~48)까지 이어받기

---

## 8. 완료 기준

- [ ] 새로 clone한 컴퓨터에서 `docker compose up --build`만으로 `GET /api/health`가 `200 {"status": "ok", "db": "ok"}` (`X-Client-Id` 없이)
- [ ] `X-Client-Id` 없이 요청하면 `400 CLIENT_ID_REQUIRED`, 다른 `X-Client-Id`로 남의 결정을 조회하면 `404`
- [ ] 모든 오류가 7.1 공통 형식(`error.code/message/details`)으로 나감
- [ ] 노트북 시나리오(2.2) 1~4단계(결정 생성 → 선택지 3개 → 기준 → 값 입력 → 필수 조건 "Windows 지원"으로 맥북 제외)가 API만으로 끝까지 가능. 자유 입력(28~29번)은 C 담당이므로 27번 `required_tag`로 직접 넣어 확인
- [ ] 11번 `readiness`가 정확함: 선택지 <2, 기준 <2, 값 미입력, 순위 미입력 각각에서 `missing` 코드가 맞게 나오고, 다 채우면 `canStartQuestions: true`
- [ ] 선택지·기준·값·조건을 바꾸면 `decisions.version`이 정확히 올라가고, 12번 결정 수정에서는 그대로임
- [ ] FINALIZED 결정에 구성 수정 요청 시 `409 DECISION_FINALIZED`, 삭제(13)·복제(49)는 성공
- [ ] 결정 삭제 후 목록·상세에서 빠지고(404), 하위 행은 DB에 남아 있음 (소프트 삭제)
- [ ] QUESTIONING/ANALYZED 규칙: 기준 추가 시 `statusChanged`, 선택지 추가 시 `RANKING_UPDATED`, 2개 미만 삭제 시 `409 INVALID_STATE`
- [ ] 응답 필드가 7.3 예시와 이름·타입이 같음 (camelCase)
- [ ] B 담당 통합 테스트가 Compose 환경에서 통과

---

## 9. 지켜야 할 규칙

**범위**
- 로그인 없음. 사용자 식별은 `X-Client-Id` 헤더(UUID)뿐이다. 다르면 `404`, 없거나 형식이 틀리면 `400 CLIENT_ID_REQUIRED` (3.1).
- 인증·보안·배포는 범위 밖이다. 세션·CSRF·레이트 리밋·사용자 테이블을 만들지 않는다 (12.1).
- 그래도 12.2 최소 규칙은 지킨다: Pydantic 입력 검증, `get_decision` 조회 조건, API 키는 `.env`에만.

**명세**
- 7장 스키마 v1과 8장을 벗어나지 않는다. **없는 필드·없는 API를 추가하지 않는다.**
- 빈틈이 보이면 코드로 메우지 말고 먼저 팀에 공유하고, **기획서부터 수정**한 뒤 구현한다 (변경은 PR + 17장 기록 + OpenAPI 타입 재생성, 7.1).
- 7장 스키마 변경 PR에는 프론트 담당을 리뷰어로 넣는다 (15.1).
- 먼저 7장 예시 응답을 돌려주는 스텁 라우터를 올려 프론트가 MSW 없이 바로 붙게 한다 (7.1, 15장).

**DB**
- DB 구조의 기준은 git의 SQLAlchemy 모델 하나뿐이다. DB에서 직접 테이블을 고치지 않는다 (11.2).
- Alembic은 DB 구조가 거의 확정된 뒤 도입한다. 그 전엔 `create_all`(local만).
- 모델이 바뀌면 `docker compose down -v`로 초기화한다. 모델을 바꾼 PR 설명에 **"DB 초기화 필요"** 를 적는다.
- 모델 변경은 B를 거친다 (15.1).
- 상태(`status`)는 직접 바꾸지 않고 A의 상태 전이 서비스로만 바꾼다 (4.2).
- `version` 증가는 변경과 같은 트랜잭션에서 한다. `version`은 분석 캐시 키로만 쓴다 (8.1·8.2).
- 결정 삭제는 `deleted_at`만 채운다. 하위 행을 지우지 않는다 (4.1·8.2).

**환경**
- Docker Compose는 로컬 개발 환경용이다(`db` postgres:16.4 + `api`). 배포용으로 쓰지 않는다 (11.2).
- `uv.lock`, `.env.example`은 커밋한다. `.env`와 실제 LLM 키는 커밋하지 않는다.

**코드·협업 (13.2)**
- 린트·포맷: `ruff` + `mypy`.
- 커밋: Conventional Commits (`feat:`, `fix:`, `docs:` …).
- 브랜치: `main` ← `dev` ← `b/<작업-이름>`(예: `b/models`), PR 리뷰 1명 이상. 모델·`core/`·스텁은 다른 사람이 기다리므로 작게 먼저 `dev`에 합친다(13.2, `docs/개발환경-세팅.md` 0번).
- API 변경 시 OpenAPI 타입 재생성 커밋 포함.

---

## 10. 참고

| 절 | 내용 |
|---|---|
| 2.2 | 노트북 시나리오 (완료 기준) |
| 3.1 | 사용자 식별 `X-Client-Id` |
| 3.2~3.5 | 결정·선택지·기준·값·조건 기능, 기준 유형·단계 라벨·입력 방식 |
| 4.1 | 엔티티 관계, 소프트 삭제와 CASCADE |
| 4.2 | 상태 머신, API별 상태 제약 표, 추가 질문 라운드 |
| 4.3 | 수정이 분석에 미치는 영향, 척도 고정 |
| 6.2 | `recommend_criteria`·`recommend_options`, 카테고리별 기본 기준 (23번 `template` 대체) |
| 7.1 | 공통 규약, 오류 형식, 오류 코드표 |
| 7.2 | 엔드포인트 전체 목록 (0번 헬스 체크 포함) |
| 7.3 | 클라이언트 식별, 0번·9~27번·49번 상세, 29번 추출 항목 형식 |
| 8.1~8.2 | 테이블(`questions.origin` 포함), version 용도·증가 규칙, 소프트 삭제 |
| 10.2 | 백엔드·DB 기술 스택 |
| 11.2~11.3 | Docker Compose 공통 환경, 환경 변수 |
| 12.1~12.2 | 범위에서 뺀 것, 최소 규칙, AI 호출 상한(18·23·28만) |
| 13.1~13.2 | 저장소 구조, 개발 규칙 |
| 14 | 테스트 전략 (API 통합) |
| 15, 15.1 | 병렬 개발 원칙(스텁 라우터), 역할 분담 |
