# 백엔드 A — 분석 엔진 · 질문 흐름 · 분석 · AI 계층

> 기준 문서: `docs/개발기획서.md` (v0.4). 이 문서와 기획서가 다르면 **기획서가 맞다.**
> 괄호 안 숫자(예: (5.10))는 기획서 절 번호다.

---

## 1. 한 줄 요약

**사용자 답변으로 가중치를 추정하고, 다음 질문을 고르고, 결과(순위·안정성·전환 조건)를 계산해 API로 내보낸다. LLM은 문장만 맡도록 감싼다.**

맡는 이유: 선비의 차별점(적응형 질문, 1위 확률, 전환 조건, 숨은 선호)이 전부 엔진과 질문 흐름에 있다(1.5, 5장). 상태 전이(4.2)도 질문 시작·종료와 붙어 있어서 한 사람이 같이 맡아야 어긋나지 않는다. 그래서 팀에서 가장 무거운 역할이다(15.1).

---

## 2. 담당 범위

| 구분 | 맡는 것 (A) | 맡지 않는 것 (누구 몫) |
|---|---|---|
| 엔진 | `engine/` 7개 모듈 마무리, v0.4 수정(추가 라운드·아직 나오지 않은 기준 우선), 5.13 파라미터를 모듈 상수로 정리 | — |
| 변환 | DB 행(기준 ID·선택지 ID) ↔ 엔진 입력(인덱스·배열) 변환 | DB 모델 정의 자체 — **B** |
| 상태 | 상태 전이 서비스(4.2): `DRAFT→QUESTIONING`, `QUESTIONING→ANALYZED`(37·32·35번), `ANALYZED→QUESTIONING`(추가 질문 라운드), 질문 중 기준 추가 시 `question_limit` 확장, API별 상태 제약(4.2 표) 검사 | `ANALYZED→FINALIZED`를 일으키는 47번 라우터 — **C** (전이 함수는 A 것을 호출) |
| API | 질문·답변 31~38, 분석 39~46 (42·43·46 포함) | 결정 9~13·49, 선택지·기준·값·조건 14~27 — **B** / 자유 입력 28~30, 결과 47~48 — **C** |
| 캐시 | `analysis_results` 계산·저장·읽기 | `decisions.version` +1 처리(캐시 무효화 트리거) — **B** |
| AI | `LLMClient`·`LLMRouter`·어댑터(Fake·Gemini), LLM 호출 6종(6.2), 숫자 검증, AI 호출 상한(사용자가 직접 부르는 18·23·28만 센다), 자동 호출 결과 캐시, `ai_usage_logs` 기록 | 23·18번 추천 라우터 — **B**, 28~30 라우터 — **C** (둘 다 A의 호출 함수를 쓴다) |
| 기반 | — | FastAPI 골격, `core/`(설정·`X-Client-Id`·오류 형식), 8장 모델 전부, 통합 테스트 기반 — **B** |
| 프론트 | — | 9장 화면 — **프론트** / openapi 타입·TanStack Query 훅 — **C** |
| 실험 | 가상 사용자 시뮬레이션 노트북, PyMC 교차검증, 범용 AI 비교 실험(1.5, 14) | 파일럿 운영 — 팀 공동 |

---

## 3. 담당 파일·폴더

13.1 구조 기준. `services/` 파일 이름은 제안이고 B와 합의 후 확정한다.

| 경로 | 내용 |
|---|---|
| `backend/app/engine/` | `normalize.py`, `likelihood.py`, `posterior.py`, `question_selector.py`, `stopping.py`, `decision_analysis.py`, `hidden_preference.py` (이미 있음). 5.13 파라미터는 별도 설정 파일 없이 **각 모듈 맨 위 상수**로 둔다(5.13) |
| `backend/app/services/` 중 A 몫 | 상태 전이, 엔진 입력 조립(ID↔인덱스), 질문 흐름, 분석·캐시, AI 호출 상한 (예: `state_machine.py`, `engine_bridge.py`, `questioning.py`, `analysis.py`, `ai_quota.py` — 이름은 제안) |
| `backend/app/routers/questions.py`, `answers.py`, `analysis.py` | 31~46번 라우터 |
| `backend/app/schemas/` 중 A 몫 | 질문 객체·답변·분석 응답 Pydantic 스키마 (7.3 형식 그대로) |
| `backend/app/ai/` | `LLMClient` 인터페이스, `LLMRouter`, 호출 6종 함수, 출력 스키마, `prompts/` |
| `backend/app/ai/providers/` | `fake`, `gemini` 어댑터 (openai·anthropic은 필요할 때 추가) |
| `backend/tests/` | 엔진 단위 테스트(스크래치 폴더에서 이전), 질문·분석 API 통합 테스트, AI 계층 테스트 |
| `backend/notebooks/` | 가상 사용자 시뮬레이션, PyMC 비교, 범용 AI 비교 실험 |

`models/`(8장)는 읽기만 한다. 고칠 일이 생기면 B에게 요청한다(11.2, 15.1).

---

## 4. 담당 API

모두 `/api/v1/decisions/{decisionId}` 아래 경로다(7.1). 응답 형식은 7.3 그대로 따른다. 상태별 허용·오류는 4.2 **API별 상태 제약** 표를 따른다.

| # | 메서드 | 경로 | 우선 | 참조 | 핵심 동작 / 주의점 |
|---|---|---|---|---|---|
| 31 | POST | `/questions/start` | P0 | 4.2, 7.3, 5.5 | `readiness.canStartQuestions` 아니면 `422 NOT_READY`(missing 포함). 척도 고정 → 초기 슬라이더 사슬 → `QUESTIONING`, `question_limit = max_questions(K)`. 이미 QUESTIONING이면 현재 질문 반환(멱등). ANALYZED면 추가 라운드(`round: "extra"`, `question_limit = 답변 수 + 3`, 시드·척도 유지). FINALIZED면 `409 DECISION_FINALIZED`. 응답 `progress.expectedRange` = 첫 라운드 `[3, max_questions(K)]`, 추가 라운드 `[현재 답변 수 + 1, question_limit]`. 가상 답변(29번)은 `answered`에 넣지 않는다 |
| 32 | GET | `/questions/next` | P0 | 4.2, 5.5, 5.10 | 미답변 질문이 있으면 그것을 반환(새로고침해도 같은 질문). 없으면 종료 판단 후 새 질문 생성·저장. 종료면 `{done: true, reason}`이고, **종료 조건을 처음 확인한 것이 32번이면 이때 `ANALYZED`로 전이**한다. `ANALYZED`·`FINALIZED`에서는 항상 `done: true`, `DRAFT`는 `422 NOT_READY`. 가상 질문(`origin = free_text`)은 이미 답이 있으므로 반환하지 않는다 |
| 33 | GET | `/questions/status` | P0 | 5.4, 5.10 | `shouldStop`, `reason`, `metrics`(답변 수·1위·`P_best`·`H`·최고 EIG), `remainingUncertainty`. 조회만 하고 상태는 바꾸지 않는다. `DRAFT`는 `422 NOT_READY` |
| 34 | GET | `/questions/progress` | P2 | 7.3 | `answered`, `expectedRange`(31번과 같은 계산), `confidence`. 전체 질문 수가 없으므로 답변 수 + 확신도로만 표현. `DRAFT`는 `422 NOT_READY` |
| 35 | POST | `/questions/stop` | P1 | 4.2, 5.10 | `QUESTIONING`에서만 된다(`DRAFT`·`ANALYZED`는 `409 INVALID_STATE`, `FINALIZED`는 `409 DECISION_FINALIZED`). `USER_STOPPED`로 `ANALYZED` 전이. `lowConfidence`는 `P_best < 0.55`이고 39번 같은 이름 필드와 값이 같다 |
| 36 | GET | `/answers` | P1 | 7.3 | 모든 상태에서 된다. 질문 객체 포함 목록, `question.origin`은 `engine` \| `free_text`. 무효 답변도 `isValid=false`로 보여 준다 |
| 37 | POST | `/answers` | P0 | 5.2, 5.3, 4.2 | `QUESTIONING`에서만 된다(`DRAFT`·`ANALYZED`는 `409 INVALID_STATE`, `FINALIZED`는 `409 DECISION_FINALIZED`). 응답 형식이 질문 유형과 다르면 `400`, 이미 답했으면 `409 QUESTION_ALREADY_ANSWERED`. 저장 → 전체 재추정 → `estimate` + `next` 함께 반환. `next.done=true`면 같은 트랜잭션에서 `ANALYZED` 전이. `preference_kind` 저장 |
| 38 | PATCH | `/answers/{answerId}` | P1 | 4.2, 4.3 | 기존 행 갱신 후 유효 답변 전체로 처음부터 재추정. 37번과 같은 `estimate` 반환. `QUESTIONING`·`ANALYZED`에서 된다(ANALYZED는 상태 유지). `DRAFT`는 `409 INVALID_STATE`, `FINALIZED`는 `409 DECISION_FINALIZED` |
| 39 | GET | `/analysis` | P0 | 5.4~5.8, 6.2 | 결과 화면 1회 로드용 종합. 캐시 키는 `decisions.version`. `ANALYZED`·`FINALIZED`(확정 시점 결과 조회)에서 된다. `QUESTIONING`은 답변 1개 이상이면 `provisional: true`, 0개면 `422 NOT_READY`. `DRAFT`는 `422 NOT_READY`. `explanation.source`는 `ai`/`template`. `lowConfidence`(5.10 배너 조건, 35번과 같은 값). `hiddenPreferences[].strength = min(진술 쪽 P(w_a > w_b), 1 − 드러난 쪽 P(w_a > w_b))` |
| 40 | GET | `/weights` | P0 | 5.4, 5.6 | 기준별 평균·10~90% 구간, `intervalLevel: 0.8`. `statedRank`·`revealedRank` = 진술/드러난 답변만으로 다시 계산한 평균 가중치 기준 순위(1 = 가장 중요), 해당 답변이 없으면 `null` |
| 41 | GET | `/rankings` | P0 | 5.1, 5.4 | 사후 평균 가중치 기준 점수(0~1)·순위·`topProbability`. 제외된 선택지는 빠진다 |
| 42 | GET | `/scores` | P1 | 5.1 | 선택지 × 기준 `rawValue`·`normalized`·`contribution(=w_k·v_ik)` |
| 43 | GET | `/sensitivity` | P1 | 5.9 | `criterionId` 생략 시 전체 기준. 21개 점 + 교차점 |
| 44 | GET | `/stability` | P0 | 5.7 | `level`은 `P_best`로만(0.80/0.55), 엔진 `high/medium/low` → API `HIGH/MEDIUM/LOW`. 섭동 유지율은 `localPerturbationKeepRate`로 보조 표시. `competitors` = 1위가 아닌 선택지 중 `P_best ≥ 0.05`를 확률 내림차순. `text`는 등급별 템플릿(`HIGH`: "가중치가 조금 달라져도 1위가 유지될 가능성이 높아요." / 그 외: "{가장 가까운 전환 조건의 기준}을(를) 얼마나 중시하는지에 따라 {1순위 경쟁 선택지}(으)로 바뀔 수 있어요.") |
| 45 | GET | `/change-conditions` | P0 | 5.8 | 평균 가중치 기준 닫힌 해. 기준마다 현재 값에 더 가까운 전환점 1개. 없으면 `threshold: null` + "유지" 문장. `type: value`는 P1(아직 엔진에 없음) |
| 46 | POST | `/simulations` | P1 | 5.1, 7.3 | 저장하지 않는 순수 계산. 상태 제약은 분석 조회와 같다(`FINALIZED`도 가능). 합이 1이 아니어도 정규화. `changedFromCurrent` 반환. 빠르게(디바운스 150ms 전제) |

공통: `{decisionId}` 조회는 B가 만든 `get_decision(decision_id, client_id)` 의존성을 그대로 쓴다(12.2). 오류는 B의 공통 오류 형식으로 낸다(7.1).

---

## 5. 다른 담당과 맞물리는 곳 (인터페이스)

> 아래 시그니처는 모두 **제안**이다. **시그니처는 팀 합의 후 확정**한다(15.1 "담당 간 연결 지점"). 이름·필드는 기획서 용어를 따랐고, 새 API나 새 DB 필드는 만들지 않는다.

### 5.1 상태 전이 서비스 (A 제공 → B·C 호출)

B의 19·22번(기준 추가·삭제), C의 29번(자유 입력 확정으로 기준 추가)·47번(확정)이 상태를 바꿀 때 직접 `status`를 쓰지 않고 이 함수를 부른다.

```python
# 제안 — services/state_machine.py
async def start_questions(session, decision) -> StartResult          # 31번. 최초/추가 라운드 구분, question_limit 설정
async def finish_questions(session, decision, reason: StopReason)    # 37번 next.done / 32번 done / 35번
async def on_criterion_added(session, decision, criterion_id) -> str | None
    # ANALYZED면 QUESTIONING으로(추가 라운드, question_limit = 답변 수 + 3)
    # QUESTIONING이면 question_limit = max(현재 상한, 현재 답변 수 + 3)
    # 반환값은 statusChanged 값 (19·29번 응답용)
async def on_criterion_deleted(session, decision, criterion_id) -> int
    # 관련 답변 is_valid=false, 무효화한 수 반환 (22번 invalidatedAnswers)
async def finalize(session, decision, option_id, memo) -> FinalChoice # 47번. ANALYZED에서만, recommendation_snapshot 채움
def assert_editable(decision) -> None                                 # FINALIZED면 409 DECISION_FINALIZED
```

- 상태별 허용·오류 코드는 4.2 **API별 상태 제약** 표 하나를 기준으로 검사한다(`409 INVALID_STATE` / `409 DECISION_FINALIZED` / `422 NOT_READY`).
- `QUESTIONING → DRAFT` 전이는 없다(4.2).
- `decisions.version` +1은 B 담당이다. 위 함수 안에서 버전을 올려야 하는 경우(답변 저장 등)는 B가 만든 공통 함수를 호출한다.

### 5.2 엔진 입력 조립 (A 내부, B 모델에 의존)

```python
# 제안 — services/engine_bridge.py
async def load_engine_input(session, decision) -> EngineInput
    # criteria(position 순) → engine.normalize.Criterion, option_values → RawValues,
    # conditions → Condition, 유효 답변(seq 순, 가상 답변 포함) → posterior.Answer
    # EngineInput 안에 criterion_ids / option_ids 순서표와 질문별 origin을 같이 들고 다닌다
def to_api_ids(inp: EngineInput, idx: int, kind: Literal["criterion", "option"]) -> str
```

- 질문 `spec`(jsonb)에는 인덱스가 아니라 **기준 ID**를 저장한다. 기준이 추가·삭제되면 인덱스가 바뀌기 때문이다. 엔진에 넘기기 직전에만 인덱스로 바꾼다.
- 가상 질문(`questions.origin = free_text`)의 답변은 추정과 "아직 나오지 않은 기준" 판단(5.5 규칙 2)에는 쓰지만, 질문 수(`MAX_QUESTIONS`·`question_limit`)·같은 유형 연속 규칙·상황형 할당 차례에는 세지 않는다(7.3 29번, 8.1).
- B가 8장 모델을 만들 때 필요한 것: `criteria.position`, `scale_min/scale_max/scale_frozen`, `decisions.rng_seed`, `decisions.question_limit`, `questions.origin`, `answers.is_valid`, `answers.preference_kind`. 모두 8장에 이미 있다.

### 5.3 LLMClient (A 제공 → C·B 호출)

```python
# 제안 — ai/client.py (6.4의 인터페이스 그대로)
class LLMClient(Protocol):
    async def complete_structured(self, schema: type[T], prompt: Prompt, *, max_tokens: int) -> T

# 제안 — ai/calls.py (6.2의 6종)
async def extract_conditions(ctx: DecisionContext, text: str, *, question_id: str | None,
                             client_id: str, decision_id: str) -> ExtractResult      # C의 28번
async def recommend_criteria(ctx: DecisionContext, *, client_id, decision_id) -> CriteriaRecommendation   # B의 23번
async def recommend_options(ctx, *, client_id, decision_id) -> OptionRecommendation  # B의 18번 (P2)
async def phrase_question(spec_json: dict, *, client_id, decision_id) -> PhrasedQuestion | None
async def summarize_options(matrix: dict, *, client_id, decision_id) -> OptionSummaries | None
async def explain_result(analysis_json: dict, *, client_id, decision_id) -> Explanation   # 숫자 검증 포함
```

- 모든 호출은 안에서 ① (사용자 호출이면) 상한 확인 ② Router로 모델 선택 ③ Pydantic 재검증·1회 재시도 ④ fallback ⑤ `ai_usage_logs` 기록을 한다. 호출하는 쪽은 결과만 받는다.
- `extract_conditions`: 출력 `items`의 항목별 필드는 7.3 28번 **추출 항목 형식** 표(`criterion`·`constraint`·`option`·`preference`)를 따른다. `relevant=false`나 빈 `items`면 C가 `422 IRRELEVANT_INPUT`을 낸다(6.3). 상한 초과는 `429 AI_QUOTA_EXCEEDED`, LLM 실패(fallback 없음)는 `503 AI_UNAVAILABLE`(6.2, 7.1).
- `recommend_criteria`: 상한 초과나 LLM 실패 시 오류 대신 카테고리별 기본 기준 목록(6.2)을 돌려준다. B의 23번이 `"source": "template"`(정상은 `"ai"`)를 담을 수 있게 결과에 출처를 같이 넘긴다(7.3 23번).
- `phrase_question`: 출력 `{ title, optionA?, optionB?, leftLabel?, rightLabel? }`을 질문 객체의 `title`, `payload.optionA.description`, `payload.optionB.description`, `payload.left.label`, `payload.right.label`에 넣는다. 숫자·`deltas`·`range`는 서버 값 그대로 쓰고, **출력에 서버 값과 다른 숫자가 있으면 버리고 템플릿 문장**을 쓴다(6.2).
- 29번 확정 시 `type: preference`(`criterionA`, `criterionB`, `value` −5~+5)는 **가상의 `slider_pair` 질문 행과 답변 행**으로 넣는다(7.3 29번). 질문 행은 `origin = free_text`, `seq`는 다음 번호, `spec`은 일반 슬라이더와 같고, 답변은 `preference_kind = stated`. `DRAFT`에서도 등록할 수 있다. 이 등록 함수(예: `add_stated_preference(session, decision, criterion_a_id, criterion_b_id, value)`)는 A가 제공하고 C가 호출하는 것으로 제안한다.

### 5.4 AI 호출 상한 (A 제공)

```python
# 제안 — services/ai_quota.py
async def check_and_count(session, client_id: str, call_type: str) -> None   # 초과 시 AI_QUOTA_EXCEEDED
```
- **세는 것은 사용자가 직접 부르는 18 선택지 추천·23 기준 추천·28 자유 입력뿐**이다. 클라이언트 ID당 하루 `AI_DAILY_QUOTA_PER_CLIENT`(기본 50). 넘으면 18·28은 `429 AI_QUOTA_EXCEEDED`, 23은 기본 기준 목록으로 대체(12.2).
- 서버가 자동으로 부르는 `phrase_question`·`summarize_options`·`explain_result`는 세지 않는다. 대신 결과를 캐시해 같은 질문·같은 분석 버전에서 다시 부르지 않는다(12.2). 질문 문장은 `questions.display`·`phrasing_source`에, 요약·설명은 `analysis_results`(같은 `decision_version`)에 저장해 재사용한다.
- 그 밖에 자유 입력 500자, 출력 토큰 상한 추출 400·설명 800(12.2, 11.3).

---

## 6. 할 일 체크리스트

### 엔진 마무리
- [ ] 스크래치 폴더의 엔진 테스트를 `backend/tests/engine/`으로 옮기고 그대로 통과시키기
- [ ] 5.13 파라미터를 각 엔진 모듈 맨 위 상수로 정리 (어느 모듈인지는 5.13 위치 열·5.11 참고, 파일럿 보정 때 이 상수만 바꿈)
- [ ] `stopping.py`: 추가 라운드 지원 — `question_limit` 인자 받기, 라운드 안 최소 1문항, `CONFIDENT` 무시(4.2). 답변 수는 가상 질문(`origin = free_text`)을 빼고 센다
- [ ] `question_selector.py`: 선택 규칙 2 — 유효 답변(가상 답변 포함)에 한 번도 나오지 않은 기준이 있으면 그 기준이 들어간 후보 중에서 고르기. 답변 기록에서 계산하고 DB 필드는 두지 않음(5.5)
- [ ] `question_selector.py`: 규칙 2와 상황형 할당(규칙 4)이 겹치면 아직 안 나온 기준이 들어간 `ab_scenario` 후보, 없으면 규칙 2(규칙 5). 같은 유형 연속·상황형 할당 차례 계산에서 가상 질문 제외
- [ ] 기준 추가 시 K+1차원 재샘플링(같은 시드)·기존 답변 재적용 확인(5.14)
- [ ] 5.14 예외 상황 전부 테스트 (선택지 1개, 기준 2개, 값이 모두 같음, 전부 "잘 모르겠어요", 동점)
- [ ] (P1) 값 전환 조건 `change_conditions` 확장 — 숫자형·단계형만, 순위 입력은 제외(5.8)
- [ ] `mypy --strict` + `ruff` 통과

### 서비스·상태 전이
- [ ] B·C와 이 문서 5절 인터페이스(상태 전이·엔진 입력·LLM 호출·가상 선호 등록) 시그니처 합의
- [ ] `engine_bridge`: DB 행 → 엔진 입력, 엔진 결과 → API ID 변환, 질문별 `origin` 전달
- [ ] 상태 전이 서비스(4.2): 시작·종료(37·32·35)·추가 라운드·기준 추가/삭제 훅·확정
- [ ] `QUESTIONING` 중 기준 추가 시 `question_limit = max(현재 상한, 현재 답변 수 + 3)`
- [ ] 4.2 API별 상태 제약 표를 31~46 공통 검사로 구현
- [ ] 척도 고정(`scale_min/scale_max/scale_frozen`)을 31번 최초 호출 때 저장
- [ ] 질문 `spec`/`display`/`phrasing_source`/`origin`/`info_gain` 저장, 템플릿 문장 생성(5.5 문장 템플릿, 5.1 범위 표기)
- [ ] 29번용 가상 `slider_pair` 질문·답변 등록 함수(`origin = free_text`, `preference_kind = stated`, DRAFT 허용)
- [ ] 분석 캐시: `analysis_results.decision_version` 비교 → 무효면 재계산·저장

### 질문 API (31~38)
- [ ] 7.3 예시 응답을 돌려주는 스텁 라우터 먼저 올리기(프론트 연결용, 15장 병렬 원칙)
- [ ] 31 질문 시작 (최초·멱등·추가 라운드·오류, `expectedRange` 계산)
- [ ] 32 다음 질문 (미답변 우선, 종료 판단 시 `ANALYZED` 전이, ANALYZED·FINALIZED는 `done: true`, DRAFT `422`)
- [ ] 37 답변 제출 (형식 검증 → 재추정 → `estimate` + `next`, 종료 시 ANALYZED)
- [ ] 33 종료 판단 조회
- [ ] 35 조기 종료 (P1, `lowConfidence`)
- [ ] 36 답변 목록(`question.origin`), 38 답변 수정 (P1, ANALYZED에서도 재추정·상태 유지)
- [ ] 34 진행률 (P2)
- [ ] (P1) `estimate_snapshots` 답변마다 기록

### 분석 API (39~46)
- [ ] 41 순위, 40 가중치, 44 안정성(`level`·`competitors`·`text`), 45 전환 조건 (P0)
- [ ] 39 종합 — 위 결과 + 제외 선택지 + `lowConfidence` + 설명(템플릿 먼저)
- [ ] 상태 처리: `provisional`, QUESTIONING 답변 0개·DRAFT `422 NOT_READY`, FINALIZED 조회 허용
- [ ] 42 조건별 점수, 43 민감도, 46 시뮬레이션 (P1)
- [ ] 39에 숨은 선호(5.6)와 `strength` 계산 붙이기, 40에 `statedRank`·`revealedRank` (P1)

### AI 계층
- [ ] `LLMClient` 인터페이스, `LLMRouter`(`제공자:모델명` 파싱, `LLM_ROUTES`, `LLM_FALLBACK_MODELS`)
- [ ] Fake 어댑터 (카테고리별 기본 기준 목록 = 응답 데이터, 6.2)
- [ ] 서버 시작 시 설정된 모델의 키가 없으면 오류로 멈추기(6.4)
- [ ] 공통 검증: Pydantic 재검증 → 1회 재시도 → fallback(6.3). `extract_conditions`는 fallback 없이 `503 AI_UNAVAILABLE`
- [ ] 호출 상한(18·23·28만) + `ai_usage_logs` 기록(원문 저장 안 함), 23번은 상한 초과·실패 시 기본 기준 목록
- [ ] 자동 호출(문장 다듬기·장단점 요약·결과 설명) 결과 캐시 — 같은 질문·같은 분석 버전에서 재호출 안 함
- [ ] 6종 호출 함수와 제공자 중립 프롬프트(`<user_input>` 분리, 6.3), `extract_conditions` 출력은 28번 추출 항목 형식
- [ ] `phrase_question` 출력 → 질문 객체 필드 매핑, 서버 값과 다른 숫자가 있으면 버리고 템플릿
- [ ] `explain_result` 숫자 검증(퍼센트·금액·수치·단계 라벨) → 불일치 시 템플릿
- [ ] Gemini 어댑터(`response_schema` 변환) + 녹화 응답 테스트
- [ ] 32·37에 `phrase_question`, 39에 `summarize_options`·`explain_result` 연결 (실패해도 템플릿으로 흐름 유지)

### 테스트·실험
- [ ] 엔진 단위 테스트를 14장 기준으로 채우기 (hypothesis 포함)
- [ ] 5.12 예시 수치 재현 테스트 (N=4,000, 시드 42)
- [ ] 질문·분석 API 통합 테스트 (상태 전이, 4.2 상태 제약 표, 오류 코드)
- [ ] AI 계층 테스트 (Fake, 스키마 위반·관련 없음·fallback)
- [ ] 가상 사용자 시뮬레이션 노트북: 구매형·진로형, EIG vs 랜덤 비교(14)
- [ ] PyMC NUTS 교차검증 (±0.03)
- [ ] 범용 AI 비교 실험 3종: 일관성·전환점 정확도·질문 효율(1.5)
- [ ] 파일럿 결과로 5.13 "파일럿" 파라미터(모듈 상수) 보정

---

## 7. 완료 기준

| 그룹 | 끝났다고 보는 조건 |
|---|---|
| 엔진 마무리 | `backend/tests/` 엔진 테스트 전부 통과, `mypy --strict app/engine` 통과, 5.12 표(가중치 평균·`P_best`·전환 조건)를 시드 42로 재현, 추가 라운드에서 `CONFIDENT`로 끝나지 않고 최소 1문항을 묻는 테스트 통과, 아직 나오지 않은 기준이 먼저 질문되는 테스트 통과 |
| 서비스·상태 전이 | 4.2 전이 표의 모든 행과 **API별 상태 제약 표의 모든 칸**이 통합 테스트로 확인됨. `QUESTIONING→DRAFT`가 일어나지 않음. 같은 입력이면 같은 결과(재현성) |
| 질문 API | **AI 없이(Fake) 질문 시작 → 답변 → 종료까지** 동작(15장 질문 흐름 완료 기준). 새로고침해도 같은 질문. 가상 질문(29번)이 질문 수·`answered`에 세지 않음. 오류 코드가 7.1 형식 |
| 분석 API | 결과 화면에 차별화 요소 3종(1위 확률·안정성·전환 조건)이 나옴. 값을 바꾸면 캐시가 무효화되고 새 결과가 나옴. FINALIZED에서도 확정 시점 결과가 조회됨 |
| AI 계층 | **Fake·Gemini 양쪽으로 전체 흐름 동작**(15장 AI 연동 완료 기준). LLM을 끊어도 템플릿으로 모든 화면이 뜸(28번만 `503`). 18·28 상한 초과 시 `429`, 23은 기본 목록. 자동 호출은 상한에 세지 않고 캐시됨 |
| 테스트·실험 | 시뮬레이션 노트북에서 MAE·1위 적중률·평균 질문 수 표가 나옴. EIG가 랜덤보다 적은 질문으로 같은 정확도를 내는지 결과가 있음. 범용 AI 비교 실측값 정리 |

---

## 8. 지켜야 할 규칙

- **명세를 벗어나지 않는다.** 7장 스키마 v1, 8장 테이블, 4.2 상태 머신·상태 제약 표, 5장 파라미터(5.13) 그대로 구현한다.
- **없는 필드·API를 만들지 않는다.** 필요해 보이면 코드보다 먼저 팀에 공유한다.
- **빈틈을 발견하면 먼저 공유하고, 기획서부터 고친 뒤 코드를 바꾼다.** 스키마 변경은 PR 합의 + 17장 기록 + OpenAPI 타입 재생성을 같이 한다(7.1). 프론트 담당이 리뷰어로 들어간다(15.1).
- **엔진은 순수 함수**로 둔다. DB·HTTP를 모른다(5.11). DB 접근은 `services/`에서만.
- **LLM은 계산에 끼어들지 않는다.** 질문의 내용(기준 쌍·Δ)은 엔진이 정하고 LLM은 문장만 다듬는다(3.6, 6.1). 숫자는 서버 값만 쓴다(6.2). LLM 출력은 사용자 확인 후에만 반영한다(6.3).
- **LLM 키는 `backend/.env`에만** 둔다. 문서·코드·커밋·로그에 적지 않는다(6.4, 12.2).
- **Alembic은 DB 구조가 확정된 뒤** 도입한다. 그 전엔 `create_all`(11.2).
- **모델(8장) 변경은 B를 거친다.** 직접 고치지 않는다(11.2, 15.1).
- **엔진 코드는 테스트 없이 머지하지 않는다**(13.2).
- 린트: `ruff` + `mypy`(engine은 strict)(13.2).
- 브랜치 `a/<작업-이름>`(예: `a/engine-tests`) → `dev`, PR 리뷰 1명 이상, 하루 한 번 이상 `dev` 받아오기, Conventional Commits(13.2, `docs/개발환경-세팅.md` 0번).
- 발표 자료에는 실제 측정값만 쓴다. "범용 AI는 틀린다"고 미리 단정하지 않는다(1.5).

---

## 9. 일이 밀릴 때

15.1의 A 병목 대응 그대로다. 42·43·46은 기본적으로 A 몫이고, A의 일이 밀릴 때만 라우터 연결을 C에게 넘긴다.

| C에게 넘길 수 있는 것 | A가 끝까지 맡는 것 |
|---|---|
| LLM 호출별 프롬프트·출력 스키마 작성(6.2) | 엔진(`engine/`)과 v0.4 수정 |
| P1 분석 API(42·43·46)의 라우터 연결 | 상태 전이 서비스(4.2) |
| | 질문 흐름(31~38), 엔진 입력 조립 |

- 넘길 때는 엔진 함수와 응답 스키마를 먼저 정해 두고 라우터 연결만 넘긴다.
- 우선순위는 **P0 흐름을 AI 없이 템플릿으로 끝까지 잇는 것**이다. AI 연동과 P1은 그 다음이다(15.1). 프론트가 아직 못 쓰는 P1 API는 프론트 진행을 보고 만든다.

---

## 10. 참고 (기획서 절)

- 상태·수정 영향: 4.1, 4.2(전이 표, API별 상태 제약 표, 추가 질문 라운드), 4.3
- 엔진: 5.0~5.14 (특히 5.5 질문 선택·선택 규칙, 5.6 숨은 선호, 5.8 전환 조건, 5.10 종료 조건, 5.11 모듈, 5.12 계산 예시, 5.13 파라미터, 5.14 예외)
- AI: 6.1, 6.2, 6.3, 6.4
- API: 7.1 공통 규약, 7.2 목록, 7.3 31~46 상세 (연결: 19·22·23·28·29·47)
- DB: 8.1 `decisions`, `criteria`, `questions`(`origin` 포함), `answers`, `analysis_results`, `estimate_snapshots`, `ai_usage_logs`, 8.2
- 환경·규칙: 11.2, 11.3, 12.2(AI 호출 상한 범위), 13.1, 13.2
- 테스트: 14
- 일정·분담: 15, 15.1
- 차별점·비교 실험: 1.5
- 변경 기록: 17.6
