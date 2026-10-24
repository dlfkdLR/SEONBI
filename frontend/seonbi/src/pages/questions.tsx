import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import type { Answer, Decision, Question } from '../domain/types';
import { withParticle } from '../lib/korean';
import { maxQuestions } from '../domain/types';
import { analyze, criterionRange, formatValue, freezeScales, hasAskedQuestion, newAnswer, nextQuestion, scenarioValues, shouldStop, validationErrors } from '../domain/engine';
import { useDecision } from '../data/hooks';
import { Button, ErrorNotice, Loading, Modal, PageHeading, TextButton } from '../components/ui';

export default function QuestionsPage() {
  const { id = '' } = useParams(), { data: d, isPending, error, update } = useDecision(id), navigate = useNavigate();
  const [response, setResponse] = useState<Answer['response'] | null>(null), [history, setHistory] = useState(false), [editing, setEditing] = useState<Answer | null>(null), [busy, setBusy] = useState(false);
  const q = d?.pendingQuestion;
  useEffect(() => { setResponse(null); }, [q?.id]);
  useEffect(() => {
    if (!d || d.status !== 'QUESTIONING' || validationErrors(d).length) return;
    if (d.pendingQuestion && !hasAskedQuestion(d.pendingQuestion, d.answers)) return;
    update(x => { x.criteria = freezeScales(x).criteria; x.pendingQuestion = nextQuestion(x); if (!x.pendingQuestion) x.status = 'ANALYZED'; });
  }, [d, update]);
  const current = useMemo(() => d && d.criteria.length >= 2 && !validationErrors(d).length ? analyze(d) : undefined, [d]);
  if (isPending) return <Loading />;
  if (error) return <main className="page" id="main"><ErrorNotice message={error.message} /></main>;
  if (!d) return <Navigate to="/" replace />;
  if (d.status === 'ANALYZED' || d.status === 'FINALIZED') return <Navigate to={`/decisions/${id}/result`} replace />;
  if (d.status === 'DRAFT' || validationErrors(d).length) return <Navigate to={`/decisions/${id}/setup/3`} replace />;
  const answerCount = d.answers.length, limit = d.questionLimit ?? maxQuestions(d.criteria.length);
  const submit = () => {
    if (!q || !response || busy) return;
    setBusy(true);
    let done = false;
    const saved = update(x => {
      // Guard duplicate clicks with the pending question identity.
      if (x.answers.some(a => a.question.id === q.id)) return;
      x.answers.push(newAnswer(q, response)); x.pendingQuestion = undefined;
      const a = analyze(x);
      done = shouldStop(x, a);
      if (!done) x.pendingQuestion = nextQuestion(x);
      if (done || !x.pendingQuestion) { x.status = 'ANALYZED'; done = true; }
    });
    setBusy(false); if (saved && done) navigate(`/decisions/${id}/result`);
  };
  return <main className="page question-page" id="main"><div className="page-content">{q ? <><PageHeading context={`${d.title} · 지금까지 ${answerCount}개 답변`} title={questionTitle(q, d)} description="실제로 고를 것 같은 쪽을 선택해주세요. 다음 질문은 답변에 따라 달라져요." />
    <QuestionInput question={q} decision={d} response={response} onChange={setResponse} />
    <div className="question-help"><p className="muted">정답은 없어요. 지금 내 상황에서 더 마음이 가는 쪽이면 돼요.</p><TextButton className="muted" onClick={() => setResponse({ skip: true })} aria-pressed={response?.skip === true}>{response?.skip ? '✓ 잘 모르겠어요 · 선택됨' : '잘 모르겠어요'}</TextButton></div>
    <div className="question-meta"><small>답변에 따라 질문이 달라져요 · 이번 라운드 최대 {limit}개</small><Link to={`/decisions/${id}/setup/3`}>비교 내용 확인 ↗</Link></div></> : <p role="status" className="muted">다음 질문을 준비하고 있어요.</p>}</div>
    <footer className="page-footer"><Button secondary disabled={!answerCount} onClick={() => setHistory(true)}>이전 답변 수정</Button><small className="save-status">선호를 파악하고 있어요.</small>{answerCount > 0 && <TextButton className="muted" onClick={() => { if (update(x => { x.status = 'ANALYZED'; x.pendingQuestion = undefined; })) navigate(`/decisions/${id}/result`); }}>지금 결과 보기</TextButton>}<Button disabled={!response || !q || busy} onClick={submit}>{busy ? '반영 중…' : '이 답변으로 계속'}</Button></footer>
    {history && <Modal open onClose={() => setHistory(false)} title="이전 답변을 확인해요." description="답변을 수정하면 지금까지의 응답을 다시 분석해요." wide><div className="answer-history">{d.answers.map((a, i) => <div key={a.id} className="answer-row"><div><small className="muted">질문 {i + 1}{!a.isValid ? ' · 기준 변경으로 제외됨' : ''}</small><p>{questionTitle(a.question, d)}</p><p className="muted caption">{a.response.skip ? '잘 모르겠어요' : a.question.type === 'slider_pair' ? `선호 강도 ${a.response.value}` : `${a.response.choice} 선택`}</p></div><TextButton disabled={!a.isValid} onClick={() => { setEditing(structuredClone(a)); setHistory(false); }}>수정</TextButton></div>)}</div></Modal>}
    {editing && <Modal open onClose={() => setEditing(null)} title="이전 답변을 수정해요." description={questionTitle(editing.question, d)} wide><QuestionInput question={editing.question} decision={d} response={editing.response} onChange={r => setEditing(a => a ? { ...a, response: r } : null)} compact /><TextButton className="muted" onClick={() => setEditing(a => a ? { ...a, response: { skip: true } } : null)}>잘 모르겠어요{editing.response.skip ? ' · 선택됨' : ''}</TextButton><div className="modal-actions"><Button secondary onClick={() => setEditing(null)}>취소</Button><Button onClick={() => { update(x => { const a = x.answers.find(a => a.id === editing.id); if (a) a.response = editing.response; }); setEditing(null); }}>수정한 답변 저장</Button></div></Modal>}
    {current && current.ess < 400 && <p className="sr-only">로컬 체험 분석의 유효 표본이 적어 결과가 불안정할 수 있어요.</p>}
  </main>;
}

export function questionTitle(q: Question, d: Decision) {
  const a = d.criteria.find(c => c.id === q.criteria[0])?.name ?? '삭제한 기준', b = d.criteria.find(c => c.id === q.criteria[1])?.name ?? '삭제한 기준';
  return q.type === 'ab_scenario' ? `${withParticle(a, '과', '와')} ${b}, 어떤 조합을 고르시겠어요?` : `${withParticle(a, '과', '와')} ${b}, 어느 쪽이 더 중요한가요?`;
}
export function QuestionInput({ question: q, decision: d, response, onChange, compact = false }: { question: Question; decision: Decision; response: Answer['response'] | null; onChange: (r: Answer['response']) => void; compact?: boolean }) {
  const a = d.criteria.find(c => c.id === q.criteria[0]), b = d.criteria.find(c => c.id === q.criteria[1]);
  if (!a || !b) return <ErrorNotice message="이 질문의 평가 기준이 삭제되어 답변할 수 없어요." />;
  if (q.type === 'slider_pair') return <div className={`preference-slider ${compact ? 'compact' : ''}`}><div className="flex justify-between"><strong>{a.name} ↔ {b.name}</strong><span>{response?.value === undefined ? '직접 움직여주세요' : response.value === 0 ? '비슷해요' : `${response.value > 0 ? a.name : b.name} +${Math.abs(response.value)}`}</span></div><input type="range" aria-label={`${a.name}과 ${b.name} 상대 중요도`} min={-5} max={5} step={1} value={response?.value ?? 0} onChange={e => onChange({ value: Number(e.target.value) })} /><div className="range-labels"><span>{a.name}이 더 중요해요<br /><small>{criterionRange(d, a)}</small></span><button onClick={() => onChange({ value: 0 })}>비슷해요</button><span>{b.name}이 더 중요해요<br /><small>{criterionRange(d, b)}</small></span></div></div>;
  const scenario = q.type === 'ab_scenario' ? q.scenario ?? scenarioValues(d, a, b) : null;
  return <div className={`choice-cards ${compact ? 'compact' : ''}`} role="group" aria-label="질문 선택지">{(['A', 'B'] as const).map((choice, i) => {
    const selected = response?.choice === choice && !response.skip, c = i === 0 ? a : b;
    const profile = choice === 'A' ? scenario?.a : scenario?.b;
    return <button key={choice} className={`choice-card ${selected ? 'selected' : ''}`} aria-pressed={selected} onClick={() => onChange({ choice })}><small>{choice}{selected ? ' · 선택됨' : ''}</small><h2>{withParticle(c.name, '을', '를')} 더 중요하게</h2>{profile ? <><p>{a.name} · {formatValue(profile[0], a)}</p><p>{b.name} · {formatValue(profile[1], b)}</p></> : <><p>{c.name} 차이를 더 중요하게 생각해요.</p><p className="muted">{criterionRange(d, c)}</p></>}</button>;
  })}</div>;
}
