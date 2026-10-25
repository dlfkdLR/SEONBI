import type { Criterion, Decision } from '../domain/types';
import { defaultLevels, uid } from '../domain/types';

export function blankDecision(_ownerId?: string): Decision {
  const now = new Date().toISOString();
  return { id: uid(), title: '', description: '', category: 'etc', status: 'DRAFT',
    options: [{ id: uid(), name: '' }, { id: uid(), name: '' }], criteria: [], values: {}, conditions: [], createdAt: now, updatedAt: now };
}
export function criterion(name: string, type: Criterion['type'], direction: Criterion['direction'] = 'higher_better', unit = ''): Criterion {
  return { id: uid(), name, type, direction: type === 'boolean' ? 'true_better' : direction, unit, levels: [...defaultLevels], inputMode: 'score' };
}
