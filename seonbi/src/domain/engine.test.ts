import { describe, expect, it } from 'vitest';
import { analyze, changeConditions, exclusionReasons, freezeScales, nextQuestion, normalized, posterior, ranking, redistribute, shouldStop, validationErrors } from './engine';
import { blankDecision, criterion } from '../data/examples';
import type { Answer, Decision } from './types';
import { markdown } from '../lib/export';

function fixture(): Decision {
  const d = blankDecision('test'); d.id = 'test-decision'; d.seed = 42; d.title = '전공 비교';
  d.options = [{ id: 'a', name: '컴퓨터공학과' }, { id: 'b', name: '디자인과' }];
  d.criteria = [{ ...criterion('통학 시간', 'numeric', 'lower_better', '분'), id: 'time' }, { ...criterion('적성', 'rating'), id: 'fit' }];
  d.values = { a: { time: 30, fit: 2 }, b: { time: 60, fit: 5 } };
  return freezeScales(d);
}
function answer(d: Decision, value: number): Answer {
  return { id: 'answer', question: { id: 'question', type: 'slider_pair', criteria: [d.criteria[0].id, d.criteria[1].id] }, response: { value }, isValid: true };
}
describe('입력과 정규화', () => {
  it('가격 대신 전공 기준도 같은 엔진으로 정규화한다', () => {
    const d = fixture(); expect(validationErrors(d)).toEqual([]); expect(normalized(d, 'a', d.criteria[0])).toBe(1); expect(normalized(d, 'b', d.criteria[0])).toBe(0); expect(normalized(d, 'a', d.criteria[1])).toBe(.25);
  });
  it('고정된 범위는 선택지 추가 후에도 바뀌지 않고 범위 밖 값은 자른다', () => {
    const d = fixture(), original = normalized(d, 'a', d.criteria[0]); d.options.push({ id: 'c', name: '추가 전공' }); d.values.c = { time: 200, fit: 3 };
    expect(normalized(d, 'a', d.criteria[0])).toBe(original); expect(normalized(d, 'c', d.criteria[0])).toBe(0); expect(freezeScales(d).criteria[0].scaleMax).toBe(60);
  });
  it('모두 같은 숫자는 중간 점수로 처리한다', () => {
    const d = fixture(); d.criteria[0].scaleMax = 30; expect(normalized(d, 'a', d.criteria[0])).toBe(.5);
  });
  it('순위 입력은 동순위를 묶고 모든 동순위는 0.5다', () => {
    const d = fixture(); d.criteria[1].inputMode = 'rank'; d.values.a.fit = 1; d.values.b.fit = 1;
    expect(normalized(d, 'a', d.criteria[1])).toBe(.5); d.values.b.fit = 2; expect(normalized(d, 'a', d.criteria[1])).toBe(1); expect(normalized(d, 'b', d.criteria[1])).toBe(0);
  });
  it('미입력과 중복 이름, 잘못된 단계를 차단한다', () => {
    const d = fixture(); d.values.a.time = null; d.values.b.fit = 10; d.options[1].name = d.options[0].name;
    expect(validationErrors(d).join(' ')).toContain('서로 달라야'); expect(validationErrors(d).join(' ')).toContain('통학 시간'); expect(validationErrors(d).join(' ')).toContain('적성');
  });
  it('필수 조건은 삭제 없이 제외하고 전부 제외되면 진행을 막는다', () => {
    const d = fixture(); d.conditions = [{ id: 'c', kind: 'threshold', criterionId: 'time', operator: '<=', value: 35 }];
    expect(exclusionReasons(d).map(e => e.optionId)).toEqual(['b']); expect(ranking(d, [.5, .5]).map(r => r.optionId)).toEqual(['a']); expect(d.options).toHaveLength(2); expect(validationErrors(d).join()).toContain('2개 이상');
  });
});
describe('선호 추정과 질문', () => {
  it('질문이 바뀌어도 같은 기준 쌍의 중요도를 반복해서 묻지 않는다', () => {
    const d = fixture();
    for (const [id, name] of [['battery', '배터리'], ['weight', '무게']]) {
      d.criteria.push({ ...criterion(name, 'numeric'), id, scaleMin: 0, scaleMax: 10 });
      d.values.a[id] = 2; d.values.b[id] = 8;
    }
    const seen = new Set<string>();
    for (let i = 0; i < 20; i++) {
      const q = nextQuestion(d); if (!q) break;
      const pair = [...q.criteria].sort().join(':');
      const meaning = `${pair}:${q.type === 'ab_scenario' ? '상황' : '중요도'}`;
      expect(seen.has(meaning)).toBe(false);
      seen.add(meaning);
      d.answers.push({ id: `answer-${i}`, question: q, response: q.type === 'slider_pair' ? { value: 0 } : { choice: i % 2 ? 'A' : 'B' }, isValid: true });
    }
    expect(nextQuestion(d)).toBeUndefined();
  });
  it('잘 모르겠다고 넘긴 질문도 동일한 중요도 질문으로 다시 내지 않는다', () => {
    const d = fixture(), first = nextQuestion(d)!;
    d.answers.push({ id: 'skip', question: first, response: { skip: true }, isValid: true });
    expect(nextQuestion(d)?.type).toBe('ab_scenario');
  });
  it('기준이 두 개일 때 새 질문이 소진되면 반복 대신 끝낸다', () => {
    const d = fixture(), first = nextQuestion(d)!;
    d.answers.push({ id: 'first', question: first, response: { choice: 'A' }, isValid: true });
    const second = nextQuestion(d)!;
    expect(second.type).toBe('ab_scenario');
    d.answers.push({ id: 'second', question: second, response: { choice: 'B' }, isValid: true });
    expect(nextQuestion(d)).toBeUndefined();
  });
  it('기준 순서가 반대인 기존 답변도 같은 비교로 인식한다', () => {
    const d = fixture(), old = answer(d, 2);
    old.question.criteria.reverse(); d.answers.push(old);
    expect(nextQuestion(d)?.type).toBe('ab_scenario');
    old.isValid = false;
    expect(nextQuestion(d)?.type).not.toBe('ab_scenario');
  });
  it('같은 시드와 답변으로 결과가 재현된다', () => { const d = fixture(); d.answers = [answer(d, 4)]; expect(analyze(d)).toEqual(analyze(d)); });
  it('양의 슬라이더 답변은 왼쪽 기준 중요도를 높이고 음수는 반대다', () => {
    const d = fixture(), a = posterior(d, [answer(d, 5)], 1200), b = posterior(d, [answer(d, -5)], 1200);
    expect(a.means[0]).toBeGreaterThan(a.means[1]); expect(b.means[0]).toBeLessThan(b.means[1]); expect(a.means.reduce((x, y) => x + y)).toBeCloseTo(1, 9);
  });
  it('무효 답변과 건너뛰기는 가중치를 바꾸지 않는다', () => {
    const d = fixture(), baseline = posterior(d, [], 500).means, a = answer(d, 5); a.isValid = false;
    expect(posterior(d, [a], 500).means).toEqual(baseline); a.isValid = true; a.response = { skip: true }; expect(posterior(d, [a], 500).means).toEqual(baseline);
  });
  it('확률의 합은 1이고 동점의 확률은 나눠 갖는다', () => {
    const d = fixture(); d.values.b = { ...d.values.a }; const a = analyze(d);
    expect(a.rankings.reduce((s, r) => s + r.probability, 0)).toBeCloseTo(1, 9); expect(a.rankings[0].probability).toBeCloseTo(.5, 9); expect(a.rankings.map(r => r.rank)).toEqual([1, 1]);
  });
  it('순위형 기준에는 상황형 질문을 만들지 않는다', () => {
    const d = fixture(); d.criteria[1].inputMode = 'rank'; d.values.a.fit = 1; d.values.b.fit = 2; d.answers = [answer(d, 1), { ...answer(d, 2), id: 'second' }];
    expect(nextQuestion(d)?.type).not.toBe('ab_scenario');
  });
  it('추가 질문 라운드는 기존 확신이 높아도 최소 한 문항을 묻는다', () => {
    const d = fixture(); d.answers = [answer(d, 5)]; d.questionLimit = 4;
    expect(shouldStop(d, analyze(d))).toBe(false);
  });
});
describe('시뮬레이션과 전환 조건', () => {
  it('100%와 0%에서도 가중치 합을 유지하고 다시 분배한다', () => {
    expect(redistribute([.3, .3, .4], 0, 1)).toEqual([1, 0, 0]); expect(redistribute([1, 0, 0], 0, .4)).toEqual([.4, .3, .3]);
    for (let n = 0; n <= 100; n++) { const weights = redistribute([.2, .3, .5], 1, n / 100); expect(weights.reduce((a, b) => a + b)).toBeCloseTo(1, 12); expect(weights.every(x => x >= 0)).toBe(true); }
  });
  it('전환점에서 두 선택지 점수가 같고 전환점 너머의 전체 1위가 바뀐다', () => {
    const d = fixture(), weights = [.7, .3], change = changeConditions(d, weights)[0]; expect(change).toBeDefined();
    const k = d.criteria.findIndex(c => c.id === change.criterionId), tied = ranking(d, redistribute(weights, k, change.threshold)); expect(tied[0].score).toBeCloseTo(tied[1].score, 9);
    const beyond = redistribute(weights, k, change.threshold + (change.direction === 'increase' ? .001 : -.001)); expect(ranking(d, beyond)[0].optionId).toBe(change.newTop);
  });
  it('내보내기는 현재 선택지와 사용자 기준, 최종 결정을 포함한다', () => {
    const d = fixture(); d.finalized = { optionId: 'b', memo: '적성 우선', at: '2026-10-24T00:00:00Z' }; const text = markdown(d, analyze(d));
    expect(text).toContain('컴퓨터공학과'); expect(text).toContain('통학 시간'); expect(text).toContain('적성 우선'); expect(text).not.toContain('갤럭시북');
  });
});
