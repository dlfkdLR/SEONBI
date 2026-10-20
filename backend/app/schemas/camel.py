"""camelCase 응답·요청 스키마 공통 기반 (개발기획서 7.1: 필드 표기는 camelCase).

FastAPI의 `response_model_by_alias` 기본값(True)이 별칭(camelCase)으로 직렬화한다.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """파이썬 속성은 snake_case, JSON 키는 camelCase."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class StrictCamelModel(CamelModel):
    """명세에 없는 필드를 거부한다(요청·payload용)."""

    model_config = ConfigDict(extra="forbid")
