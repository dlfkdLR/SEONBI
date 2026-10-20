"""ports.py의 모든 Protocol을 메모리로 구현한 가짜 저장소와 예시 스냅샷 (테스트 전용).

- `make_stores(snapshot)`: Stores 묶음. `stores.snapshot()`이 저장소의 현재 상태(상태·상한·버전·고정 척도)를 반영한
  새 `DecisionSnapshot`을 만든다. 서비스는 요청마다 새 스냅샷을 받는다는 가정을 그대로 흉내 낸다.
- `laptop_snapshot()`: 5.12 노트북 예시(숫자형 2 + 단계형 1). `career_snapshot()`: 진로형(순위·단계·숫자형 섞임).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, replace
from typing import Any

from app.services.ports import (
    AnalysisCache,
    AnswerRow,
    CachedAnalysis,
    ConditionRow,
    CriterionRow,
    DecisionSnapshot,
    DecisionWriter,
    FinalChoiceRow,
    FinalChoiceStore,
    OptionRow,
    PhrasingSource,
    PreferenceKind,
    QuestionRow,
    QuestionStore,
    Stores,
    UsageRecord,
    UsageStore,
    ValueRow,
)
from app.services.state_rules import Status

CLIENT_ID = "00000000-0000-7000-8000-000000000001"


class FakeState:
    """decisions 행 하나의 가변 상태. 구성(선택지·기준·값)은 base 스냅샷에서 시작해 add/remove로만 바뀐다."""

    def __init__(self, base: DecisionSnapshot) -> None:
        self.base = base
        self.status: Status = base.status
        self.question_limit: int | None = base.question_limit
        self.version: int = base.version
        self.scales: dict[str, tuple[float, float]] = {}
        self.calls: list[str] = []  # 호출 기록 (테스트에서 "한 번만 저장" 등을 확인)

    def snapshot(self) -> DecisionSnapshot:
        criteria = tuple(
            replace(c, scale_min=self.scales[c.id][0], scale_max=self.scales[c.id][1], scale_frozen=True)
            if c.id in self.scales
            else c
            for c in self.base.criteria
        )
        return replace(
            self.base, status=self.status, question_limit=self.question_limit, version=self.version, criteria=criteria
        )

    def add_criterion(self, row: CriterionRow, values: Sequence[ValueRow] = ()) -> None:
        row = replace(row, position=len(self.base.criteria))
        self.base = replace(self.base, criteria=(*self.base.criteria, row), values=(*self.base.values, *values))

    def remove_criterion(self, criterion_id: str) -> None:
        self.base = replace(
            self.base,
            criteria=tuple(c for c in self.base.criteria if c.id != criterion_id),
            values=tuple(v for v in self.base.values if v.criterion_id != criterion_id),
        )
        self.scales.pop(criterion_id, None)


class FakeDecisionWriter(DecisionWriter):
    def __init__(self, state: FakeState) -> None:
        self.state = state

    async def set_status(self, decision_id: str, status: Status) -> None:
        self.state.calls.append(f"set_status:{status}")
        self.state.status = status

    async def set_question_limit(self, decision_id: str, limit: int | None) -> None:
        self.state.calls.append("set_question_limit")
        self.state.question_limit = limit

    async def freeze_scales(self, decision_id: str, scales: dict[str, tuple[float, float]]) -> None:
        self.state.calls.append("freeze_scales")
        self.state.scales.update(scales)

    async def bump_version(self, decision_id: str) -> int:
        self.state.calls.append("bump_version")
        self.state.version += 1
        return self.state.version


class FakeQuestionStore(QuestionStore):
    def __init__(self) -> None:
        self.questions: list[QuestionRow] = []
        self.answers: list[AnswerRow] = []

    async def list_questions(self, decision_id: str) -> Sequence[QuestionRow]:
        return sorted((q for q in self.questions if q.decision_id == decision_id), key=lambda q: q.seq)

    async def add_question(self, row: QuestionRow) -> None:
        if any(q.decision_id == row.decision_id and q.seq == row.seq for q in self.questions):
            raise ValueError(f"seq 중복: {row.seq}")  # UNIQUE(decision_id, seq)
        self.questions.append(row)

    async def list_answers(self, decision_id: str) -> Sequence[AnswerRow]:
        seq = {q.id: q.seq for q in self.questions}
        return sorted((a for a in self.answers if a.decision_id == decision_id), key=lambda a: seq[a.question_id])

    async def add_answer(self, row: AnswerRow) -> None:
        if any(a.question_id == row.question_id for a in self.answers):
            raise ValueError("answers.question_id UNIQUE")
        self.answers.append(row)

    async def update_answer(self, answer_id: str, response: dict[str, Any], preference_kind: PreferenceKind) -> None:
        self.answers = [
            replace(a, response=response, preference_kind=preference_kind, updated_at="edited")
            if a.id == answer_id
            else a
            for a in self.answers
        ]

    async def invalidate_answers_of_criterion(self, decision_id: str, criterion_id: str) -> int:
        by_id = {q.id: q for q in self.questions}
        count = 0
        out: list[AnswerRow] = []
        for a in self.answers:
            q = by_id[a.question_id]
            hit = a.decision_id == decision_id and criterion_id in (q.spec.get("a"), q.spec.get("b"))
            if hit and a.is_valid:
                count += 1
                a = replace(a, is_valid=False)
            out.append(a)
        self.answers = out
        return count

    async def update_question_display(
        self, question_id: str, display: dict[str, Any], phrasing_source: PhrasingSource
    ) -> None:
        self.questions = [
            replace(q, display=display, phrasing_source=phrasing_source) if q.id == question_id else q
            for q in self.questions
        ]


class FakeAnalysisCache(AnalysisCache):
    def __init__(self) -> None:
        self.items: dict[str, CachedAnalysis] = {}

    async def get(self, decision_id: str) -> CachedAnalysis | None:
        return self.items.get(decision_id)

    async def put(self, decision_id: str, value: CachedAnalysis) -> None:
        self.items[decision_id] = value


class FakeFinalChoiceStore(FinalChoiceStore):
    def __init__(self) -> None:
        self.items: dict[str, FinalChoiceRow] = {}

    async def add(self, row: FinalChoiceRow) -> None:
        self.items[row.decision_id] = row

    async def get(self, decision_id: str) -> FinalChoiceRow | None:
        return self.items.get(decision_id)


class FakeUsageStore(UsageStore):
    def __init__(self) -> None:
        self.records: list[UsageRecord] = []

    async def count_today(self, client_id: str, call_types: Sequence[str]) -> int:
        return sum(1 for r in self.records if r.client_id == client_id and r.call_type in call_types)

    async def add(self, record: UsageRecord) -> None:
        self.records.append(record)


@dataclass(frozen=True)
class FakeStores(Stores):
    state: FakeState

    def snapshot(self) -> DecisionSnapshot:
        return self.state.snapshot()


def make_stores(base: DecisionSnapshot) -> FakeStores:
    state = FakeState(base)
    return FakeStores(
        decisions=FakeDecisionWriter(state),
        questions=FakeQuestionStore(),
        analysis=FakeAnalysisCache(),
        final_choices=FakeFinalChoiceStore(),
        usage=FakeUsageStore(),
        state=state,
    )


# --------------------------------------------------------------------------------------------------
# 예시 스냅샷
# --------------------------------------------------------------------------------------------------


def laptop_snapshot(
    status: Status = Status.DRAFT,
    *,
    seed: int = 42,
    question_limit: int | None = None,
    conditions: tuple[ConditionRow, ...] = (),
) -> DecisionSnapshot:
    """5.12 노트북 예시. 가격·무게는 낮을수록 좋은 숫자형, 성능은 5단계."""
    options = (
        OptionRow("galaxy", "갤럭시북", position=0),
        OptionRow("gram", "LG 그램 16", position=1),
        OptionRow("yoga", "요가", position=2),
    )
    criteria = (
        CriterionRow("price", "가격", "numeric", "lower_better", 0, unit="만 원"),
        CriterionRow("weight", "무게", "numeric", "lower_better", 1, unit="kg"),
        CriterionRow(
            "perf", "성능", "rating", "higher_better", 2, levels=("매우 낮음", "낮음", "보통", "높음", "매우 높음")
        ),
    )
    nums = {
        ("galaxy", "price"): 189,
        ("gram", "price"): 165,
        ("yoga", "price"): 150,
        ("galaxy", "weight"): 1.23,
        ("gram", "weight"): 1.19,
        ("yoga", "weight"): 1.45,
        ("galaxy", "perf"): 4,
        ("gram", "perf"): 3,
        ("yoga", "perf"): 4,
    }
    values = tuple(ValueRow(o, c, value_num=float(x)) for (o, c), x in nums.items())
    return DecisionSnapshot(
        id="d-laptop",
        client_id=CLIENT_ID,
        category="purchase",
        status=status,
        rng_seed=seed,
        question_limit=question_limit,
        version=1,
        options=options,
        criteria=criteria,
        values=values,
        conditions=conditions,
    )


def career_snapshot(
    status: Status = Status.DRAFT, *, seed: int = 7, question_limit: int | None = None
) -> DecisionSnapshot:
    """진로형: 흥미(순위 입력)·적성(4단계)·전망(5단계)·통학 시간·등록금. tests/engine/test_simulation.py와 같은 값."""
    options = (
        OptionRow("cs", "컴퓨터공학과", position=0),
        OptionRow("biz", "경영학과", position=1),
        OptionRow("design", "디자인학과", position=2),
        OptionRow("edu", "교육학과", position=3),
    )
    criteria = (
        CriterionRow("interest", "흥미", "rating", "higher_better", 0, input_mode="rank"),
        CriterionRow(
            "fit", "적성", "rating", "higher_better", 1, levels=("안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음")
        ),
        CriterionRow(
            "outlook", "전망", "rating", "higher_better", 2, levels=("매우 낮음", "낮음", "보통", "높음", "매우 높음")
        ),
        CriterionRow("commute", "통학 시간", "numeric", "lower_better", 3, unit="분"),
        CriterionRow("tuition", "등록금", "numeric", "lower_better", 4, unit="만 원"),
    )
    table = {
        "interest": {"cs": 1, "biz": 3, "design": 2, "edu": 4},
        "fit": {"cs": 4, "biz": 2, "design": 3, "edu": 2},
        "outlook": {"cs": 5, "biz": 3, "design": 3, "edu": 2},
        "commute": {"cs": 70, "biz": 30, "design": 50, "edu": 20},
        "tuition": {"cs": 800, "biz": 600, "design": 900, "edu": 500},
    }
    values: list[ValueRow] = []
    for cid, per_option in table.items():
        for oid, x in per_option.items():
            if cid == "interest":
                values.append(ValueRow(oid, cid, value_rank=x))
            else:
                values.append(ValueRow(oid, cid, value_num=float(x)))
    return DecisionSnapshot(
        id="d-career",
        client_id=CLIENT_ID,
        category="career",
        status=status,
        rng_seed=seed,
        question_limit=question_limit,
        version=1,
        options=options,
        criteria=criteria,
        values=tuple(values),
    )
