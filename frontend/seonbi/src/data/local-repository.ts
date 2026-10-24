import type { Decision } from '../domain/types';
import { examples } from './examples';

const key = 'seonbi.decisions.v1';
function read(): Decision[] {
  try { const data: unknown = JSON.parse(localStorage.getItem(key) ?? '[]'); return Array.isArray(data) ? data.filter(d => d && typeof d.id === 'string' && Array.isArray(d.options) && Array.isArray(d.criteria)) as Decision[] : []; }
  catch { throw new Error('저장된 기록을 읽지 못했어요. 브라우저 저장소 설정을 확인해주세요.'); }
}
function write(items: Decision[]) {
  try { localStorage.setItem(key, JSON.stringify(items)); }
  catch { throw new Error('브라우저에 저장하지 못했어요. 저장 공간이나 개인정보 보호 설정을 확인해주세요.'); }
}
// Examples are only shown while this browser has no decisions of its own.
// Applying the same rule on reads also cleans up older three-example data.
function withExample(items: Decision[], ownerId: string): Decision[] {
  const owned = items.filter(d => d.ownerId === ownerId);
  const hasOwnDecision = owned.some(d => !d.example);
  const example = hasOwnDecision ? undefined
    : owned.find(d => d.example && d.title === '새 노트북 고르기')
      ?? owned.find(d => d.example) ?? examples(ownerId)[0];
  const next = items.filter(d => d.ownerId !== ownerId || !d.example || d === example);
  if (example && !next.includes(example)) next.push(example);
  return next;
}
export const repository = {
  list(ownerId: string): Decision[] {
    const items = read(), next = withExample(items, ownerId);
    if (items.length !== next.length || items.some((d, i) => d !== next[i])) write(next);
    return next.filter(d => d.ownerId === ownerId).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  },
  get(ownerId: string, id: string) { return this.list(ownerId).find(d => d.id === id) ?? null; },
  save(ownerId: string, decision: Decision) {
    if (decision.ownerId !== ownerId) throw new Error('이 브라우저에서 만든 결정이 아니에요.');
    const items = read(), index = items.findIndex(d => d.id === decision.id && d.ownerId === ownerId);
    const saved = { ...decision, updatedAt: new Date().toISOString() };
    if (index < 0) items.push(saved); else items[index] = saved;
    write(withExample(items, ownerId)); return saved;
  },
  remove(ownerId: string, id: string) { write(withExample(read().filter(d => !(d.id === id && d.ownerId === ownerId)), ownerId)); },
  removeOwner(ownerId: string) { write(read().filter(d => d.ownerId !== ownerId)); },
};
