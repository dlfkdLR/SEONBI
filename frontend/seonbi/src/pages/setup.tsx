import { useEffect, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import type { Category, CellValue, Condition, Criterion, Decision } from '../domain/types';
import { categories, uid } from '../domain/types';
import { exclusionReasons, freezeScales, nextQuestion, validationErrors } from '../domain/engine';
import { presetCriteria } from '../data/examples';
import { useDecision } from '../data/hooks';
import { Button, ErrorNotice, Field, Loading, Modal, PageHeading, TextButton } from '../components/ui';
import { CriterionEditor } from '../components/criterion-editor';
import { Input } from '../components/input';
import { useUI } from '../lib/state';

const steps = ['주제와 선택지', '평가 기준', '값 비교', '필수 조건'];
const titles = ['어떤 결정을 앞두고 있나요?', '무엇을 기준으로 비교할까요?', '선택지를 같은 기준으로 놓고 보세요.', '꼭 지켜야 할 조건이 있나요?'];
const descriptions = ['제품·전공·활동 등 비교하고 싶은 선택지를 자유롭게 입력하세요.', '기준의 이름·입력 방식·단위를 직접 정하세요. 추가한 기준은 비교 표의 열이 됩니다.', '비교할 대상과 기준을 자유롭게 바꾸고, 각 값을 입력하세요.', '중요한 기준과 반드시 충족해야 하는 조건을 구분해요.'];

export default function SetupPage() {
  const { id = '', step: stepParam = '1' } = useParams(), step = Number(stepParam);
  const { data: d, isPending, error, update } = useDecision(id), navigate = useNavigate(), notify = useUI(s => s.notify);
  const [errors, setErrors] = useState<string[]>([]), [editing, setEditing] = useState<Criterion | 'new' | null>(null), [deleting, setDeleting] = useState<Criterion | null>(null), [suggestions, setSuggestions] = useState(false), [optionDialog, setOptionDialog] = useState<string | 'new' | null>(null), [optionName, setOptionName] = useState(''), [tagOpen, setTagOpen] = useState(false), [thresholdOpen, setThresholdOpen] = useState(false);
  useEffect(() => setErrors([]), [step]);
  if (isPending) return <Loading />;
  if (error) return <main className="page" id="main"><ErrorNotice message={error.message} /></main>;
  if (!d) return <main className="page" id="main"><PageHeading title="결정을 찾을 수 없어요." /><Link to="/">내 결정으로 돌아가기</Link></main>;
  if (![1, 2, 3, 4].includes(step)) return <Navigate to={`/decisions/${id}/setup/1`} replace />;
  const locked = d.status === 'FINALIZED';
  const goStep = (to: number) => {
    const missing = to > step ? validationErrors(d, to - 1) : [];
    if (missing.length) { setErrors(missing); return; }
    if (!locked) update(x => { x.step = to; });
    navigate(`/decisions/${id}/setup/${to}`);
  };
  const addOption = (name: string) => {
    if (d.options.length >= 8) { notify('선택지는 8개까지 등록할 수 있어요.'); return false; }
    if (!name.trim() || d.options.some(o => o.name === name.trim())) { notify('서로 다른 선택지 이름을 입력해주세요.'); return false; }
    return update(x => { const oid = uid(); x.options.push({ id: oid, name: name.trim() }); x.values[oid] = Object.fromEntries(x.criteria.map(c => [c.id, c.type === 'rating' && c.inputMode === 'score' ? Math.ceil(c.levels.length / 2) : null])); });
  };
  const saveCriterion = (c: Criterion) => {
    const old = d.criteria.find(k => k.id === c.id);
    if (!old && d.criteria.length >= 7) { notify('평가 기준은 7개까지 등록할 수 있어요.'); return; }
    const success = update(x => {
      const idx = x.criteria.findIndex(k => k.id === c.id);
      const scaleChanged = old && (old.direction !== c.direction || old.scaleMin !== c.scaleMin || old.scaleMax !== c.scaleMax || old.levels.length !== c.levels.length);
      const formatChanged = old && (old.type !== c.type || old.inputMode !== c.inputMode);
      if (idx < 0) x.criteria.push(c); else x.criteria[idx] = c;
      if (!old || formatChanged || (c.type === 'rating' && old.levels.length !== c.levels.length)) for (const o of x.options) {
        x.values[o.id] ??= {}; x.values[o.id][c.id] = c.type === 'rating' && c.inputMode === 'score' ? Math.ceil(c.levels.length / 2) : null;
      }
      if (formatChanged || scaleChanged) x.answers.forEach(a => { if (a.question.criteria.includes(c.id)) a.isValid = false; });
      if (!old && x.status !== 'DRAFT') { x.status = 'QUESTIONING'; x.questionLimit = Math.max(x.questionLimit ?? 0, x.answers.length + 3); }
      x.pendingQuestion = undefined;
    });
    if (success) { setEditing(null); notify(old ? '평가 기준을 수정했어요.' : '평가 기준을 추가했어요.'); }
  };
  const start = () => {
    const missing = validationErrors(d);
    if (missing.length) { setErrors(missing); return; }
    const success = update(x => {
      const frozen = freezeScales(x); x.criteria = frozen.criteria;
      if (x.status === 'ANALYZED') x.questionLimit = x.answers.length + 3;
      x.status = 'QUESTIONING'; x.pendingQuestion ??= nextQuestion(x);
      if (!x.pendingQuestion) x.status = 'ANALYZED';
    });
    if (success) navigate(`/decisions/${id}/${nextQuestion(d) ? 'questions' : 'result'}`);
  };
  const removeOption = (optionId: string) => {
    if (d.options.length <= 2) { notify('비교하려면 선택지가 2개 이상 필요해요.'); return; }
    update(x => { x.options = x.options.filter(o => o.id !== optionId); delete x.values[optionId]; x.conditions.forEach(c => { if (c.kind === 'required_tag') c.satisfiedBy = c.satisfiedBy.filter(v => v !== optionId); }); });
  };
  return <main className="page setup-page" id="main"><div className="page-content"><PageHeading context={step === 1 ? '새 결정' : d.title || '제목 없는 결정'} title={titles[step - 1]} description={descriptions[step - 1]} />
    <nav className="setup-steps" aria-label="결정 구성 단계">{steps.map((name, i) => <button aria-current={step === i + 1 ? 'step' : undefined} onClick={() => goStep(i + 1)} key={name}>{`0${i + 1}`} <span>{name}</span></button>)}</nav>
    {locked && <div className="notice">확정한 결정의 입력 내용입니다. 변경하려면 결과 화면에서 복제해주세요. <Link to={`/decisions/${id}/result`}>결과 보기 ↗</Link></div>}
    {step === 1 && <div className="setup-columns"><fieldset disabled={locked} className="decision-form"><Field label="결정 제목" value={d.title} onValueChange={value => update(x => { x.title = value; })} placeholder="예: 다음 학기 전공 선택" maxLength={100} />
      <Field label="어디에, 어떻게 사용할 예정인가요?" value={d.description} onValueChange={value => update(x => { x.description = value; })} placeholder="결정의 목적과 현재 상황을 적어주세요" maxLength={1000} />
      <h2 className="section-title">비교할 선택지 · {d.options.length}개</h2>
      {d.options.map((o, i) => <div className="option-row" key={o.id}><span className="muted eyebrow">{String(i + 1).padStart(2, '0')}</span><Input aria-label={`선택지 ${i + 1}`} value={o.name} maxLength={60} placeholder={`선택지 ${i + 1} 이름`} onValueChange={value => update(x => { x.options.find(v => v.id === o.id)!.name = value; })} /><TextButton className="caption muted" onClick={() => removeOption(o.id)} aria-label={`${o.name || `선택지 ${i + 1}`} 삭제`}>삭제</TextButton></div>)}
      <TextButton className="muted" disabled={d.options.length >= 8} onClick={() => update(x => { const oid = uid(); x.options.push({ id: oid, name: '' }); x.values[oid] = {}; })}>+ 선택지 추가</TextButton>
    </fieldset><aside className="input-guidance"><h2 className="section-title">비교할 대상은 자유로워요.</h2><p>선택지 이름과 개수는<br />언제든 바꿀 수 있어요.</p><p>여기서 입력한 선택지는<br />값 비교 표의 행이 됩니다.</p><label className="field">결정 분류<select value={d.category} disabled={locked} onChange={e => update(x => { x.category = e.target.value as Category; })}>{Object.entries(categories).map(([v, name]) => <option key={v} value={v}>{name}</option>)}</select><small>기본 기준 제안에만 사용해요.</small></label></aside></div>}
    {step === 2 && <><div className="recommendation-strip"><span>이 주제에 어울리는 기준</span><Button secondary disabled={locked} onClick={() => setSuggestions(true)}>기본 기준 제안 보기</Button></div>
      <div className="criteria-table"><div className="criteria-row criteria-head"><span>선택</span><span>기준</span><span>입력 방식</span><span>좋은 방향</span><span>단위</span><span>관리</span></div>
        {d.criteria.map(c => <div className="criteria-row" key={c.id}><span>✓</span><strong>{c.name}</strong><span>{c.type === 'numeric' ? '숫자로 비교' : c.type === 'boolean' ? '예 / 아니요' : c.inputMode === 'rank' ? '순위로 비교' : '단계로 비교'}</span><span>{c.inputMode === 'rank' ? '1위가 가장 좋음' : c.type === 'boolean' ? '예가 좋음' : c.direction === 'lower_better' ? '낮을수록 좋음' : '높을수록 좋음'}</span><span>{c.type === 'numeric' ? c.unit || '—' : c.type === 'rating' && c.inputMode === 'score' ? `${c.levels.length}단계` : '—'}</span><div className="row-controls"><TextButton disabled={locked} onClick={() => setEditing(c)} aria-label={`${c.name} 수정`}>수정</TextButton><span>·</span><TextButton disabled={locked} onClick={() => setDeleting(c)} aria-label={`${c.name} 삭제`}>삭제</TextButton></div></div>)}
        {!d.criteria.length && <div className="empty-inline">비교할 기준을 직접 추가하거나, 분류에 맞는 기본 기준을 골라보세요.</div>}
      </div><TextButton className="add-action" disabled={locked || d.criteria.length >= 7} onClick={() => setEditing('new')}>+ 기준 직접 추가</TextButton></>}
    {step === 3 && <><div className="table-toolbar"><span className="eyebrow">선택지 {d.options.length}개 · 평가 기준 {d.criteria.length}개</span><div className="flex gap-4"><Button secondary onClick={() => goStep(1)}>선택지 편집</Button><Button secondary onClick={() => goStep(2)}>평가 기준 편집</Button></div></div>
      <div className="matrix-scroll"><table className="value-table" style={{ minWidth: Math.max(900, 272 + d.criteria.length * 212) }}><thead><tr><th>선택지</th>{d.criteria.map(c => <th key={c.id}><button disabled={locked} onClick={() => setEditing(c)} aria-label={`${c.name} 기준 수정`}>{c.name}{c.unit ? ` · ${c.unit}` : c.type === 'rating' ? c.inputMode === 'rank' ? ' · 순위' : ` · ${c.levels.length}단계` : ''}</button></th>)}</tr></thead><tbody>{d.options.map(o => <tr key={o.id}><th scope="row"><div><span>{o.name || '이름 없는 선택지'}</span><TextButton disabled={locked} className="muted caption" onClick={() => { setOptionDialog(o.id); setOptionName(o.name); }} aria-label={`${o.name} 이름 수정`}>수정</TextButton></div></th>{d.criteria.map(c => <td key={c.id}><CellInput criterion={c} optionName={o.name} optionCount={d.options.length} value={d.values[o.id]?.[c.id] ?? null} disabled={locked} onChange={value => update(x => { x.values[o.id] ??= {}; x.values[o.id][c.id] = value; })} /></td>)}</tr>)}</tbody></table></div>
      <div className="table-add-actions"><TextButton disabled={locked || d.options.length >= 8} onClick={() => { setOptionDialog('new'); setOptionName(''); }}>+ 선택지 추가</TextButton><TextButton disabled={locked || d.criteria.length >= 7} onClick={() => setEditing('new')}>+ 평가 기준 추가</TextButton></div>
      <p className="caption muted input-help">선택지를 추가하면 행이, 평가 기준을 추가하면 열이 늘어나요. 기준에 맞는 단위와 입력 방식을 사용하세요.</p></>}
    {step === 4 && <div className="constraints-columns"><fieldset disabled={locked} className="constraints-form"><h2 className="section-title">반드시 지켜야 하는 조건</h2>
      {d.conditions.map(c => <div className="condition-item" key={c.id}>{c.kind === 'threshold' ? <><label className="field">{d.criteria.find(k => k.id === c.criterionId)?.name} {c.operator === '<=' ? '최대' : c.operator === '>=' ? '최소' : '정확히'} · {d.criteria.find(k => k.id === c.criterionId)?.unit}<input type="number" step="any" aria-label={`${d.criteria.find(k => k.id === c.criterionId)?.name} 조건값`} value={c.value} onChange={e => { if (e.target.value !== '') update(x => { const v = x.conditions.find(v => v.id === c.id); if (v?.kind === 'threshold') v.value = Number(e.target.value); }); }} /></label></> : <div><h3>{c.label}</h3><p className="caption muted">이 조건을 충족하는 선택지를 체크해주세요.</p><div className="condition-checks">{d.options.map(o => <label key={o.id}><input type="checkbox" checked={c.satisfiedBy.includes(o.id)} onChange={e => update(x => { const v = x.conditions.find(v => v.id === c.id); if (v?.kind === 'required_tag') v.satisfiedBy = e.target.checked ? [...v.satisfiedBy, o.id] : v.satisfiedBy.filter(id => id !== o.id); })} />{o.name}</label>)}</div></div>}<TextButton className="muted caption" onClick={() => update(x => { x.conditions = x.conditions.filter(v => v.id !== c.id); })} aria-label="필수 조건 삭제">삭제</TextButton></div>)}
      {!d.conditions.length && <p className="muted">필수 조건이 없다면 바로 시작해도 괜찮아요.</p>}
      <div className="flex gap-6"><TextButton onClick={() => setThresholdOpen(true)} disabled={!d.criteria.some(c => c.type === 'numeric')}>+ 숫자 조건 추가</TextButton><TextButton onClick={() => setTagOpen(true)}>+ 필수 조건 추가</TextButton></div><p className="constraint-note muted">조건을 충족하지 않는 선택지는 삭제하지 않고<br />비교에서 제외해요. 나중에 조건을 수정할 수 있어요.</p>
    </fieldset><Eligibility d={d} /></div>}
    {errors.length > 0 && <ErrorNotice message={errors.slice(0, 4).join(' ')} />}
  </div><footer className="page-footer">{step === 1 ? <TextButton className="muted" onClick={() => navigate('/')}>나중에 이어서 작성</TextButton> : <Button secondary onClick={() => goStep(step - 1)}>이전 단계</Button>}<small className="save-status">{locked ? '확정한 결정이에요.' : '이 브라우저에 자동 저장돼요.'}</small>{locked ? <Button onClick={() => navigate(`/decisions/${id}/result`)}>결과로 돌아가기</Button> : <Button onClick={() => step < 4 ? goStep(step + 1) : start()}>{step < 4 ? `다음: ${steps[step]}` : '입력 끝내고 질문 시작'}</Button>}</footer>
    {editing && <CriterionEditor initial={editing === 'new' ? undefined : editing} existingNames={d.criteria.filter(c => editing === 'new' || c.id !== editing.id).map(c => c.name)} onSave={saveCriterion} onClose={() => setEditing(null)} />}
    {deleting && <Modal open onClose={() => setDeleting(null)} title="이 평가 기준을 삭제할까요?" description={`${deleting.name}의 입력값을 지우고, 이 기준에 대한 이전 답변은 분석에서 제외해요.`}><div className="modal-actions"><Button secondary onClick={() => setDeleting(null)}>취소</Button><Button onClick={() => { update(x => { x.criteria = x.criteria.filter(c => c.id !== deleting.id); for (const row of Object.values(x.values)) delete row[deleting.id]; x.conditions = x.conditions.filter(c => c.kind !== 'threshold' || c.criterionId !== deleting.id); x.answers.forEach(a => { if (a.question.criteria.includes(deleting.id)) a.isValid = false; }); x.pendingQuestion = undefined; }); setDeleting(null); }}>기준 삭제</Button></div></Modal>}
    {suggestions && <Suggestions category={d.category} existing={d.criteria} onClose={() => setSuggestions(false)} onApply={criteria => { update(x => { x.criteria.push(...criteria); for (const o of x.options) for (const c of criteria) { x.values[o.id] ??= {}; x.values[o.id][c.id] = c.type === 'rating' ? 3 : null; } if (x.status !== 'DRAFT') { x.status = 'QUESTIONING'; x.questionLimit = x.answers.length + 3; } x.pendingQuestion = undefined; }); setSuggestions(false); }} />}
    {optionDialog && <Modal open onClose={() => setOptionDialog(null)} title={optionDialog === 'new' ? '비교할 선택지를 추가해요.' : '선택지 이름을 수정해요.'}><form className="form-stack" onSubmit={e => { e.preventDefault(); const name = optionName.trim(); if (!name || d.options.some(o => o.id !== optionDialog && o.name === name)) { notify('서로 다른 선택지 이름을 입력해주세요.'); return; } const saved = optionDialog === 'new' ? addOption(name) : update(x => { x.options.find(o => o.id === optionDialog)!.name = name; }); if (saved) setOptionDialog(null); }}><Field label="선택지 이름" value={optionName} onValueChange={value => setOptionName(value)} maxLength={60} required autoFocus /><div className="modal-actions"><Button secondary type="button" onClick={() => setOptionDialog(null)}>취소</Button><Button type="submit">선택지 저장</Button></div></form></Modal>}
    {tagOpen && <TagCondition d={d} onClose={() => setTagOpen(false)} onSave={c => { update(x => { x.conditions.push(c); }); setTagOpen(false); }} />}
    {thresholdOpen && <ThresholdCondition d={d} onClose={() => setThresholdOpen(false)} onSave={c => { update(x => { x.conditions.push(c); }); setThresholdOpen(false); }} />}
  </main>;
}

function CellInput({ criterion: c, optionName, optionCount, value, disabled, onChange }: { criterion: Criterion; optionName: string; optionCount: number; value: CellValue; disabled: boolean; onChange: (v: CellValue) => void }) {
  const label = `${optionName} ${c.name}`;
  if (c.type === 'boolean') return <select aria-label={label} value={value === null ? '' : String(value)} disabled={disabled} onChange={e => onChange(e.target.value === '' ? null : e.target.value === 'true')}><option value="">선택해주세요</option><option value="true">예</option><option value="false">아니요</option></select>;
  if (c.type === 'rating' && c.inputMode === 'score') return <select aria-label={label} value={value === null ? '' : Number(value)} disabled={disabled} onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))}><option value="">선택해주세요</option>{c.levels.map((l, i) => <option key={i} value={i + 1}>{l}</option>)}</select>;
  return <input aria-label={label} type="number" step={c.inputMode === 'rank' ? 1 : 'any'} min={c.inputMode === 'rank' ? 1 : undefined} max={c.inputMode === 'rank' ? optionCount : undefined} disabled={disabled} value={value === null ? '' : Number(value)} placeholder={c.inputMode === 'rank' ? '순위' : '값 입력'} onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))} />;
}
function Eligibility({ d }: { d: Decision }) {
  const excluded = exclusionReasons(d), remaining = d.options.length - excluded.length;
  return <aside className="eligibility"><h2 className="section-title">비교에 남는 선택지</h2>{d.options.map(o => { const e = excluded.find(x => x.optionId === o.id); return <div className="eligibility-row" key={o.id}><strong>{o.name}</strong><p className="caption muted">{e ? `제외 · ${e.reasons.join(', ')}` : '✓ 조건 충족'}</p></div>; })}<p className="caption muted">{remaining >= 2 ? `${remaining}개 선택지로 질문을 시작할 수 있어요.` : '조건을 충족하는 선택지가 부족해요. 조건을 조정해주세요.'}</p></aside>;
}
function Suggestions({ category, existing, onClose, onApply }: { category: Category; existing: Criterion[]; onClose: () => void; onApply: (c: Criterion[]) => void }) {
  const [items] = useState(() => presetCriteria(category).filter(c => !existing.some(k => k.name === c.name))), [selected, setSelected] = useState<string[]>([]);
  return <Modal open onClose={onClose} title="이 기준으로 시작해볼까요?" description="분류별 기본 목록이에요. 추가한 뒤 이름과 입력 방식을 바꿀 수 있어요."><div className="form-stack">{items.length ? items.map(c => <label className="check-row" key={c.id}><input type="checkbox" checked={selected.includes(c.id)} disabled={!selected.includes(c.id) && existing.length + selected.length >= 7} onChange={e => setSelected(v => e.target.checked ? [...v, c.id] : v.filter(id => id !== c.id))} /><span>{c.name}</span><small className="muted">{c.type === 'numeric' ? c.unit : '단계로 비교'}</small></label>) : <p>이 분류의 기본 기준이 모두 추가되어 있어요. 직접 새로운 기준을 추가해주세요.</p>}<div className="modal-actions"><Button secondary onClick={onClose}>취소</Button><Button disabled={!selected.length} onClick={() => onApply(items.filter(c => selected.includes(c.id)))}>선택한 기준 추가</Button></div></div></Modal>;
}
function TagCondition({ d, onClose, onSave }: { d: Decision; onClose: () => void; onSave: (c: Condition) => void }) {
  const [label, setLabel] = useState(''), [satisfied, setSatisfied] = useState(d.options.map(o => o.id));
  return <Modal open onClose={onClose} title="반드시 필요한 조건을 정해요."><form className="form-stack" onSubmit={e => { e.preventDefault(); if (label.trim()) onSave({ id: uid(), kind: 'required_tag', label: label.trim(), satisfiedBy: satisfied }); }}><Field label="조건 이름" value={label} onValueChange={value => setLabel(value)} placeholder="예: Windows 지원, 기숙사 제공" maxLength={100} required autoFocus /><p className="muted">이 조건을 충족하는 선택지를 체크해주세요.</p>{d.options.map(o => <label key={o.id} className="check-row"><input type="checkbox" checked={satisfied.includes(o.id)} onChange={e => setSatisfied(v => e.target.checked ? [...v, o.id] : v.filter(id => id !== o.id))} />{o.name}</label>)}<div className="modal-actions"><Button type="button" secondary onClick={onClose}>취소</Button><Button type="submit">조건 추가</Button></div></form></Modal>;
}
function ThresholdCondition({ d, onClose, onSave }: { d: Decision; onClose: () => void; onSave: (c: Condition) => void }) {
  const [criterionId, setCriterion] = useState(d.criteria.find(c => c.type === 'numeric')?.id ?? ''), [operator, setOperator] = useState<'<=' | '>=' | '=='>('<='), [value, setValue] = useState('');
  return <Modal open onClose={onClose} title="넘지 않아야 하는 범위를 정해요."><form className="form-stack" onSubmit={e => { e.preventDefault(); if (value !== '' && Number.isFinite(Number(value))) onSave({ id: uid(), kind: 'threshold', criterionId, operator, value: Number(value) }); }}><label className="field">평가 기준<select value={criterionId} onChange={e => setCriterion(e.target.value)}>{d.criteria.filter(c => c.type === 'numeric').map(c => <option key={c.id} value={c.id}>{c.name} · {c.unit}</option>)}</select></label><label className="field">조건<select value={operator} onChange={e => setOperator(e.target.value as typeof operator)}><option value="<=">이하</option><option value=">=">이상</option><option value="==">같음</option></select></label><Field label="기준값" type="number" step="any" value={value} onValueChange={value => setValue(value)} required /><div className="modal-actions"><Button secondary type="button" onClick={onClose}>취소</Button><Button type="submit">조건 추가</Button></div></form></Modal>;
}
