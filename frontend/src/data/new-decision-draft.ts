import type { Category, Decision, Option } from '../domain/types';
import { categories } from '../domain/types';
import { blankDecision } from './examples';

interface NewDecisionDraft {
  version: 1;
  title: string;
  description: string;
  category: Category;
  options: Option[];
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
    const raw = sessionStorage.getItem(storageKey(owner));
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
}
