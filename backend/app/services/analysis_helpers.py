"""분석·질문 API 응답(7.3 31·37·39~45번)의 조각을 엔진 결과로 만드는 순수 함수 (camelCase 일반 dict).

엔진은 기준·선택지를 인덱스로 가리킨다. 호출하는 쪽이 `criterion_ids`·`criterion_names`·`option_ids`·
`option_names`를 엔진 인덱스 순서대로 넘기면 여기서 API ID·이름으로 바꾼다. DB·FastAPI·Pydantic은 쓰지 않는다.

반올림 규칙: 확률·평균 가중치·점수·신뢰 구간은 소수 둘째 자리(PROB_DIGITS), 전환 조건의 현재 가중치·임계값과
값 전환 조건의 값은 소수 넷째 자리(THRESHOLD_DIGITS). 문장 속 퍼센트는 정수로 반올림한다. 등급·종료 판단 같은
분기는 반올림 전 값으로 한다.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from typing import Any, Literal

import numpy as np
from numpy.typing import NDArray

from app.engine.decision_analysis import ChangeCondition, Stability, ValueCondition, ranking, scores
from app.engine.hidden_preference import Mismatch
from app.engine.likelihood import preference_kind
from app.engine.posterior import Answer, Summary, reweight
from app.engine.stopping import MIN_QUESTIONS, counted_answers, is_low_confidence, max_questions

PROB_DIGITS = 2
THRESHOLD_DIGITS = 4
INTERVAL_LEVEL = 0.8  # 40번 intervalLevel — 10~90% 구간 (posterior.INTERVAL)
COMPETITOR_MIN_P = 0.05  # 44번: 1위가 아닌 선택지 중 P_best가 이 값 이상이면 경쟁자
_TIE_TOL = 1e-12

# 5.6: 숨은 선호 결과에 항상 붙이는 고정 문구 (text에는 넣지 않고 화면이 따로 보여 준다)
HIDDEN_PREFERENCE_NOTICE = "응답에서 추정한 경향이며 단정하지 않습니다"

STABILITY_TEXT_HIGH = "가중치가 조금 달라져도 1위가 유지될 가능성이 높아요."
# 명세에 없는 경우(전환 조건이나 경쟁 선택지가 하나도 없는데 등급이 HIGH가 아님)에 쓰는 고정 문장
STABILITY_TEXT_FALLBACK = "중요도를 어떻게 두느냐에 따라 1위가 바뀔 수 있어요."

_LEVELS = {"high": "HIGH", "medium": "MEDIUM", "low": "LOW"}


# --------------------------------------------------------------------------------------------------
# 한국어 조사
# --------------------------------------------------------------------------------------------------

# 숫자·알파벳을 읽는 소리의 받침 여부 (영 일 이 삼 사 오 육 칠 팔 구 / 에이 비 … 엘 엠 엔 … 알)
_DIGIT_BATCHIM = {"0": True, "1": True, "2": False, "3": True, "4": False, "5": False, "6": True, "7": True, "8": True}
_DIGIT_BATCHIM["9"] = False
_LETTER_BATCHIM = {c: c in "LMNR" for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ"}
_RIEUL_LETTERS = frozenset("LR")  # 엘, 알 — ㄹ 받침 (로/으로 판단에서 받침 없음 취급)
_RIEUL_DIGITS = frozenset("178")  # 일, 칠, 팔


def _last_sound(word: str) -> tuple[bool, bool]:
    """(받침 있음, ㄹ 받침)을 마지막 글자의 읽는 소리로 판단한다. 괄호·따옴표·공백 등은 건너뛴다.

    한글은 받침을 직접 보고, 숫자·알파벳은 읽는 소리(위 표)로 본다. 그 밖의 문자는 받침 없음으로 취급한다.
    """
    for ch in reversed(word):
        if "가" <= ch <= "힣":
            jong = (ord(ch) - 0xAC00) % 28
            return jong != 0, jong == 8
        if ch in _DIGIT_BATCHIM:
            return _DIGIT_BATCHIM[ch], ch in _RIEUL_DIGITS
        up = ch.upper()
        if up in _LETTER_BATCHIM:
            return _LETTER_BATCHIM[up], up in _RIEUL_LETTERS
        if ch.isalnum():
            return False, False
    return False, False


def i_ga(word: str) -> str:
    """word 뒤에 붙는 이/가를 붙여 돌려준다. 예: 가격 → 가격이, 무게 → 무게가."""
    return word + ("이" if _last_sound(word)[0] else "가")


def eul_reul(word: str) -> str:
    """을/를. 예: 가격 → 가격을, 무게 → 무게를."""
    return word + ("을" if _last_sound(word)[0] else "를")


def eu_ro(word: str) -> str:
    """(으)로. ㄹ 받침은 받침이 없는 것처럼 '로'. 예: 갤럭시북 → 갤럭시북으로, 서울 → 서울로."""
    batchim, rieul = _last_sound(word)
    return word + ("으로" if batchim and not rieul else "로")


# --------------------------------------------------------------------------------------------------
# 31번 · 37번 · 35/39번
# --------------------------------------------------------------------------------------------------


def answered_count(answers: Sequence[Answer]) -> int:
    """`progress.answered`. 자유 입력으로 만든 가상 답변(origin=free_text)은 뺀다. 잘 모르겠어요는 센다."""
    return counted_answers(answers)


def expected_range(round_kind: Literal["initial", "extra"], K: int, answered: int, question_limit: int) -> list[int]:
    """31번 `progress.expectedRange`. 첫 라운드 [3, max_questions(K)], 추가 라운드 [답변 수 + 1, question_limit]."""
    if round_kind == "initial":
        return [MIN_QUESTIONS, max_questions(K)]
    return [answered + 1, question_limit]


def low_confidence(p_best_top: float) -> bool:
    """35번·39번 `lowConfidence` — 같은 값을 쓰도록 이 함수 하나만 거친다 (P_best < 0.55)."""
    return is_low_confidence(p_best_top)


def top_probability(s: Summary, V: NDArray[np.float64]) -> float:
    """평균 가중치 기준 1위 선택지의 P_best (39번 recommendation.topProbability, 37번 topProbability, 44번 score)."""
    return float(s.p_best[ranking(s.mean, V)[0]])


def estimate_payload(s: Summary, V: NDArray[np.float64], criterion_ids: Sequence[str]) -> dict[str, Any]:
    """37·38번 `estimate`."""
    return {
        "weights": [
            {
                "criterionId": criterion_ids[k],
                "mean": _p(s.mean[k]),
                "interval": [_p(s.intervals[k, 0]), _p(s.intervals[k, 1])],
            }
            for k in range(len(criterion_ids))
        ],
        "topProbability": _p(top_probability(s, V)),
    }


# --------------------------------------------------------------------------------------------------
# 40번 · 41번
# --------------------------------------------------------------------------------------------------


def _rank_from_kind(
    W_prior: NDArray[np.float64], answers: Sequence[Answer], kind: Literal["stated", "revealed"]
) -> list[int] | None:
    """같은 사전 입자에 kind 답변만 재가중해 구한 평균 가중치의 기준별 순위(1 = 가장 큼). 근거 답변이 없으면 None."""
    used = [a for a in answers if a.response is not None and preference_kind(a.spec) == kind]
    if not used:
        return None
    mean = reweight(W_prior, used) @ W_prior
    return [1 + int(np.sum(mean > mean[k] + _TIE_TOL)) for k in range(len(mean))]


def weights_items(
    s: Summary,
    criterion_ids: Sequence[str],
    criterion_names: Sequence[str],
    answers: Sequence[Answer],
    W_prior: NDArray[np.float64],
) -> list[dict[str, Any]]:
    """40번 `items`. 응답의 `intervalLevel`은 INTERVAL_LEVEL(0.8).

    s는 `posterior.summary(post, V)`, W_prior는 `posterior.prior_particles(seed, K)`, answers는 유효 답변 전체
    (자유 입력의 가상 슬라이더 답변도 추정에 쓰이므로 진술 순위에 포함한다).
    """
    stated = _rank_from_kind(W_prior, answers, "stated")
    revealed = _rank_from_kind(W_prior, answers, "revealed")
    return [
        {
            "criterionId": criterion_ids[k],
            "name": criterion_names[k],
            "mean": _p(s.mean[k]),
            "interval": [_p(s.intervals[k, 0]), _p(s.intervals[k, 1])],
            "statedRank": None if stated is None else stated[k],
            "revealedRank": None if revealed is None else revealed[k],
        }
        for k in range(len(criterion_ids))
    ]


def rankings_items(
    mean: NDArray[np.float64],
    V: NDArray[np.float64],
    p_best: NDArray[np.float64],
    option_ids: Sequence[str],
    option_names: Sequence[str],
) -> list[dict[str, Any]]:
    """41번 `items`. V·p_best·option_*는 제외되지 않은 선택지만 같은 순서로 넣는다. 동점이면 입력 순서."""
    u = scores(mean, V)
    return [
        {
            "rank": r,
            "optionId": option_ids[i],
            "name": option_names[i],
            "score": _p(u[i]),
            "topProbability": _p(p_best[i]),
        }
        for r, i in enumerate(ranking(mean, V), start=1)
    ]


# --------------------------------------------------------------------------------------------------
# 44번
# --------------------------------------------------------------------------------------------------


def stability_payload(
    st: Stability,
    p_best: NDArray[np.float64],
    change_conds: Sequence[ChangeCondition],
    option_ids: Sequence[str],
    option_names: Sequence[str],
    criterion_names: Sequence[str],
) -> dict[str, Any]:
    """44번 응답. p_best는 `Summary.p_best`(선택지별 1위 확률).

    text: HIGH면 고정 문장. 그 외에는 "{가장 가까운 전환 조건의 기준}을(를) 얼마나 중시하는지에 따라
    {1순위 경쟁 선택지}(으)로 바뀔 수 있어요." — "가장 가까운"은 임계값이 있는 전환 조건 중 |임계값 − 현재 가중치|가
    가장 작은 것으로 읽었다. 그런 전환 조건이나 경쟁 선택지가 없으면 STABILITY_TEXT_FALLBACK을 쓴다(명세에 없음).
    """
    competitors = sorted(
        ((i, float(p_best[i])) for i in range(len(p_best)) if i != st.top and p_best[i] >= COMPETITOR_MIN_P),
        key=lambda t: -t[1],
    )
    level = _LEVELS[st.grade]
    if st.grade == "high":
        text = STABILITY_TEXT_HIGH
    else:
        with_thr = [c for c in change_conds if c.threshold is not None]
        if with_thr and competitors:
            nearest = min(with_thr, key=lambda c: abs((c.threshold or 0.0) - c.current))
            text = (
                f"{eul_reul(criterion_names[nearest.criterion])} 얼마나 중시하는지에 따라 "
                f"{eu_ro(option_names[competitors[0][0]])} 바뀔 수 있어요."
            )
        else:
            text = STABILITY_TEXT_FALLBACK
    return {
        "optionId": option_ids[st.top],
        "score": _p(st.p_best),
        "level": level,
        "localPerturbationKeepRate": _p(st.perturb_keep),
        "competitors": [{"optionId": option_ids[i], "probability": _p(pr)} for i, pr in competitors],
        "text": text,
    }


# --------------------------------------------------------------------------------------------------
# 45번
# --------------------------------------------------------------------------------------------------


def change_conditions_payload(
    conds: Sequence[ChangeCondition],
    current_top: int,
    criterion_ids: Sequence[str],
    criterion_names: Sequence[str],
    option_ids: Sequence[str],
    option_names: Sequence[str],
    value_conds: Sequence[ValueCondition] = (),
    criterion_units: Sequence[str] = (),
    criterion_levels: Sequence[Sequence[str]] = (),
) -> dict[str, Any]:
    """45번 응답. 가중치 항목(기준 순서) 뒤에 값 항목(P1, value_conditions가 준 순서)을 붙인다.

    - 가중치 항목에 임계값이 있으면 명세 예시의 키 전부, 없으면 예시처럼 type·criterionId·threshold(null)·text만 낸다.
    - 값 항목은 type·optionId·criterionId·currentValue·threshold·text. 방향은 text에만 담는다(명세 키에 없음).
    - criterion_units는 숫자형 기준의 단위("만 원", "kg"; 없으면 ""), criterion_levels는 단계형 기준의 단계 라벨
      (나쁜 것 → 좋은 것 순서). 둘 다 기준 인덱스 순서이고, 값 항목이 없으면 안 넘겨도 된다.
    """
    items: list[dict[str, Any]] = []
    for c in conds:
        name = criterion_names[c.criterion]
        if c.threshold is None or c.new_top is None or c.direction is None:
            items.append(
                {
                    "type": "weight",
                    "criterionId": criterion_ids[c.criterion],
                    "threshold": None,
                    "text": f"{name} 중요도가 어떻게 바뀌어도 1위가 유지됩니다.",
                }
            )
            continue
        bound = "이상이" if c.direction == "increase" else "이하가"
        items.append(
            {
                "type": "weight",
                "criterionId": criterion_ids[c.criterion],
                "currentWeight": _t(c.current),
                "threshold": _t(c.threshold),
                "direction": c.direction,
                "newTop": option_ids[c.new_top],
                "text": (
                    f"{name} 중요도가 {_pct(c.current)}% → {_pct(c.threshold)}% {bound} 되면 "
                    f"{i_ga(option_names[c.new_top])} 1위가 됩니다."
                ),
            }
        )
    for v in value_conds:
        items.append(
            {
                "type": "value",
                "optionId": option_ids[v.option],
                "criterionId": criterion_ids[v.criterion],
                "currentValue": _num(v.current),
                "threshold": _num(v.threshold),
                "text": _value_text(v, criterion_names, option_names, criterion_units, criterion_levels),
            }
        )
    return {"currentTop": option_ids[current_top], "items": items}


def _value_text(
    v: ValueCondition,
    criterion_names: Sequence[str],
    option_names: Sequence[str],
    criterion_units: Sequence[str],
    criterion_levels: Sequence[Sequence[str]],
) -> str:
    head = f"{option_names[v.option]}의 {i_ga(criterion_names[v.criterion])}"
    levels = criterion_levels[v.criterion] if v.criterion < len(criterion_levels) else ()
    if levels:  # 단계형: 라벨로 말한다
        now, target = _level_label(levels, v.current), _level_label(levels, v.threshold)
        return f"{head} '{now}'에서 {_quoted_i_ga(target)} 되면 1위가 됩니다."
    unit = criterion_units[v.criterion] if v.criterion < len(criterion_units) else ""
    amount = f"{_num(round(abs(v.current - v.threshold), THRESHOLD_DIGITS))}{unit}"
    verb = "올라가면" if v.direction == "increase" else "내려가면"
    return f"{head} {amount} 이상 {verb} 1위가 됩니다."


def _level_label(levels: Sequence[str], level: float) -> str:
    idx = int(round(level)) - 1
    return levels[idx] if 0 <= idx < len(levels) else f"{int(round(level))}단계"


def _quoted_i_ga(label: str) -> str:
    return f"'{label}'" + ("이" if _last_sound(label)[0] else "가")


# --------------------------------------------------------------------------------------------------
# 39번 hiddenPreferences
# --------------------------------------------------------------------------------------------------


def hidden_preferences_payload(
    mismatches: Sequence[Mismatch], criterion_ids: Sequence[str], criterion_names: Sequence[str]
) -> list[dict[str, Any]]:
    """39번 `hiddenPreferences`. `hidden_preference.detect`의 결과를 옮긴다.

    A = 진술에서 더 중요하다고 한 기준(`stated_over`), B = 실제 선택에서 더 중시한 기준(`revealed_over`)이라
    stated는 항상 A_MORE_IMPORTANT, revealed는 항상 B_MORE_IMPORTANT다(명세 예시와 같다).
    strength = min(진술 쪽 P(w_a > w_b), 1 − 드러난 쪽 P(w_a > w_b)). 고정 문구는 HIDDEN_PREFERENCE_NOTICE.
    """
    out: list[dict[str, Any]] = []
    for m in mismatches:
        a, b = criterion_names[m.stated_over], criterion_names[m.revealed_over]
        out.append(
            {
                "criterionA": criterion_ids[m.stated_over],
                "criterionB": criterion_ids[m.revealed_over],
                "stated": "A_MORE_IMPORTANT",
                "revealed": "B_MORE_IMPORTANT",
                "strength": _p(min(m.stated_p, 1.0 - m.revealed_p)),
                "text": (
                    f"{i_ga(a)} 더 중요하다고 답했지만, "
                    f"실제 선택에서는 {eul_reul(b)} 위해 {eul_reul(a)} 양보하는 경향이 보였어요."
                ),
            }
        )
    return out


# --------------------------------------------------------------------------------------------------
# 반올림
# --------------------------------------------------------------------------------------------------


def _p(x: float) -> float:
    return round(float(x), PROB_DIGITS)


def _t(x: float) -> float:
    return round(float(x), THRESHOLD_DIGITS)


def _num(x: float) -> float | int:
    """원래 값 표기: 소수 넷째 자리까지 반올림하고 정수값이면 int로 낸다(210.0 → 210)."""
    r = round(float(x), THRESHOLD_DIGITS)
    return int(r) if r == math.floor(r) else r


def _pct(x: float) -> int:
    return int(math.floor(float(x) * 100 + 0.5))
