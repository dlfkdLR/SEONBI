import { useState } from 'react';
import { Link } from 'react-router';
import { PageHeading, Button, TextButton } from '../components/ui';
import { CriterionCard } from '../components/criterion-card';
import { useNewDecision } from '../components/layout';
import { previewConditions } from '../domain/conditions';
import type { Criterion, Decision } from '../domain/types';
const criteria: Criterion[] = [
  { id: 'price', name: '가격', type: 'numeric', direction: 'lower_better', unit: '만 원', levels: [], inputMode: 'score' },
  { id: 'performance', name: '성능', type: 'rating', direction: 'higher_better', unit: '', levels: ['매우 아쉬움', '아쉬움', '보통', '좋음', '매우 좋음'], inputMode: 'score' },
  { id: 'weight', name: '무게', type: 'numeric', direction: 'lower_better', unit: 'kg', levels: [], inputMode: 'score' },
  { id: 'battery', name: '배터리', type: 'numeric', direction: 'higher_better', unit: '시간', levels: [], inputMode: 'score' },
];
export default function ExamplePage() {
  const create = useNewDecision(), [step, setStep] = useState(0), [selected, setSelected] = useState(criteria.map(c => c.id));
  const [windows, setWindows] = useState(true), [budget, setBudget] = useState('180');
  const options = [{ id: 'galaxy', name: '갤럭시북5 프로' }, { id: 'gram', name: 'LG 그램 16' }, { id: 'mac', name: '맥북 에어 M4' }];
  const values = { galaxy: { price: 170, weight: 1.23 }, gram: { price: 150, weight: 1.19 }, mac: { price: 160, weight: 1.24 } };
  const conditions: Decision['conditions'] = [...(budget !== '' && Number.isFinite(Number(budget)) ? [{ id: 'budget', kind: 'threshold' as const, criterionId: 'price', operator: '<=' as const, value: Number(budget) }] : []), ...(windows ? [{ id: 'windows', kind: 'required_tag' as const, label: 'Windows 지원', satisfiedBy: ['galaxy', 'gram'] }] : [])];
  const eligible = previewConditions({ options, criteria, values, conditions }).filter(o => o.eligible);
  return <main className="page example-page" id="main"><div className="page-content"><p className="eyebrow stage">새 노트북 고르기 · 입력 예시</p><PageHeading title={step === 0 ? '어떤 점을 비교할까요?' : step === 1 ? '정보는 하나씩, 편하게.' : '꼭 지켜야 하는\n조건이 있나요?'} description={step === 0 ? '비교 기준은 후보들의 장단점을 살펴볼 항목이에요.\n가격, 성능, 무게처럼 비교하고 싶은 항목을 골라주세요.' : step === 1 ? '모든 후보에 같은 항목과 단위를 사용해요.' : '반드시 충족해야 하는 조건이에요. 맞지 않는 후보는 추천에서 빠져요.'} />
    {step === 0 && <><div className="criteria-grid">{criteria.map(c => <CriterionCard key={c.id} criterion={c} selected={selected.includes(c.id)} onToggle={() => setSelected(v => v.includes(c.id) ? v.filter(id => id !== c.id) : [...v, c.id])} />)}</div><p className="caption centered">항목을 눌러 켜고 꺼보세요. 내 결정에서는 항목을 직접 추가할 수도 있어요.</p></>}
    {step === 1 && <section className="surface example-values"><h2>가격은 얼마인가요?</h2><p className="muted">저렴할수록 좋게 비교해요.</p>{options.map(o => <div className="value-row" key={o.id}><strong>{o.name}</strong><span className="example-input">{values[o.id as keyof typeof values].price}</span><span className="caption">만 원</span></div>)}</section>}
    {step === 2 && <><div className="surface condition-explanation"><div><p className="eyebrow">비교 기준</p><h2>가벼울수록 좋아요.</h2><p className="caption">장단점을 비교할 때 사용해요.</p></div><div><p className="eyebrow">필수 조건</p><h2>1.3kg 이하여야 해요.</h2><p className="caption">1.3kg을 넘으면 추천하지 않아요.</p></div></div><div className="conditions-grid"><div className="surface condition-card"><h2>예산은 이 금액 안에서</h2><div className="threshold-input"><input aria-label="예시 예산" type="number" value={budget} onChange={e => setBudget(e.target.value)} /><span>만 원 이하</span></div></div><button className={`selection-card ${windows ? 'selected' : ''}`} aria-pressed={windows} onClick={() => setWindows(v => !v)}><span className="selection-mark" aria-hidden="true">{windows ? '✓' : ''}</span><strong>Windows가 꼭 필요해요</strong><span className="card-description">맞지 않는 제품은 추천에서 빼요.</span><small className="card-detail">{windows ? '맥북 에어 M4는 비교에서 제외돼요.' : '운영체제와 관계없이 비교해요.'}</small></button></div><p className="caption centered" role="status">{eligible.length ? `현재 조건에 맞는 후보는 ${eligible.map(o => o.name).join(', ')}이에요.` : '조건에 맞는 후보가 없어요. 예산을 바꿔보세요.'}</p></>}
    <p className="caption example-disclaimer">한 개의 노트북 비교 예시예요. 제품 정보와 수치는 시연용이며 내 결정으로 저장되지 않아요.</p></div><footer className="page-footer">{step ? <TextButton onClick={() => setStep(v => v - 1)}>‹ 이전 예시</TextButton> : <Link className="text-button" to="/">‹ 처음으로</Link>}{step < 2 ? <Button onClick={() => setStep(v => v + 1)}>{step === 0 ? '정보 입력 예시 보기' : '필수 조건 예시 보기'}</Button> : <Button onClick={create}>내 결정 시작하기</Button>}</footer></main>;
}
