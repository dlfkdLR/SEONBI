import type { Category, Condition, Criterion, Decision, Option } from '../domain/types';
import { categories } from '../domain/types';
import { blankDecision } from './examples';
import { useSyncExternalStore } from 'react';

export type SetupStep = 1 | 2 | 3 | 4 | 5;
/** 초기 설정 1~5단계에서 입력한 내용. 마지막 단계에서 서버에 한 번에 보내기 전까지는 브라우저에만 있다. */
interface NewDecisionDraft {
  version: 2;
  title: string;
  description: string;
  category: Category;
  options: Option[];
  criteria: Criterion[];
  values: Decision['values'];
  conditions: Condition[];
  step?: SetupStep;
  savedAt?: string;
}
const storageKey = (owner: string) => `seonbi.newDecisionDraft.v1.${owner}`;
// 같은 내용을 다시 보낼 때(더블 클릭·재시도) 서버가 한 결정으로 묶을 수 있게 하는 멱등 키. 내용이 바뀌면 새 키를 쓴다
const submitKeyStorage = (owner: string) => `seonbi.newDecisionSubmitKey.v1.${owner}`;

const isStep = (value: unknown): value is SetupStep => value === 1 || value === 2 || value === 3 || value === 4 || value === 5;
const isCriterion = (value: unknown): value is Criterion => {
  const c = value as Partial<Criterion> | null;
  return !!c && typeof c.id === 'string' && c.id.length > 0 && typeof c.name === 'string'
    && (c.type === 'numeric' || c.type === 'rating' || c.type === 'boolean') && Array.isArray(c.levels);
};
const isCondition = (value: unknown): value is Condition => {
  const c = value as Partial<Condition> | null;
  return !!c && typeof c.id === 'string' && (c.kind === 'threshold' || (c.kind === 'required_tag' && Array.isArray(c.satisfiedBy)));
};

function isDraft(value: unknown): value is NewDecisionDraft {
  if (!value || typeof value !== 'object') return false;
  const draft = value as Partial<Omit<NewDecisionDraft, 'version'>> & { version?: number };
  // 제목·후보만 있던 v1 임시 저장도 그대로 이어 쓸 수 있다(기준·값·조건은 비어 있다)
  if (draft.version === 1) { draft.criteria ??= []; draft.values ??= {}; draft.conditions ??= []; draft.version = 2; }
  return draft.version === 2 && typeof draft.title === 'string' && draft.title.length <= 100
    && typeof draft.description === 'string' && draft.description.length <= 1000
    && typeof draft.category === 'string' && Object.hasOwn(categories, draft.category)
    && Array.isArray(draft.options) && draft.options.length >= 2 && draft.options.length <= 8
    && draft.options.every(option => option && typeof option.id === 'string' && option.id.length > 0
      && typeof option.name === 'string' && option.name.length <= 60)
    && new Set(draft.options.map(option => option.id)).size === draft.options.length
    && Array.isArray(draft.criteria) && draft.criteria.length <= 7 && draft.criteria.every(isCriterion)
    && !!draft.values && typeof draft.values === 'object' && !Array.isArray(draft.values)
    && Array.isArray(draft.conditions) && draft.conditions.length <= 50 && draft.conditions.every(isCondition);
}
const toDraft = (decision: Decision): Omit<NewDecisionDraft, 'step' | 'savedAt'> => ({ version: 2, title: decision.title, description: decision.description,
  category: decision.category, options: decision.options, criteria: decision.criteria, values: decision.values, conditions: decision.conditions });

/** Only the unsaved setup form lives here. Server records remain authoritative. */
export function readNewDecisionDraft(owner: string): Decision {
  const empty = { ...blankDecision(owner), id: 'new' };
  try {
    const raw = sessionStorage.getItem(storageKey(owner)) ?? localStorage.getItem(storageKey(owner));
    const draft: unknown = raw ? JSON.parse(raw) : null;
    if (isDraft(draft)) return { ...empty, title: draft.title, description: draft.description, category: draft.category, options: draft.options,
      criteria: draft.criteria, values: draft.values, conditions: draft.conditions };
  } catch { /* Storage may be unavailable. Keep the in-memory form usable. */ }
  return empty;
}

export function retainNewDecisionDraft(owner: string, decision: Decision): boolean {
  if (decision.id !== 'new') return false;
  try {
    sessionStorage.setItem(storageKey(owner), JSON.stringify(toDraft(decision)));
    return true;
  } catch { return false; }
}

export function clearNewDecisionDraft(owner: string): void {
  try { sessionStorage.removeItem(storageKey(owner)); } catch { /* Best effort after a successful server save. */ }
  try { localStorage.removeItem(storageKey(owner)); } catch { /* Storage may be unavailable. */ }
  try { sessionStorage.removeItem(submitKeyStorage(owner)); } catch { /* Best effort. */ }
  window.dispatchEvent(new Event('seonbi:draft'));
}

/** Explicit early saves survive closing a tab; incomplete forms never reach the API. */
export function saveNewDecisionDraft(owner: string, decision: Decision, step: SetupStep): boolean {
  if (decision.id !== 'new') return false;
  const draft: NewDecisionDraft = { ...toDraft(decision), step, savedAt: new Date().toISOString() };
  try {
    localStorage.setItem(storageKey(owner), JSON.stringify(draft));
    retainNewDecisionDraft(owner, decision);
    window.dispatchEvent(new Event('seonbi:draft'));
    return true;
  } catch { return false; }
}

/**
 * 서버에 한 번에 저장할 때 쓰는 멱등 키. 같은 내용을 다시 보내면(응답을 못 받고 재시도, 더블 클릭) 같은 키가 나와 서버가 결정을 하나만 만들고,
 * 내용을 고친 뒤에는 새 키가 나온다. 새로고침해도 이어지도록 sessionStorage에 둔다.
 */
export function submitKeyFor(owner: string, decision: Decision): string {
  const fingerprint = JSON.stringify(toDraft(decision));
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(submitKeyStorage(owner)) ?? 'null');
    if (saved && typeof saved === 'object' && (saved as { fingerprint?: unknown }).fingerprint === fingerprint
      && typeof (saved as { key?: unknown }).key === 'string') return (saved as { key: string }).key;
  } catch { /* Fall through and make a new key. */ }
  const key = crypto.randomUUID();
  try { sessionStorage.setItem(submitKeyStorage(owner), JSON.stringify({ key, fingerprint })); } catch { /* A fresh key per attempt still works; it only loses retry de-duplication. */ }
  return key;
}

function subscribe(listener: () => void) {
  window.addEventListener('storage', listener);
  window.addEventListener('seonbi:draft', listener);
  return () => { window.removeEventListener('storage', listener); window.removeEventListener('seonbi:draft', listener); };
}

export function useSavedNewDecisionDraft(owner: string): NewDecisionDraft | null {
  const raw = useSyncExternalStore(subscribe, () => {
    try { return localStorage.getItem(storageKey(owner)); } catch { return null; }
  }, () => null);
  try {
    const value: unknown = raw ? JSON.parse(raw) : null;
    return isDraft(value) && typeof value.savedAt === 'string' && isStep(value.step) ? value : null;
  } catch { return null; }
}
