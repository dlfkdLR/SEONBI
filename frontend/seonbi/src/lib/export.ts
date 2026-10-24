import type { Analysis, Decision } from '../domain/types';
import { categories, statusLabels } from '../domain/types';
import { conditionLabel, formatValue, hiddenPreferences } from '../domain/engine';

const cell = (s: string) => s.replace(/\|/g, '\\|').replace(/[\r\n]/g, ' ');
export function markdown(d: Decision, a: Analysis) {
  const lines = [`# ${d.title}`, '', d.description, '', `분류: ${categories[d.category]} · 상태: ${statusLabels[d.status]}`, '', '## 선택지 비교', '',
    `| 선택지 | ${d.criteria.map(c => cell(c.name)).join(' | ')} |`, `| --- | ${d.criteria.map(() => '---').join(' | ')} |`,
    ...d.options.map(o => `| ${cell(o.name)} | ${d.criteria.map(c => cell(formatValue(d.values[o.id]?.[c.id], c))).join(' | ')} |`), '', '## 필수 조건', '',
    ...d.conditions.map(c => `- ${conditionLabel(c, d)}`), '', '## 답변에서 드러난 중요도', '',
    ...a.weights.map(w => `- ${d.criteria.find(c => c.id === w.criterionId)?.name}: ${(w.mean * 100).toFixed(1)}% (80% 구간 ${(w.interval[0] * 100).toFixed(1)}~${(w.interval[1] * 100).toFixed(1)}%)`), '', '## 분석 결과', '',
    ...a.rankings.map(r => `${r.rank}. ${r.name}: ${(r.score * 100).toFixed(1)}점 · 1위 확률 ${(r.probability * 100).toFixed(1)}%`), '', `결정 안정성: ${a.stability} · 작은 가중치 변화에서 추천 유지 비율 ${(a.keepRate * 100).toFixed(1)}%`, '', '## 추천이 바뀌는 조건', '',
    ...a.changes.map(c => `- ${d.criteria.find(k => k.id === c.criterionId)?.name} 중요도가 ${(c.threshold * 100).toFixed(1)}% ${c.direction === 'increase' ? '이상' : '이하'}일 때 ${d.options.find(o => o.id === c.newTop)?.name}이 유리해져요.`), '',
    ...hiddenPreferences(d).map(t => `- 숨은 선호: ${t}`), '', '## 제외한 선택지', '', ...a.excluded.map(e => `- ${d.options.find(o => o.id === e.optionId)?.name}: ${e.reasons.join(', ')}`), '',
  ];
  if (d.finalized) lines.push('## 최종 선택', '', d.options.find(o => o.id === d.finalized?.optionId)?.name ?? '', d.finalized.memo, `확정일: ${new Date(d.finalized.at).toLocaleString('ko-KR')}`, '');
  lines.push('---', '로컬 체험용 참조 분석입니다. 서버 엔진 및 AI 서비스에 연결되지 않았으며 결과는 입력 데이터와 답변에 따라 계산됩니다.', `분석 답변 수: ${d.answers.filter(a => a.isValid && !a.response.skip).length}개`, `내보낸 시각: ${new Date().toISOString()}`);
  return lines.join('\n');
}
export function downloadMarkdown(d: Decision, analysis: Analysis) {
  const url = URL.createObjectURL(new Blob([markdown(d, analysis)], { type: 'text/markdown;charset=utf-8' }));
  const a = document.createElement('a'); a.href = url; a.download = `선비-${d.title.replace(/[\\/:*?"<>|]/g, '-').slice(0, 60) || '결정'}.md`; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
