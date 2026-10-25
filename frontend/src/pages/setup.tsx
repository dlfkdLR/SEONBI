import { useEffect, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import type { Category, CellValue, Condition, Criterion, Decision } from '../domain/types';
import { categories, uid } from '../domain/types';
import { validationErrors } from '../domain/forms';
import { previewConditions } from '../domain/conditions';
import { api } from '../api/http';
import type { CriterionDto } from '../api/contracts';
import { criterion } from '../data/examples';
import { useDecision } from '../data/hooks';
import { Button, ErrorNotice, Field, Loading, Modal, PageHeading, TextButton } from '../components/ui';
import { CriterionEditor } from '../components/criterion-editor';
import { CriterionCard, criterionDirection } from '../components/criterion-card';
import { Input } from '../components/input';
import { useUI } from '../lib/state';
import { withParticle } from '../lib/korean';
import { phoneCriterion } from '../domain/phone-criteria';

const titles = ['어떤 결정을\n앞두고 있나요?', '어떤 것들을\n비교할까요?', '어떤 점을 비교할까요?', '', '꼭 지켜야 하는\n조건이 있나요?'];
const descriptions = ['고민 하나만 적어주세요. 어떤 상황인지 알려주면 더 잘 비교할 수 있어요.', '두 개부터 시작할 수 있어요. 이름과 개수는 나중에 바꿔도 괜찮아요.', '비교 기준은 후보들의 장단점을 살펴볼 항목이에요.\n가격, 성능, 무게처럼 비교하고 싶은 항목을 골라주세요.', '', '반드시 충족해야 하는 조건이에요. 맞지 않는 후보는 추천에서 빠져요.'];
const backLabels = ['‹ 처음으로', '‹ 고민 제목으로', '‹ 후보 입력으로', '‹ 비교할 항목으로', '‹ 후보 정보로'];
const nextLabels = ['후보 적기', '비교할 항목 고르기', '후보 정보 입력하기', '입력 마치기', '이 조건으로 질문 시작'];
const hiddenRecommendations = new Set(['가격 합리성', '사용자 만족도 기대', '기기 신뢰성 및 위험 부담', '사용 효율성']);

export default function SetupPage() {
  const { id = '', step: stepParam = '1' } = useParams(), step = Number(stepParam);
  const { data: d, isPending, error, update, save, isSaving, dirty } = useDecision(id), navigate = useNavigate(), notify = useUI(s => s.notify);
  const [errors, setErrors] = useState<string[]>([]), [editing, setEditing] = useState<Criterion | 'new' | null>(null);
  const [valueIndex, setValueIndex] = useState(0), [tagOpen, setTagOpen] = useState(false), [thresholdOpen, setThresholdOpen] = useState(false);
  const [pausedConditions, setPausedConditions] = useState<Condition[]>([]), [available, setAvailable] = useState<Criterion[]>([]);
  const suggestions = useQuery({ queryKey: ['criterion-recommendations', id], enabled: id !== 'new' && step === 3,
    queryFn: async () => await api.request('recommendCriteria', { params: { decisionId: id } }) as { items: CriterionDto[] }, staleTime: Infinity });
  useEffect(() => { setErrors([]); setValueIndex(0); }, [step]);
  useEffect(() => { setPausedConditions([]); setAvailable([]); }, [id]);
  useEffect(() => {
    if (!d || !Array.isArray(suggestions.data?.items)) return;
    setAvailable(suggestions.data.items.filter(c => !hiddenRecommendations.has(c.name.trim())).map(c => phoneCriterion({ ...criterion(c.name, c.type, c.direction, c.unit), ...c, id: uid(), unit: c.unit ?? '', levels: c.levels ?? criterion('', 'rating').levels, inputMode: c.inputMode ?? 'score', source: 'ai_recommended' }, d)));
  }, [suggestions.data, d?.title, d?.options]);
  useEffect(() => {
    if (!d || d.status !== 'DRAFT' || step !== 3 || d.criteria.some(c => c.name === '배터리 용량') || !d.criteria.some(c => phoneCriterion(c, d) !== c)) return;
    update(x => {
      for (const c of x.criteria) {
        const next = phoneCriterion(c, x);
        if (next === c) continue;
        const oldId = c.id;
        // A new criterion ID prevents persisted hour values from becoming mAh.
        Object.assign(c, next, { id: uid() });
        delete c.scaleMin; delete c.scaleMax;
        for (const row of Object.values(x.values)) delete row[oldId];
        x.conditions = x.conditions.filter(condition => condition.kind !== 'threshold' || condition.criterionId !== oldId);
        break;
      }
    });
  }, [d, step, update]);
  if (isPending) return <Loading />;
  if (error) return <main className="page" id="main"><ErrorNotice message={error.message} /></main>;
  if (!d) return <main className="page" id="main"><PageHeading title="결정을 찾을 수 없어요." /><Link to="/decisions">공유 결정으로 돌아가기</Link></main>;
  if (![1, 2, 3, 4, 5].includes(step)) return <Navigate to={`/decisions/${id}/setup/1`} replace />;
  // A fresh decision cannot be configured before the server has issued its ID.
  if (id === 'new' && step > 2) return <Navigate to="/decisions/new/setup/1" replace />;
  const locked = d.status === 'FINALIZED', active = d.criteria[Math.min(valueIndex, Math.max(0, d.criteria.length - 1))];
  const path = (to: number, nextId = id) => `/decisions/${nextId}/setup/${to}`;
  const goStep = async (to: number) => {
    if (isSaving) return;
    let missing: string[] = [];
    if (to > step) missing = step === 1 ? (!d.title.trim() ? ['고민 제목을 입력해주세요.'] : []) : validationErrors(d, step - 1);
    if (missing.length) { setErrors(missing); return; }
    if (locked || (id === 'new' && to <= 2)) { navigate(path(to)); return; }
    const saved = await save();
    if (saved) navigate(path(to, saved.id));
    else setErrors(['저장하지 못했어요. 입력 내용은 그대로예요. 연결 상태를 확인한 뒤 다시 시도해주세요.']);
  };
  const saveCriterion = (c: Criterion) => {
    const old = d.criteria.find(k => k.id === c.id);
    if (!old && d.criteria.length >= 7) { notify('비교 항목은 7개까지 고를 수 있어요.'); return; }
    if (update(x => {
      const i = x.criteria.findIndex(k => k.id === c.id); if (i < 0) x.criteria.push(c); else x.criteria[i] = c;
      if (!old || old.type !== c.type || old.inputMode !== c.inputMode) for (const o of x.options) { x.values[o.id] ??= {}; x.values[o.id][c.id] = null; }
    })) setEditing(null);
  };
  const toggleCriterion = (c: Criterion) => {
    const selected = d.criteria.some(k => k.name === c.name);
    if (!selected) { saveCriterion(c); return; }
    if (!available.some(k => k.name === c.name)) setAvailable(v => [...v, c]);
    update(x => { const current = x.criteria.find(k => k.name === c.name)!; x.criteria = x.criteria.filter(k => k.id !== current.id); for (const row of Object.values(x.values)) delete row[current.id]; x.conditions = x.conditions.filter(k => k.kind !== 'threshold' || k.criterionId !== current.id); });
  };
  const allCriteria = [...d.criteria, ...available.filter(c => !d.criteria.some(k => k.name === c.name))];
  const removeOption = (optionId: string) => {
    if (d.options.length <= 2) { notify('비교하려면 후보가 2개 이상 필요해요.'); return; }
    update(x => { x.options = x.options.filter(o => o.id !== optionId); delete x.values[optionId]; x.conditions.forEach(c => { if (c.kind === 'required_tag') c.satisfiedBy = c.satisfiedBy.filter(v => v !== optionId); }); });
  };
  const start = async (skip = false) => {
    if (isSaving) return;
    const missing = validationErrors(d); if (missing.length) { setErrors(missing); return; }
    // Skipping is explicit and reversible; only active conditions reach the API.
    const saved = await save(skip ? { ...d, conditions: [] } : undefined);
    if (!saved) { setErrors(['저장하지 못했어요. 입력 내용을 유지했으니 다시 시도해주세요.']); return; }
    if (!saved.readiness?.canStartQuestions) { setErrors(['아직 질문을 시작할 수 없어요. 후보와 비교 항목의 입력값을 확인해주세요.']); return; }
    try { await api.request('startQuestions', { params: { decisionId: saved.id } }); navigate(`/decisions/${saved.id}/questions`); }
    catch (e) { setErrors([e instanceof Error ? e.message : '질문을 시작하지 못했어요.']); }
  };
  const nextValue = async () => {
    if (!active) return;
    const missing = validationErrors({ ...d, criteria: [active] }, 3).filter(e => e.includes('값을 입력'));
    if (missing.length) { setErrors(missing); return; }
    setErrors([]);
    if (valueIndex < d.criteria.length - 1) setValueIndex(v => v + 1); else await goStep(5);
  };
  const allConditions = [...d.conditions, ...pausedConditions.filter(c => !d.conditions.some(a => a.id === c.id))];
  const preview = previewConditions(d), eligible = preview.filter(o => o.eligible);
  const toggleCondition = (c: Condition, enabled: boolean) => {
    if (enabled) { update(x => { x.conditions.push(c); }); setPausedConditions(v => v.filter(k => k.id !== c.id)); }
    else { update(x => { x.conditions = x.conditions.filter(k => k.id !== c.id); }); setPausedConditions(v => [...v, c]); }
  };
  return <main className={`page setup-page setup-step-${step}`} id="main"><div className="page-content" inert={isSaving}>
    <p className="eyebrow stage">{step <= 4 ? `새 결정 · ${step} / 4` : '필요할 때만 · 선택 사항'}</p>
    {step !== 4 && <PageHeading title={titles[step - 1]} description={descriptions[step - 1]} />}
    {locked && <div className="notice">확정한 결정이에요. 다시 고민하고 싶다면 결과에서 복제해주세요. <Link to={`/decisions/${id}/result`}>결과 보기 ›</Link></div>}
    {step === 1 && <fieldset disabled={locked} className="surface topic-form"><Field label="고민 제목" value={d.title} onValueChange={value => update(x => { x.title = value; })} placeholder="예: 새 노트북 고르기" maxLength={100} autoFocus /><Field label="어디에 사용할 건가요? · 선택" value={d.description} onValueChange={value => update(x => { x.description = value; })} placeholder="예: 통학하면서 학교 개발 수업에 쓸 노트북" maxLength={1000} /><p className="caption">제품, 전공, 동아리, 여행지… 어떤 고민이든 좋아요.</p><details className="advanced"><summary>고민 종류 선택 · 선택</summary><label className="field">고민 종류<select value={d.category} onChange={e => update(x => { x.category = e.target.value as Category; })}>{Object.entries(categories).map(([v, name]) => <option key={v} value={v}>{name}</option>)}</select><small>비교 항목을 제안할 때만 참고해요.</small></label></details></fieldset>}
    {step === 2 && <><fieldset disabled={locked} className="surface candidates-form">{d.options.map((o, i) => <div className="option-row" key={o.id}><span className="caption">{String(i + 1).padStart(2, '0')}</span><Input aria-label={`후보 ${i + 1}`} value={o.name} placeholder={`후보 ${i + 1} 이름`} maxLength={60} onValueChange={value => update(x => { x.options.find(v => v.id === o.id)!.name = value; })} /><TextButton onClick={() => removeOption(o.id)} disabled={d.options.length <= 2} aria-label={`${o.name || `후보 ${i + 1}`} 삭제`}>×</TextButton></div>)}<TextButton disabled={d.options.length >= 8} onClick={() => update(x => { const oid = uid(); x.options.push({ id: oid, name: '' }); x.values[oid] = {}; })}>+ 후보 추가하기</TextButton></fieldset><p className="caption centered">고민 중인 대상을 자유롭게 적어주세요. 후보는 2~8개까지 비교할 수 있어요.</p></>}
    {step === 3 && <><div className="criteria-grid">{allCriteria.map(c => <div className="criterion-item" key={c.id}><CriterionCard criterion={c} selected={d.criteria.some(k => k.name === c.name)} disabled={locked} onToggle={() => toggleCriterion(c)} />{d.criteria.some(k => k.name === c.name) && <TextButton className="criterion-edit" disabled={locked} aria-label={`${c.name} 수정`} onClick={() => setEditing(c)}>수정</TextButton>}</div>)}</div>
      {suggestions.isPending && <p role="status" className="caption centered">이 고민에 어울리는 항목을 가져오고 있어요.</p>}{suggestions.error && <div className="inline-feedback"><p>추천 항목을 가져오지 못했어요. 직접 추가해서 계속할 수 있어요.</p><TextButton onClick={() => void suggestions.refetch()}>다시 가져오기</TextButton></div>}{!allCriteria.length && !suggestions.isPending && <p className="caption centered">비교하고 싶은 특징을 직접 추가해보세요.</p>}
      <div className="criteria-toolbar"><p className="caption">비교할 항목을 2~7개 골라주세요. 필요 없는 항목은 눌러서 빼세요.</p><TextButton disabled={locked || d.criteria.length >= 7} onClick={() => setEditing('new')}>+ 직접 추가하기</TextButton></div><p className="caption centered">‘180만 원 이하만’처럼 반드시 지킬 조건은 뒤에서 따로 정해요.</p></>}
    {step === 4 && (active ? <><fieldset disabled={locked} className="surface value-panel"><p className="eyebrow">선택한 {d.criteria.length}개 항목 중 {Math.min(valueIndex + 1, d.criteria.length)}번째</p><h1>{active.type === 'boolean' ? `${active.name}에 해당하나요?` : `${withParticle(active.name, '은', '는')} ${active.type === 'numeric' ? '얼마인가요?' : '어느 정도인가요?'}`}</h1><p className="muted">{criterionDirection(active)} {active.type === 'rating' ? active.inputMode === 'rank' ? '같은 순위도 입력할 수 있어요.' : `모든 후보를 같은 ${active.levels.length}단계로 비교해요.` : ''}</p><div className="value-rows">{d.options.map(o => <div className="value-row" key={o.id}><span className="value-option-name">{o.name}</span><CellInput criterion={active} optionName={o.name} optionCount={d.options.length} value={d.values[o.id]?.[active.id] ?? null} disabled={locked} onChange={value => update(x => { x.values[o.id] ??= {}; x.values[o.id][active.id] = value; })} /><span className="caption">{active.unit || (active.inputMode === 'rank' ? '순위' : '')}</span></div>)}</div><div className="value-actions"><TextButton onClick={() => { setErrors([]); if (valueIndex > 0) setValueIndex(v => v - 1); else void goStep(3); }}>{valueIndex ? '‹ 이전 항목' : '‹ 비교 항목 다시 고르기'}</TextButton><Button onClick={() => void nextValue()}>{valueIndex === d.criteria.length - 1 ? '입력 마치기' : '다음 항목'}</Button></div></fieldset><p className="caption centered">같은 항목에는 같은 단위를 써주세요. 입력한 내용은 이전 항목에서 다시 고칠 수 있어요.</p></> : <div className="empty-state"><p>먼저 비교할 항목을 골라주세요.</p><Button onClick={() => void goStep(3)}>비교 항목 고르기</Button></div>)}
    {step === 5 && <><div className="surface condition-explanation"><div><p className="eyebrow">비교 기준</p><h2>가벼울수록 좋아요.</h2><p className="caption">장단점을 비교할 때 사용해요.</p></div><div><p className="eyebrow">필수 조건</p><h2>1.3kg 이하여야 해요.</h2><p className="caption">1.3kg을 넘으면 추천하지 않아요.</p></div></div>
      <fieldset disabled={locked} className="conditions-grid">{allConditions.map(c => { const enabled = d.conditions.some(v => v.id === c.id), item = c.kind === 'threshold' ? d.criteria.find(v => v.id === c.criterionId) : undefined; return <div className={`surface condition-card ${enabled ? '' : 'condition-paused'}`} key={c.id}><div className="condition-heading"><h2>{c.kind === 'required_tag' ? c.label : `${item?.name ?? '숫자'} 조건`}</h2><label className="toggle"><input type="checkbox" aria-label={`${c.kind === 'required_tag' ? c.label : item?.name} 조건 사용`} checked={enabled} onChange={e => toggleCondition(c, e.target.checked)} /><span aria-hidden="true" /></label></div>{c.kind === 'threshold' ? <div className="threshold-input"><Input type="number" step="any" aria-label={`${item?.name} 조건값`} value={c.value} disabled={!enabled} onValueChange={value => { if (value !== '' && Number.isFinite(Number(value))) update(x => { const condition = x.conditions.find(v => v.id === c.id); if (condition?.kind === 'threshold') condition.value = Number(value); }); }} /><span>{item?.unit} {c.operator === '<=' ? '이하' : c.operator === '>=' ? '이상' : '정확히'}</span></div> : <><p className="caption">이 조건에 맞는 후보를 골라주세요.</p><div className="condition-checks">{d.options.map(o => <label key={o.id}><input type="checkbox" disabled={!enabled} checked={c.satisfiedBy.includes(o.id)} onChange={e => update(x => { const current = x.conditions.find(v => v.id === c.id); if (current?.kind === 'required_tag') current.satisfiedBy = e.target.checked ? [...current.satisfiedBy, o.id] : current.satisfiedBy.filter(id => id !== o.id); })} />{o.name}</label>)}</div></>}<TextButton className="condition-remove" aria-label={`${c.kind === 'required_tag' ? c.label : item?.name} 조건 삭제`} onClick={() => { update(x => { x.conditions = x.conditions.filter(v => v.id !== c.id); }); setPausedConditions(v => v.filter(k => k.id !== c.id)); }}>삭제</TextButton></div>; })}</fieldset>
      <div className="condition-add"><TextButton disabled={locked || !d.criteria.some(c => c.type === 'numeric')} onClick={() => setThresholdOpen(true)}>+ 숫자 조건 추가</TextButton><TextButton disabled={locked} onClick={() => setTagOpen(true)}>+ 꼭 필요한 조건 추가</TextButton></div>
      <p className="caption centered" role="status">{!d.conditions.length ? '필수 조건이 없다면 모든 후보를 비교해요. 그냥 넘어가도 괜찮아요.' : eligible.length ? `입력한 조건으로는 ${withParticle(eligible.map(o => o.name).join(', '), '이', '가')} 비교에 남아요.` : '모든 후보가 조건에 맞지 않거나 정보가 부족해요. 조건과 입력값을 다시 확인해주세요.'}</p><details className="condition-preview"><summary>후보별 조건 확인</summary>{preview.map(o => <p key={o.id}>{o.name} <span className="muted">{o.reasons.length ? `· 제외: ${o.reasons.join(', ')}` : o.unknown.length ? `· ${o.unknown.join(', ')} 정보 필요` : '· 조건에 맞아요'}</span></p>)}<small>입력 기준의 미리보기예요. 질문을 시작할 때 저장한 조건을 다시 확인해요.</small></details></>}
    {errors.length > 0 && <ErrorNotice message={errors.slice(0, 4).join(' ')} />}
  </div><footer className="page-footer"><TextButton disabled={isSaving} onClick={() => step === 1 ? navigate('/') : void goStep(step - 1)}>{backLabels[step - 1]}</TextButton><div className="footer-actions">{step === 5 && !locked && <TextButton disabled={isSaving} onClick={() => void start(true)}>조건 없이 시작할게요</TextButton>}{step === 4 ? <small>선택한 항목을 차례로 입력하면 돼요.</small> : locked ? <Button onClick={() => navigate(`/decisions/${id}/result`)}>결과로 돌아가기</Button> : <Button disabled={isSaving} onClick={() => step === 5 ? void start() : void goStep(step + 1)}>{isSaving ? '저장 중…' : nextLabels[step - 1]}</Button>}</div></footer>
    {id !== 'new' && !locked && <div className="draft-save"><span className="caption">{isSaving ? '저장 중…' : dirty ? '아직 저장하지 않은 변경사항이 있어요.' : '저장한 내용이에요.'}</span><TextButton disabled={isSaving} onClick={async () => { const saved = await save(); if (saved) notify('변경사항을 저장했어요.'); }}>변경사항 저장</TextButton></div>}
    {editing && <CriterionEditor initial={editing === 'new' ? undefined : editing} existingNames={d.criteria.filter(c => editing === 'new' || c.id !== editing.id).map(c => c.name)} onSave={saveCriterion} onClose={() => setEditing(null)} />}
    {tagOpen && <TagCondition d={d} onClose={() => setTagOpen(false)} onSave={c => { update(x => { x.conditions.push(c); }); setTagOpen(false); }} />}{thresholdOpen && <ThresholdCondition d={d} onClose={() => setThresholdOpen(false)} onSave={c => { update(x => { x.conditions.push(c); }); setThresholdOpen(false); }} />}
  </main>;
}
function CellInput({ criterion: c, optionName, optionCount, value, disabled, onChange }: { criterion: Criterion; optionName: string; optionCount: number; value: CellValue; disabled: boolean; onChange: (v: CellValue) => void }) {
  const label = `${optionName} ${c.name}`;
  if (c.type === 'boolean') return <select aria-label={label} value={value === null ? '' : String(value)} disabled={disabled} onChange={e => onChange(e.target.value === '' ? null : e.target.value === 'true')}><option value="">선택해주세요</option><option value="true">예</option><option value="false">아니요</option></select>;
  if (c.type === 'rating' && c.inputMode === 'score') return <select aria-label={label} value={value === null ? '' : Number(value)} disabled={disabled} onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))}><option value="">선택해주세요</option>{c.levels.map((l, i) => <option key={i} value={i + 1}>{l}</option>)}</select>;
  return <input aria-label={label} type="number" step={c.inputMode === 'rank' ? 1 : 'any'} min={c.inputMode === 'rank' ? 1 : undefined} max={c.inputMode === 'rank' ? optionCount : undefined} disabled={disabled} value={value === null ? '' : Number(value)} placeholder={c.inputMode === 'rank' ? '순위' : '값 입력'} onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))} />;
}
function TagCondition({ d, onClose, onSave }: { d: Decision; onClose: () => void; onSave: (c: Condition) => void }) {
  const [label, setLabel] = useState(''), [satisfied, setSatisfied] = useState(d.options.map(o => o.id));
  return <Modal open onClose={onClose} title="반드시 필요한 조건을 정해요."><form className="form-stack" onSubmit={e => { e.preventDefault(); if (label.trim()) onSave({ id: uid(), kind: 'required_tag', label: label.trim(), satisfiedBy: satisfied }); }}><Field label="조건 이름" value={label} onValueChange={value => setLabel(value)} placeholder="예: Windows 지원, 기숙사 제공" maxLength={100} required autoFocus /><p className="muted">이 조건을 충족하는 선택지를 체크해주세요.</p>{d.options.map(o => <label key={o.id} className="check-row"><input type="checkbox" checked={satisfied.includes(o.id)} onChange={e => setSatisfied(v => e.target.checked ? [...v, o.id] : v.filter(id => id !== o.id))} />{o.name}</label>)}<div className="modal-actions"><Button type="button" secondary onClick={onClose}>취소</Button><Button type="submit">조건 추가</Button></div></form></Modal>;
}
function ThresholdCondition({ d, onClose, onSave }: { d: Decision; onClose: () => void; onSave: (c: Condition) => void }) {
  const [criterionId, setCriterion] = useState(d.criteria.find(c => c.type === 'numeric')?.id ?? ''), [operator, setOperator] = useState<'<=' | '>=' | '=='>('<='), [value, setValue] = useState('');
  return <Modal open onClose={onClose} title="넘지 않아야 하는 범위를 정해요."><form className="form-stack" onSubmit={e => { e.preventDefault(); if (value !== '' && Number.isFinite(Number(value))) onSave({ id: uid(), kind: 'threshold', criterionId, operator, value: Number(value) }); }}><label className="field">평가 기준<select value={criterionId} onChange={e => setCriterion(e.target.value)}>{d.criteria.filter(c => c.type === 'numeric').map(c => <option key={c.id} value={c.id}>{c.name} · {c.unit}</option>)}</select></label><label className="field">조건<select value={operator} onChange={e => setOperator(e.target.value as typeof operator)}><option value="<=">이하</option><option value=">=">이상</option><option value="==">같음</option></select></label><Field label="기준값" type="number" step="any" value={value} onValueChange={value => setValue(value)} required /><div className="modal-actions"><Button secondary type="button" onClick={onClose}>취소</Button><Button type="submit">조건 추가</Button></div></form></Modal>;
}
