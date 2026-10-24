import { useState } from 'react';
import type { Criterion } from '../domain/types';
import { criterion } from '../data/examples';
import { Button, ErrorNotice, Field, Modal } from './ui';

export function CriterionEditor({ initial, existingNames, onSave, onClose }: { initial?: Criterion; existingNames: string[]; onSave: (c: Criterion) => void; onClose: () => void }) {
  const [draft, setDraft] = useState<Criterion>(() => initial ? structuredClone(initial) : criterion('', 'numeric'));
  const [labels, setLabels] = useState(draft.levels.join('\n')), [error, setError] = useState('');
  const patch = (values: Partial<Criterion>) => setDraft(d => ({ ...d, ...values }));
  return <Modal open onClose={onClose} title={initial ? '평가 기준을 수정해요.' : '비교할 기준을 추가해요.'} wide>
    <form className="form-stack" onSubmit={e => {
      e.preventDefault();
      const levels = labels.split('\n').map(l => l.trim()).filter(Boolean), name = draft.name.trim();
      if (!name) { setError('기준 이름을 입력해주세요.'); return; }
      if (existingNames.includes(name)) { setError('같은 이름의 기준이 이미 있어요.'); return; }
      if (draft.type === 'rating' && draft.inputMode === 'score' && (levels.length < 3 || levels.length > 7 || new Set(levels).size !== levels.length)) { setError('서로 다른 단계 라벨을 3~7개 입력해주세요.'); return; }
      if ((draft.scaleMin === undefined) !== (draft.scaleMax === undefined) || (draft.scaleMin !== undefined && draft.scaleMax !== undefined && draft.scaleMin >= draft.scaleMax)) { setError('척도 범위의 최솟값과 최댓값을 모두 입력하고, 최댓값을 더 크게 정해주세요.'); return; }
      onSave({ ...draft, name, levels, unit: draft.type === 'numeric' ? draft.unit : '' });
    }}>
      <Field label="기준 이름" placeholder="예: 통학 시간, 적성, 비용" value={draft.name} maxLength={40} onValueChange={value => patch({ name: value })} autoFocus required />
      <label className="field">입력 방식<select value={draft.type === 'rating' && draft.inputMode === 'rank' ? 'rank' : draft.type} onChange={e => { const type = e.target.value; patch({ type: type === 'rank' ? 'rating' : type as Criterion['type'], inputMode: type === 'rank' ? 'rank' : 'score', scaleMin: undefined, scaleMax: undefined }); }}>
        <option value="numeric">숫자로 비교</option><option value="rating">단계로 비교</option><option value="rank">좋은 순서로 비교 · 동순위 가능</option><option value="boolean">예 / 아니요로 비교</option>
      </select></label>
      {draft.inputMode !== 'rank' && draft.type !== 'boolean' && <label className="field">좋은 방향<select value={draft.direction} onChange={e => patch({ direction: e.target.value as Criterion['direction'] })}><option value="higher_better">높을수록 좋아요</option><option value="lower_better">낮을수록 좋아요</option></select></label>}
      {draft.type === 'numeric' && <><Field label="단위" placeholder="예: 만원, 분, kg" value={draft.unit} maxLength={20} onValueChange={value => patch({ unit: value })} /><details className="advanced"><summary>비교 범위 직접 정하기</summary><p className="muted caption">비워두면 질문 시작 시 선택지의 최솟값과 최댓값을 사용해요. 범위를 바꾸면 관련 상황형 답변을 다시 받아야 해요.</p><div className="two-fields"><Field label="최솟값" type="number" step="any" value={draft.scaleMin ?? ''} onValueChange={value => patch({ scaleMin: value === '' ? undefined : Number(value) })} /><Field label="최댓값" type="number" step="any" value={draft.scaleMax ?? ''} onValueChange={value => patch({ scaleMax: value === '' ? undefined : Number(value) })} /></div></details></>}
      {draft.type === 'rating' && draft.inputMode === 'score' && <label className="field">단계 이름 · 낮은 단계부터 한 줄에 하나<textarea rows={5} value={labels} onChange={e => setLabels(e.target.value)} /><small>3~7단계. 입력한 순서대로 비교해요.</small></label>}
      {draft.inputMode === 'rank' && <p className="muted caption">1위가 가장 좋아요. 같은 순위도 입력할 수 있어요. 순위 사이의 간격은 같은 것으로 간주해요.</p>}
      {error && <ErrorNotice message={error} />}<div className="modal-actions"><Button type="button" secondary onClick={onClose}>취소</Button><Button type="submit">기준 저장</Button></div>
    </form>
  </Modal>;
}
