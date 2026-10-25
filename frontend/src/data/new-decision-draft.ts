import type { Category, Decision, Option } from '../domain/types';
import { categories } from '../domain/types';
import { blankDecision } from './examples';
import { useSyncExternalStore } from 'react';

interface NewDecisionDraft {
  version: 1;
  title: string;
  description: string;
  category: Category;
  options: Option[];
  step?: 1 | 2;
  savedAt?: string;
}
const storageKey = (owner: string) => `seonbi.newDecisionDraft.v1.${owner}`;

function isDraft(value: unknown): value is NewDecisionDraft {
  if (!value || typeof value !== 'object') return false;
  const draft = value as Partial<NewDecisionDraft>;
  return draft.version === 1 && typeof draft.title === 'string' && draft.title.length <= 100
    && typeof draft.description === 'string' && draft.description.length <= 1000
    && typeof draft.category === 'string' && Object.hasOwn(categories, draft.category)
    && Array.isArray(draft.options) && draft.options.length >= 2 && draft.options.length <= 8
    && draft.options.every(option => option && typeof option.id === 'string' && option.id.length > 0
      && typeof option.name === 'string' && option.name.length <= 60)
    && new Set(draft.options.map(option => option.id)).size === draft.options.length;
}

/** Only the unsaved title/candidate form lives here. Server records remain authoritative. */
export function readNewDecisionDraft(owner: string): Decision {
  const empty = { ...blankDecision(owner), id: 'new' };
  try {
    const raw = sessionStorage.getItem(storageKey(owner)) ?? localStorage.getItem(storageKey(owner));
    const draft: unknown = raw ? JSON.parse(raw) : null;
    if (isDraft(draft)) return { ...empty, title: draft.title, description: draft.description, category: draft.category, options: draft.options };
  } catch { /* Storage may be unavailable. Keep the in-memory form usable. */ }
  return empty;
}

export function retainNewDecisionDraft(owner: string, decision: Decision): boolean {
  if (decision.id !== 'new') return false;
  const draft: NewDecisionDraft = { version: 1, title: decision.title, description: decision.description, category: decision.category, options: decision.options };
  try {
    sessionStorage.setItem(storageKey(owner), JSON.stringify(draft));
    return true;
  } catch { return false; }
}

export function clearNewDecisionDraft(owner: string): void {
  try { sessionStorage.removeItem(storageKey(owner)); } catch { /* Best effort after a successful server save. */ }
  try { localStorage.removeItem(storageKey(owner)); } catch { /* Storage may be unavailable. */ }
  window.dispatchEvent(new Event('seonbi:draft'));
}

/** Explicit early saves survive closing a tab; incomplete forms never reach the API. */
export function saveNewDecisionDraft(owner: string, decision: Decision, step: 1 | 2): boolean {
  if (decision.id !== 'new') return false;
  const draft: NewDecisionDraft = { version: 1, title: decision.title, description: decision.description, category: decision.category, options: decision.options, step, savedAt: new Date().toISOString() };
  try {
    localStorage.setItem(storageKey(owner), JSON.stringify(draft));
    retainNewDecisionDraft(owner, decision);
    window.dispatchEvent(new Event('seonbi:draft'));
    return true;
  } catch { return false; }
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
    return isDraft(value) && typeof value.savedAt === 'string' && (value.step === 1 || value.step === 2) ? value : null;
  } catch { return null; }
}
