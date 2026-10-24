import { useMemo, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { analyze, changeConditions, formatValue, hiddenPreferences, nextQuestion, normalized, validationErrors } from '../domain/engine';
import { blankDecision } from '../data/examples';
import { useDecision, useOwner } from '../data/hooks';
import { repository } from '../data/local-repository';
import { uid } from '../domain/types';
import type { Analysis, Decision } from '../domain/types';
import { Button, ErrorNotice, Field, Loading, Modal, TextButton } from '../components/ui';
import { useUI } from '../lib/state';
import { downloadMarkdown } from '../lib/export';
import { withParticle } from '../lib/korean';

export default function ResultPage() {
  const { id = '' } = useParams(), { data: d, isPending, error, update } = useDecision(id), owner = useOwner(), navigate = useNavigate(), cache = useQueryClient(), notify = useUI(s => s.notify);
  const analysis = useMemo(() => d && d.criteria.length >= 2 && !validationErrors(d).length ? analyze(d) : null, [d]);
  const [dialog, setDialog] = useState<'edit' | 'delete' | 'finalize' | 'export' | null>(null), [finalId, setFinalId] = useState(''), [memo, setMemo] = useState(''), [title, setTitle] = useState(''), [description, setDescription] = useState('');
  if (isPending) return <Loading />;
  if (error) return <main className="page" id="main"><ErrorNotice message={error.message} /></main>;
  if (!d) return <Navigate to="/" replace />;
  if (d.status === 'DRAFT') return <Navigate to={`/decisions/${id}/setup/${d.step}`} replace />;
  if (!analysis || !analysis.rankings.length) return <main className="page" id="main"><ErrorNotice message="비교할 값이나 조건을 확인해주세요. 조건을 충족하는 선택지가 2개 이상 필요해요." /><Link to={`/decisions/${id}/setup/3`}>비교 내용 수정하기</Link></main>;
  const top = analysis.rankings[0], topOption = d.options.find(o => o.id === top.optionId)!, locked = d.status === 'FINALIZED';
  const contributions = analysis.weights.map(w => ({ c: d.criteria.find(c => c.id === w.criterionId)!, value: w.mean * normalized(d, top.optionId, d.criteria.find(c => c.id === w.criterionId)!) })).sort((a, b) => b.value - a.value);
  const hidden = hiddenPreferences(d), changes = changeConditions(d, analysis.weights.map(w => w.mean));
  const duplicate = () => {
    try {
      const next = blankDecision(owner), ids = new Map<string, string>();
      next.title = `${d.title} (복사)`.slice(0, 100); next.description = d.description; next.category = d.category;
      next.options = d.options.map(o => { const id = uid(); ids.set(o.id, id); return { ...o, id }; });
      next.criteria = d.criteria.map(c => { const id = uid(); ids.set(c.id, id); return { ...structuredClone(c), id }; });
      next.values = Object.fromEntries(d.options.map(o => [ids.get(o.id)!, Object.fromEntries(d.criteria.map(c => [ids.get(c.id)!, d.values[o.id]?.[c.id] ?? null]))]));
      next.conditions = d.conditions.map(c => c.kind === 'required_tag' ? { ...c, id: uid(), satisfiedBy: c.satisfiedBy.map(id => ids.get(id)!) } : { ...c, id: uid(), criterionId: ids.get(c.criterionId)! });
      repository.save(owner, next); void cache.invalidateQueries({ queryKey: ['decisions', owner] }); navigate(`/decisions/${next.id}/setup/1`); notify('새 결정으로 복제했어요.');
    } catch (e) { notify(e instanceof Error ? e.message : '복제하지 못했어요.'); }
  };
  return <main className="page result-page" id="main"><div className="page-content"><div className="result-context"><p className="eyebrow muted">{d.title} / {locked ? '최종 결정' : '분석 결과'}</p><div className="flex gap-6">{!locked && <TextButton onClick={() => { setTitle(d.title); setDescription(d.description); setDialog('edit'); }}>결정 수정</TextButton>}<TextButton onClick={() => setDialog('delete')}>삭제</TextButton></div></div>
    {d.status === 'QUESTIONING' && <div className="notice">질문이 진행 중인 잠정 결과예요. <Link to={`/decisions/${id}/questions`}>이어서 답변하기 ↗</Link></div>}
    {top.probability < .55 && <div className="notice">아직 한 선택지로 좁히기 어려워요. 답변을 더하거나 기준별 입력값을 점검해보세요.</div>}
    {locked && <div className="notice">최종 선택 · <strong>{d.options.find(o => o.id === d.finalized?.optionId)?.name}</strong>{d.finalized?.memo && <span> — {d.finalized.memo}</span>}</div>}
    <section className="result-hero"><div><p className="eyebrow muted">현재의 추천</p><h1>지금의 기준에는<br />{topOption.name}.</h1></div><div className="confidence"><p className="caption muted">1위가 될 확률</p><p className="metric">{Math.round(top.probability * 100)}<span>%</span></p><p className="eyebrow muted">결정 안정성 · {analysis.stability}</p></div></section>
    <section className="editorial-section"><h2>01 판단의 근거</h2><div><p>{withParticle(topOption.name, '은', '는')} {contributions.slice(0, 2).map(x => x.c.name).join(' · ')} 기준을 함께 고려했을 때 가장 높은 종합 점수를 얻었어요.</p><p className="muted">{d.conditions.length ? '설정한 필수 조건도 모두 충족해요.' : '직접 입력한 값과 질문 답변에서 추정한 중요도를 바탕으로 비교했어요.'}</p></div></section>
    <section className="editorial-section"><h2>02 추천이 바뀌는 조건</h2><div>{changes.length ? changes.slice(0, 2).map(c => <p key={c.criterionId}><strong>{d.criteria.find(k => k.id === c.criterionId)?.name}</strong>의 중요도가 <strong>{(c.threshold * 100).toFixed(1)}% {c.direction === 'increase' ? '이상' : '이하'}</strong>가 되면 {withParticle(d.options.find(o => o.id === c.newTop)?.name ?? '다른 선택지', '이', '가')} 유리해져요.</p>) : <p>현재 입력 범위에서는 기준 하나의 중요도를 바꿔도 1위가 유지돼요.</p>}<p className="muted">우선순위가 달라진다면 아래에서 기준을 조정해보세요.</p></div></section>
    {hidden.length > 0 && <section className="editorial-section"><h2>03 답변에서 발견한 선호</h2><div>{hidden.map(t => <p key={t}>{t}</p>)}</div></section>}
    <div className="result-columns"><section><h2 className="section-title">선택지 비교</h2><RankingList d={d} analysis={analysis} />{analysis.excluded.map(e => <div key={e.optionId} className="ranking-row excluded"><span>— {d.options.find(o => o.id === e.optionId)?.name}</span><small>{e.reasons.join(', ')}</small></div>)}</section><section><h2 className="section-title">답변에서 드러난 중요도</h2><div className="weight-bars">{analysis.weights.map(w => <div className="weight-row" key={w.criterionId}><span>{d.criteria.find(c => c.id === w.criterionId)?.name}</span><div className="weight-track"><div style={{ width: `${w.mean * 100}%` }} /><span className="weight-interval" style={{ left: `${w.interval[0] * 100}%`, width: `${(w.interval[1] - w.interval[0]) * 100}%` }} /></div><strong>{Math.round(w.mean * 100)}%</strong></div>)}</div><small className="muted caption">가는 선은 중요도의 80% 추정 구간이에요.</small></section></div>
    {analysis.excluded.length > 0 && <p className="caption muted exclusion-note">필수 조건을 충족하지 않는 선택지는 점수·순위 계산에서 제외했어요.</p>}
    <details className="result-values"><summary>입력한 비교 값과 기준 확인</summary><ValueSummary d={d} /><Link to={`/decisions/${id}/setup/3`}>{locked ? '구성 내용 보기' : '입력값 수정'} ↗</Link></details>
    <p className="caption muted source-note">로컬 체험 분석 · 실제 입력과 답변으로 계산한 참조 결과예요. 서버 엔진·AI에는 연결되지 않았어요.{d.example && ' 제품 정보는 시연용 예시예요.'}</p>
  </div><footer className="page-footer result-actions">{locked ? <Button onClick={duplicate}>복제해서 다시 고민하기</Button> : <Button disabled={d.status !== 'ANALYZED'} onClick={() => { setFinalId(top.optionId); setDialog('finalize'); }}>이 선택으로 확정</Button>}<Button secondary onClick={() => navigate(`/decisions/${id}/simulate`)}>기준 바꿔보기</Button>{!locked && <TextButton onClick={() => { const next = nextQuestion(d); if (!next) { notify('추가로 물어볼 새 질문이 없어요. 비교 기준을 추가하면 더 확인할 수 있어요.'); return; } if (update(x => { x.status = 'QUESTIONING'; x.questionLimit = x.answers.length + 3; x.pendingQuestion = next; })) navigate(`/decisions/${id}/questions`); }}>질문 더 하기</TextButton>}<TextButton onClick={() => setDialog('export')}>파일로 내보내기</TextButton></footer>
    <div className="print-only"><h2>입력한 비교 값</h2><ValueSummary d={d} /></div>
    {dialog === 'edit' && <Modal open onClose={() => setDialog(null)} title="결정 정보를 수정해요." description="비교 내용은 유지하면서 제목과 설명을 바꿔요."><form className="form-stack" onSubmit={e => { e.preventDefault(); if (title.trim() && update(x => { x.title = title.trim(); x.description = description; })) setDialog(null); }}><Field label="결정 제목" value={title} onValueChange={value => setTitle(value)} maxLength={100} required autoFocus /><Field label="설명" value={description} onValueChange={value => setDescription(value)} maxLength={1000} /><div className="modal-actions"><Button secondary type="button" onClick={() => setDialog(null)}>취소</Button><Button type="submit">변경사항 저장</Button></div></form></Modal>}
    {dialog === 'delete' && <Modal open onClose={() => setDialog(null)} title="이 결정을 삭제할까요?" description={`${d.title}의 입력 내용과 분석 결과를 삭제해요. 되돌릴 수 없어요.`}><div className="modal-actions"><Button secondary onClick={() => setDialog(null)}>취소</Button><Button onClick={() => { try { repository.remove(owner, id); cache.removeQueries({ queryKey: ['decision', owner, id] }); void cache.invalidateQueries({ queryKey: ['decisions', owner] }); navigate('/'); } catch (e) { notify(String(e)); } }}>결정 삭제</Button></div></Modal>}
    {dialog === 'finalize' && <Modal open onClose={() => setDialog(null)} title="어떤 선택으로 남길까요?" description="추천과 다른 선택도 괜찮아요. 확정 후에는 입력과 답변을 수정할 수 없어요."><form className="form-stack" onSubmit={e => { e.preventDefault(); if (!analysis.rankings.some(r => r.optionId === finalId)) return; if (update(x => { if (x.status !== 'ANALYZED') throw new Error('먼저 질문을 종료해주세요.'); x.finalized = { optionId: finalId, memo, at: new Date().toISOString() }; x.status = 'FINALIZED'; })) { setDialog(null); notify('최종 선택을 기록했어요.'); } }}><label className="field">최종 선택<select value={finalId} onChange={e => setFinalId(e.target.value)}>{analysis.rankings.map(r => <option key={r.optionId} value={r.optionId}>{r.name}</option>)}</select></label><Field label="결정 메모 · 선택" value={memo} onValueChange={value => setMemo(value)} maxLength={500} placeholder="이 선택을 한 이유를 남겨보세요" /><div className="modal-actions"><Button secondary type="button" onClick={() => setDialog(null)}>돌아가기</Button><Button type="submit">이 선택으로 확정</Button></div></form></Modal>}
    {dialog === 'export' && <Modal open onClose={() => setDialog(null)} title="결과를 파일로 남길까요?" description="추천과 비교표, 중요도, 전환 조건, 최종 선택을 함께 저장해요."><div className="form-stack"><Button onClick={() => { downloadMarkdown(d, analysis); notify('결과 파일을 다운로드했어요.'); }}>Markdown 다운로드</Button><Button secondary onClick={() => { setDialog(null); setTimeout(() => window.print(), 150); }}>인쇄 / PDF로 저장</Button><p className="caption muted">PDF는 인쇄 창에서 ‘PDF로 저장’을 선택해주세요.</p></div></Modal>}
  </main>;
}
export function RankingList({ d, analysis }: { d: Decision; analysis: Analysis }) {
  return <div>{analysis.rankings.map(r => <div className="ranking-row" key={r.optionId}><span>{r.rank} {d.options.find(o => o.id === r.optionId)?.name}</span><span className="muted">{(r.score * 100).toFixed(1)}점</span></div>)}</div>;
}
function ValueSummary({ d }: { d: Decision }) {
  return <div className="matrix-scroll"><table className="summary-table"><thead><tr><th>선택지</th>{d.criteria.map(c => <th key={c.id}>{c.name}</th>)}</tr></thead><tbody>{d.options.map(o => <tr key={o.id}><th>{o.name}</th>{d.criteria.map(c => <td key={c.id}>{formatValue(d.values[o.id]?.[c.id], c)}</td>)}</tr>)}</tbody></table></div>;
}
