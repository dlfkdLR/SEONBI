import { notifyManager, QueryClient, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Decision } from '../domain/types';
import { repository } from './local-repository';
import { getClientId } from './client';
import { useUI } from '../lib/state';

// Local writes are synchronous; notify before the next task so controlled checkboxes
// don't briefly restore an obsolete value while waiting for a timer-based notification.
notifyManager.setScheduler(queueMicrotask);
export const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
export const useOwner = () => getClientId();
export function useDecisions() {
  const owner = useOwner();
  return useQuery({ queryKey: ['decisions', owner], queryFn: () => repository.list(owner) });
}
export function useDecision(id: string) {
  const owner = useOwner(), cache = useQueryClient(), notify = useUI(s => s.notify);
  const query = useQuery({ queryKey: ['decision', owner, id], queryFn: () => repository.get(owner, id) });
  const update = (updater: (d: Decision) => void, allowFinal = false): boolean => {
    const current = cache.getQueryData<Decision>(['decision', owner, id]);
    if (!current) return false;
    if (current.status === 'FINALIZED' && !allowFinal) { notify('확정한 결정은 수정할 수 없어요. 복제해서 새로 시작해주세요.'); return false; }
    try {
      const draft = structuredClone(current); updater(draft);
      const saved = repository.save(owner, draft);
      cache.setQueryData(['decision', owner, id], saved); void cache.invalidateQueries({ queryKey: ['decisions', owner] });
      return true;
    } catch (e) { notify(e instanceof Error ? e.message : '저장하지 못했어요.'); return false; }
  };
  return { ...query, update };
}
