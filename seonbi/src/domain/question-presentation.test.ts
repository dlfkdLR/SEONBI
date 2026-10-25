import { expect, it } from 'vitest';
import type { QuestionDto } from '../api/contracts';
import { presentQuestion } from './question-presentation';

const question: QuestionDto = { id: 'q1', seq: 1, type: 'slider_pair', criteria: ['cost', 'satisfaction'], title: '긴 원본 질문', allowSkip: true, allowFreeText: true,
  payload: { left: { criterionId: 'cost', label: '비용(220~270)', range: '220~270' }, right: { criterionId: 'satisfaction', label: '만족도 기대(아이폰(낮음)~갤럭시(높음))', range: '아이폰(낮음)~갤럭시(높음)' }, min: -5, max: 5 } };

it('수치·중첩 괄호 설명을 빼고 질문을 줄이면서 답변의 방향과 식별자를 보존한다', () => {
  const next = presentQuestion(question);
  expect(next.title).toBe('비용과 만족도 중 어떤 요소를 더 중요시하나요?');
  expect(next.payload.left).toEqual({ criterionId: 'cost', label: '비용', range: undefined });
  expect(next.payload.right).toEqual({ criterionId: 'satisfaction', label: '만족도', range: undefined });
  expect(next).toMatchObject({ id: 'q1', criteria: ['cost', 'satisfaction'], payload: { min: -5, max: 5 } });
  expect(question.payload.left?.range).toBe('220~270');
});

it('두 항목 선택형도 같은 문구를 쓰되 상황 선택형의 의미는 유지한다', () => {
  const q: QuestionDto = { ...question, type: 'ab_criteria', payload: { optionA: { criterionId: 'a', label: '성능(좋음)', description: '부연 설명' }, optionB: { criterionId: 'b', label: '무게(1~2)', deltas: [{ criterionId: 'b', display: '수치' }] } } };
  expect(presentQuestion(q)).toMatchObject({ title: '성능과 무게 중 어떤 요소를 더 중요시하나요?', payload: { optionA: { label: '성능', description: undefined }, optionB: { label: '무게', deltas: undefined } } });
  const scenario: QuestionDto = { ...q, type: 'ab_scenario' };
  expect(presentQuestion(scenario)).toBe(scenario);
});
