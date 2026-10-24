import type { Category, Criterion, Decision } from '../domain/types';
import { defaultLevels, uid } from '../domain/types';
import { freezeScales } from '../domain/engine';

export function blankDecision(ownerId: string): Decision {
  const now = new Date().toISOString();
  return { id: uid(), ownerId, title: '', description: '', category: 'etc', status: 'DRAFT', step: 1,
    options: [{ id: uid(), name: '' }, { id: uid(), name: '' }], criteria: [], values: {}, conditions: [], answers: [], seed: Math.floor(Math.random() * 2 ** 31), createdAt: now, updatedAt: now };
}
export function criterion(name: string, type: Criterion['type'], direction: Criterion['direction'] = 'higher_better', unit = ''): Criterion {
  return { id: uid(), name, type, direction, unit, levels: [...defaultLevels], inputMode: 'score' };
}
export const presetCriteria = (category: Category): Criterion[] => {
  const presets: Record<Category, () => Criterion[]> = {
    purchase: () => [criterion('가격', 'numeric', 'lower_better', '만원'), criterion('성능', 'rating'), criterion('무게', 'numeric', 'lower_better', 'kg'), criterion('배터리', 'numeric', 'higher_better', '시간')],
    career: () => [criterion('흥미', 'rating'), criterion('적성', 'rating'), criterion('취업 전망', 'rating'), criterion('통학 시간', 'numeric', 'lower_better', '분')],
    study: () => [criterion('흥미', 'rating'), criterion('학습 효과', 'rating'), criterion('비용', 'numeric', 'lower_better', '만원'), criterion('소요 시간', 'numeric', 'lower_better', '시간')],
    activity: () => [criterion('흥미', 'rating'), criterion('성장 가능성', 'rating'), criterion('주당 시간', 'numeric', 'lower_better', '시간')],
    living: () => [criterion('비용', 'numeric', 'lower_better', '만원'), criterion('거리', 'numeric', 'lower_better', 'km'), criterion('만족도', 'rating')],
    etc: () => [criterion('비용', 'numeric', 'lower_better', '원'), criterion('만족도', 'rating'), criterion('시간', 'numeric', 'lower_better', '시간')],
  };
  return presets[category]();
};

export function examples(ownerId: string): Decision[] {
  const laptop = blankDecision(ownerId);
  laptop.title = '새 노트북 고르기'; laptop.category = 'purchase'; laptop.description = '학교 개발 수업에 쓰고, 통학할 때 매일 들고 다닐 노트북';
  laptop.options = ['갤럭시북5 프로', 'LG 그램 16', '맥북 에어 M4'].map(name => ({ id: uid(), name }));
  laptop.criteria = presetCriteria('purchase');
  [[170, 5, 1.23, 14], [150, 4, 1.19, 16], [160, 5, 1.24, 18]].forEach((row, i) => {
    laptop.values[laptop.options[i].id] = Object.fromEntries(row.map((v, j) => [laptop.criteria[j].id, v]));
  });
  laptop.conditions = [{ id: uid(), kind: 'threshold', criterionId: laptop.criteria[0].id, operator: '<=', value: 180 }, { id: uid(), kind: 'required_tag', label: 'Windows 지원', satisfiedBy: laptop.options.slice(0, 2).map(o => o.id) }];
  const pairs: [number, number, number][] = [[1, 0, 3], [2, 0, 2], [1, 3, 2], [2, 3, 1]];
  laptop.answers = pairs.map(([a, b, value], i) => ({ id: uid(), question: { id: `example-${i}`, type: 'slider_pair', criteria: [laptop.criteria[a].id, laptop.criteria[b].id] }, response: { value }, isValid: true }));
  laptop.status = 'ANALYZED'; laptop.step = 4; laptop.example = true;
  return [freezeScales(laptop)];
}
