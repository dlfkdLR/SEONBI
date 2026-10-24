"""LLM 출력의 숫자·단계 라벨 검증 (개발기획서 6.2 phrase_question, 6.3 explain_result).

정규식으로 출력 속 숫자를 뽑아 서버가 준 값에 있는지 본다. 보수적으로 판단한다 — 의심되면 거절하고 템플릿을 쓴다.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from decimal import Decimal
from typing import Any

from .defaults import PRESETS

_NUMBER = re.compile(r"\d+(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?")
_ID_LIKE = re.compile(r"^[A-Za-z]{1,2}\d+$")  # "c1", "o3" 같은 식별자는 숫자로 세지 않는다
ALL_LEVEL_LABELS: frozenset[str] = frozenset(label for labels in PRESETS.values() for label in labels)


def _dec(value: str | float | int) -> Decimal:
    return Decimal(str(value).replace(",", "")).normalize()


def numbers_in(text: str) -> set[Decimal]:
    """텍스트 속 숫자(부호 제외 크기, 천 단위 쉼표 제거)."""
    return {_dec(m) for m in _NUMBER.findall(text)}


def _walk(obj: Any, key: str = "") -> Iterable[tuple[str, Any]]:
    """JSON 구조의 (부모 키, 말단 값)을 모두 낸다."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from _walk(v, str(k))
    elif isinstance(obj, list | tuple):
        for v in obj:
            yield from _walk(v, key)
    else:
        yield key, obj


def _is_id_key(key: str) -> bool:
    low = key.lower()
    return low in ("id", "ids") or low.endswith("id") or low.endswith("ids")


def _leaf_strings(obj: Any, keys: frozenset[str] | None = None) -> list[str]:
    out: list[str] = []
    for key, value in _walk(obj):
        if (
            isinstance(value, str)
            and not _is_id_key(key)
            and not _ID_LIKE.match(value)
            and (keys is None or key in keys)
        ):
            out.append(value)
    return out


def server_numbers_strict(spec: Any, keys: frozenset[str]) -> set[Decimal]:
    """phrase_question용: 지정한 키(display·range·description 등)의 문자열에 나온 숫자만 허용."""
    allowed: set[Decimal] = set()
    for text in _leaf_strings(spec, keys):
        allowed |= numbers_in(text)
    return allowed


def server_numbers_loose(data: Any) -> set[Decimal]:
    """explain_result용: 입력 JSON의 모든 숫자·문자열 속 숫자. 반올림(0~2자리)과 0~1 값의 퍼센트 표기도 허용."""
    allowed: set[Decimal] = set()
    for key, value in _walk(data):
        if isinstance(value, bool) or _is_id_key(key):
            continue
        if isinstance(value, int | float):
            allowed.add(_dec(value))
            for digits in (0, 1, 2):
                allowed.add(_dec(round(value, digits)))
            if abs(value) <= 1:
                allowed.add(_dec(round(value * 100, 0)))
                allowed.add(_dec(round(value * 100, 1)))
        elif isinstance(value, str) and not _ID_LIKE.match(value):
            allowed |= numbers_in(value)
    return allowed


def has_foreign_number(texts: Iterable[str], allowed: set[Decimal]) -> bool:
    """texts에 allowed에 없는 숫자가 하나라도 있으면 True."""
    return any(not numbers_in(t) <= allowed for t in texts)


def has_foreign_level_label(texts: Iterable[str], data: Any) -> bool:
    """3.4 프리셋 단계 라벨이 출력에 나오는데 입력 JSON 문자열에는 없으면 True."""
    source = " ".join(_leaf_strings(data))
    joined = " ".join(texts)
    return any(label in joined and label not in source for label in ALL_LEVEL_LABELS)
