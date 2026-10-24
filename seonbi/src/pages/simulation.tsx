import { useMemo, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { Line, LineChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { analyze, changeConditions, ranking, redistribute, validationErrors } from '../domain/engine';
import { useDecision } from '../data/hooks';
import { Button, ErrorNotice, Loading, PageHeading, TextButton } from '../components/ui';
import type { Analysis, Decision } from '../domain/types';
import { withParticle } from '../lib/korean';

export default function SimulationPage() {
  const { id = '' } = useParams(), { data: d, isPending, error } = useDecision(id);
  const analysis = useMemo(() => d && !validationErrors(d).length ? analyze(d) : null, [d]);
  if (isPending) return <Loading />;
  if (error) return <main className="page" id="main"><ErrorNotice message={error.message} /></main>;
  if (!d) return <Navigate to="/" replace />;
  if (!analysis || d.status === 'DRAFT') return <Navigate to={`/decisions/${id}/setup/3`} replace />;
  return <Simulation key={d.id} d={d} analysis={analysis} />;
}
function Simulation({ d, analysis }: { d: Decision; analysis: Analysis }) {
  const original = useMemo(() => analysis.weights.map(w => w.mean), [analysis]), [weights, setWeights] = useState(original), [criterionIndex, setCriterionIndex] = useState(0);
  const ranks = ranking(d, weights), top = ranks[0], selected = d.criteria[criterionIndex];
  const curves = useMemo(() => Array.from({ length: 41 }, (_, i) => {
    const w = redistribute(weights, criterionIndex, i / 40), result = ranking(d, w);
    return { weight: i * 2.5, ...Object.fromEntries(result.map(r => [r.optionId, Number((r.score * 100).toFixed(3))])) };
  }), [weights, criterionIndex, d]);
  const crossover = changeConditions(d, weights).find(c => c.criterionId === selected.id);
  return <main className="page simulation-page" id="main"><div className="page-content"><PageHeading context={`${d.title} / 기준 바꿔보기`} title="우선순위가 달라지면, 선택도 달라질까요?" description="중요도를 조정하고 순위가 바뀌는 지점을 확인해보세요." /><div className="simulation-columns"><section className="weight-controls"><h2 className="section-title">내가 조정한 중요도</h2>{d.criteria.map((c, i) => <div className="weight-control" key={c.id}><label htmlFor={`weight-${c.id}`}><span>{c.name}</span><strong>{(weights[i] * 100).toFixed(1)}%</strong></label><input id={`weight-${c.id}`} type="range" min={0} max={100} step={.1} value={weights[i] * 100} onChange={e => setWeights(w => redistribute(w, i, Number(e.target.value) / 100))} /><small>원래 중요도 {(original[i] * 100).toFixed(1)}%</small></div>)}<p className="caption muted">한 기준을 움직이면 나머지가 비율대로 조정돼요. 합계 100%를 유지해요.</p></section>
      <section className="simulation-outcome"><h2 className="section-title">이 기준에서는 {withParticle(top?.name ?? '선택지', '이', '가')} 1위예요.</h2><p className="muted">원래 추천은 {withParticle(analysis.rankings[0]?.name ?? '선택지', '이에요', '예요')}.<br />시뮬레이션은 저장한 답변과 최종 선택에 영향을 주지 않아요.</p>{ranks.map((r, i) => <div className="simulation-rank" key={r.optionId}><div><strong>{r.rank} {r.name}</strong><span>{(r.score * 100).toFixed(1)}점</span></div><div className="score-track"><div style={{ width: `${r.score * 100}%`, background: i === 0 ? '#2c2c2c' : '#aaa' }} /></div></div>)}<div className="simulation-threshold">{crossover ? <p>{selected.name} 중요도 <strong>{(crossover.threshold * 100).toFixed(1)}%</strong>가 추천이 바뀌는 지점이에요.</p> : <p>{selected.name} 중요도를 바꿔도 현재 1위가 유지돼요.</p>}</div></section></div>
    <section className="sensitivity"><div className="sensitivity-heading"><h2 className="section-title">{selected.name} 중요도에 따른 점수 변화</h2><select aria-label="민감도 분석 기준" value={criterionIndex} onChange={e => setCriterionIndex(Number(e.target.value))}>{d.criteria.map((c, i) => <option key={c.id} value={i}>{c.name}</option>)}</select></div><div className="chart" role="img" aria-label={`${selected.name} 중요도 0~100%에 따른 각 선택지의 종합 점수 변화`}><ResponsiveContainer width="100%" height="100%"><LineChart data={curves} margin={{ top: 12, right: 20, bottom: 4, left: 0 }}><CartesianGrid vertical={false} stroke="#eeeeee" /><XAxis dataKey="weight" type="number" domain={[0, 100]} tickFormatter={v => `${v}%`} tick={{ fontSize: 11, fill: '#757575' }} tickLine={false} axisLine={{ stroke: '#d9d9d9' }} /><YAxis domain={[0, 100]} tick={{ fontSize: 11, fill: '#757575' }} tickLine={false} axisLine={false} width={40} /><Tooltip formatter={(v, name) => [`${Number(v).toFixed(1)}점`, name]} labelFormatter={v => `${selected.name} 중요도 ${v}%`} contentStyle={{ borderRadius: 3, border: '1px solid #d9d9d9', fontSize: 12 }} />{crossover && <ReferenceLine x={crossover.threshold * 100} stroke="#999" strokeDasharray="3 5" />}{analysis.rankings.map((r, i) => <Line key={r.optionId} dataKey={r.optionId} name={r.name} type="linear" dot={false} stroke={i === 0 ? '#1e1e1e' : i === 1 ? '#777' : '#aaa'} strokeWidth={2} strokeDasharray={i ? `${6 + i} 5` : undefined} isAnimationActive={false} />)}</LineChart></ResponsiveContainer></div><div className="chart-legend">{analysis.rankings.map((r, i) => <span key={r.optionId}><i style={{ borderTopStyle: i ? 'dashed' : 'solid', borderColor: i === 0 ? '#1e1e1e' : '#888' }} />{r.name}</span>)}</div></section>
  </div><footer className="page-footer"><Link className="button button-secondary" to={`/decisions/${d.id}/result`}>분석 결과로 돌아가기</Link><TextButton onClick={() => setWeights([...original])}>원래 기준으로 되돌리기</TextButton><small className="save-status">시뮬레이션 · 자동 저장되지 않아요.</small></footer></main>;
}
