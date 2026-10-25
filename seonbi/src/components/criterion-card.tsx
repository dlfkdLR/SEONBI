import type { Criterion } from '../domain/types';
export function criterionDescription(c: Criterion) {
  const familiar: Record<string, string> = { 가격: '얼마나 저렴한지 비교해요.', 성능: '얼마나 잘 작동하는지 비교해요.', 무게: '얼마나 가벼운지 비교해요.', 배터리: '얼마나 오래 쓰는지 비교해요.', '배터리 용량': '배터리 용량을 mAh 단위로 비교해요.' };
  if (familiar[c.name]) return familiar[c.name];
  return c.type === 'numeric' ? `${c.unit ? c.unit + ' 단위의 ' : ''}숫자로 비교해요.` : c.type === 'boolean' ? '예 / 아니요로 비교해요.' : c.inputMode === 'rank' ? '좋은 순서대로 비교해요.' : `${c.levels.length}단계로 비교해요.`;
}
export function criterionDirection(c: Criterion) {
  if (c.type === 'numeric' && c.direction === 'higher_better' && c.name === '배터리 용량') return '용량이 클수록 좋아요.';
  if (c.type === 'numeric' && c.direction === 'lower_better' && c.name === '가격') return '저렴할수록 좋아요.';
  if (c.type === 'numeric' && c.direction === 'lower_better' && c.name === '무게') return '가벼울수록 좋아요.';
  if (c.direction === 'higher_better' && c.name === '성능') return '성능이 높을수록 좋아요.';
  if (c.direction === 'higher_better' && c.name === '배터리') return '오래갈수록 좋아요.';
  return c.type === 'boolean' ? '예인 쪽이 좋아요.' : c.inputMode === 'rank' ? '1위가 가장 좋아요.' : c.direction === 'lower_better' ? '낮을수록 좋아요.' : '높을수록 좋아요.';
}
export function CriterionCard({ criterion: c, selected, onToggle, disabled = false }: { criterion: Criterion; selected: boolean; onToggle: () => void; disabled?: boolean }) {
  return <button type="button" className={`selection-card criterion-card ${selected ? 'selected' : ''}`} aria-pressed={selected} aria-label={`${c.name} 비교 항목`} disabled={disabled} onClick={onToggle}><span className="selection-mark" aria-hidden="true">{selected ? '✓' : ''}</span><strong>{c.name}</strong><span className="card-description">{criterionDescription(c)}</span><small className="card-detail">{criterionDirection(c)}</small></button>;
}
