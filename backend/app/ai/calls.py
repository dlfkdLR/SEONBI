"""LLM 호출 6종과 후처리 규칙 (개발기획서 6.2, 6.3, 7.3).

여기서는 아무것도 저장하지 않는다. 호출 상한(12.2)·`ai_usage_logs` 쓰기·결과 캐시는 서비스 계층 몫이고,
`on_usage` 훅으로 사용량을 받아 갈 수 있다. 각 함수의 첫 인자는 `LLMRouter` 또는 `LLMClient`다.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import asdict, dataclass
from typing import Any, Literal

from . import prompts
from .client import LLMClient, T, UsageCallback
from .defaults import defaults_for
from .errors import AIUnavailableError, LLMError
from .prompts import DecisionContext
from .router import LLMRouter
from .schemas import (
    ExplainResultOutput,
    ExtractConditionsOutput,
    ExtractedItem,
    OptionProsCons,
    PhraseQuestionOutput,
    RecommendCriteriaOutput,
    RecommendCriterion,
    RecommendedOption,
    RecommendOptionsOutput,
    SummarizeOptionsOutput,
)
from .verify import (
    has_foreign_level_label,
    has_foreign_number,
    server_numbers_loose,
    server_numbers_strict,
)

LLMSource = LLMRouter | LLMClient
Source = Literal["ai", "template"]

# 출력 토큰 상한. 추출 400·설명 800은 12.2, 나머지는 스키마 크기에 맞춘 값(스펙에 없음).
MAX_TOKENS: dict[str, int] = {
    "extract_conditions": 400,
    "recommend_criteria": 900,
    "recommend_options": 500,
    "phrase_question": 300,
    "summarize_options": 700,
    "explain_result": 800,
}

# phrase_question에서 서버 값으로 인정하는 질문 객체의 문자열 키 (7.3 질문 객체)
_PHRASE_SERVER_KEYS = frozenset({"display", "range", "description", "title", "label"})


async def _complete(
    source: LLMSource, call_type: str, schema: type[T], prompt: str, on_usage: UsageCallback | None
) -> T:
    """한 번 호출. 실패는 모두 `LLMError`로 올라온다(스키마 위반 포함)."""
    client: LLMClient = source.client_for(call_type, on_usage=on_usage) if isinstance(source, LLMRouter) else source
    return await client.complete_structured(schema, prompt, max_tokens=MAX_TOKENS[call_type])


# ── extract_conditions ────────────────────────────────────────────────


@dataclass(frozen=True)
class ExtractResult:
    irrelevant: bool  # True면 서비스가 422 IRRELEVANT_INPUT으로 응답한다 (6.3 고정 문구)
    items: tuple[ExtractedItem, ...] = ()
    ambiguity: str | None = None  # 서비스가 needsClarification으로 옮긴다


async def extract_conditions(
    source: LLMSource,
    ctx: DecisionContext,
    text: str,
    *,
    question_context: str | None = None,
    on_usage: UsageCallback | None = None,
) -> ExtractResult:
    """fallback 없음: 실패하면 `AIUnavailableError`(서비스가 503으로). `text` 길이(1~500자)는 호출 전에 검증한다."""
    prompt = prompts.extract_conditions_prompt(ctx, text, question_context)
    try:
        out = await _complete(source, "extract_conditions", ExtractConditionsOutput, prompt, on_usage)
    except LLMError as exc:
        raise AIUnavailableError("조건 추출에 실패했습니다") from exc
    if not out.relevant or not out.items:
        return ExtractResult(irrelevant=True)  # LLM의 자유 답변(ambiguity 포함)은 노출하지 않는다
    return ExtractResult(irrelevant=False, items=tuple(out.items), ambiguity=out.ambiguity)


# ── recommend_criteria ───────────────────────────────────────────────


@dataclass(frozen=True)
class CriteriaRecommendation:
    source: Source  # 7.3 23번의 "source"
    items: tuple[RecommendCriterion, ...]


def template_criteria(category: str) -> tuple[RecommendCriterion, ...]:
    """카테고리별 기본 기준 목록(6.2)을 출력 스키마 항목으로 바꾼다."""
    return tuple(
        RecommendCriterion.model_validate({**asdict(d), "levels": list(d.levels) if d.levels else None})
        for d in defaults_for(category)
    )


async def recommend_criteria(
    source: LLMSource, ctx: DecisionContext, *, on_usage: UsageCallback | None = None
) -> CriteriaRecommendation:
    """실패·스키마 위반이면 오류 대신 기본 기준 목록(`source="template"`). 상한 초과 대체는 `template_criteria`."""
    prompt = prompts.recommend_criteria_prompt(ctx)
    try:
        out = await _complete(source, "recommend_criteria", RecommendCriteriaOutput, prompt, on_usage)
    except LLMError:
        return CriteriaRecommendation("template", template_criteria(ctx.category))
    return CriteriaRecommendation("ai", tuple(out.criteria))


# ── recommend_options (P2) ───────────────────────────────────────────


@dataclass(frozen=True)
class OptionRecommendation:
    items: tuple[RecommendedOption, ...]


async def recommend_options(
    source: LLMSource, ctx: DecisionContext, *, count: int = 3, on_usage: UsageCallback | None = None
) -> OptionRecommendation | None:
    """실패하면 None(기능 숨김)."""
    prompt = prompts.recommend_options_prompt(ctx, count)
    try:
        out = await _complete(source, "recommend_options", RecommendOptionsOutput, prompt, on_usage)
    except LLMError:
        return None
    return OptionRecommendation(tuple(out.options[:count]))


# ── phrase_question ──────────────────────────────────────────────────


@dataclass(frozen=True)
class PhrasedQuestion:
    """LLM이 다듬은 문장. 호출하는 쪽이 아래 필드를 질문 객체에 덮어쓴다(값이 None이면 덮어쓰지 않는다)."""

    source: Source
    title: str  # → title
    option_a_description: str | None = None  # → payload.optionA.description
    option_b_description: str | None = None  # → payload.optionB.description
    left_label: str | None = None  # → payload.left.label
    right_label: str | None = None  # → payload.right.label


async def phrase_question(
    source: LLMSource, spec: Mapping[str, Any], *, on_usage: UsageCallback | None = None
) -> PhrasedQuestion | None:
    """`spec`은 7.3 질문 객체(서버 값 포함). 실패하거나 서버 값에 없는 숫자가 있으면 None → 호출자가 템플릿을 쓴다."""
    prompt = prompts.phrase_question_prompt(spec)
    try:
        out = await _complete(source, "phrase_question", PhraseQuestionOutput, prompt, on_usage)
    except LLMError:
        return None
    texts = [t for t in (out.title, out.option_a, out.option_b, out.left_label, out.right_label) if t]
    if has_foreign_number(texts, server_numbers_strict(spec, _PHRASE_SERVER_KEYS)):
        return None
    return PhrasedQuestion("ai", out.title, out.option_a, out.option_b, out.left_label, out.right_label)


# ── summarize_options ────────────────────────────────────────────────


@dataclass(frozen=True)
class OptionSummaries:
    items: tuple[OptionProsCons, ...]


async def summarize_options(
    source: LLMSource, matrix: Mapping[str, Any], *, on_usage: UsageCallback | None = None
) -> OptionSummaries | None:
    """실패하면 None(숫자 비교표만 표시)."""
    prompt = prompts.summarize_options_prompt(matrix)
    try:
        out = await _complete(source, "summarize_options", SummarizeOptionsOutput, prompt, on_usage)
    except LLMError:
        return None
    return OptionSummaries(tuple(out.options))


# ── explain_result ───────────────────────────────────────────────────


@dataclass(frozen=True)
class Explanation:
    source: Source
    summary: str
    reasons: tuple[str, ...]
    caveats: tuple[str, ...]


async def explain_result(
    source: LLMSource, analysis: Mapping[str, Any], *, on_usage: UsageCallback | None = None
) -> Explanation | None:
    """출력의 숫자·단계 라벨이 입력 JSON에 없으면 None → 호출자가 템플릿 설명을 쓴다(6.3)."""
    prompt = prompts.explain_result_prompt(analysis)
    try:
        out: ExplainResultOutput = await _complete(source, "explain_result", ExplainResultOutput, prompt, on_usage)
    except LLMError:
        return None
    texts = [out.summary, *out.reasons, *out.caveats]
    if has_foreign_number(texts, server_numbers_loose(analysis)) or has_foreign_level_label(texts, analysis):
        return None
    return Explanation("ai", out.summary, tuple(out.reasons), tuple(out.caveats))
