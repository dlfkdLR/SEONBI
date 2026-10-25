import { useEffect, useRef, useState } from 'react';
import { notifyManager, QueryClient, useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Decision } from '../domain/types';
import { repository, rebaseDraft } from './repository';
import { getClientId } from '../api/clientId';
import { blankDecision } from './examples';
import { useUI } from '../lib/state';

notifyManager.setScheduler(queueMicrotask);
export const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: false, refetchOnWindowFocus: true }, mutations: { retry: false } } });
export const useOwner = () => getClientId();
export function useDecisions() {
  const owner = useOwner();
  return useInfiniteQuery({ queryKey: ['decisions', owner], refetchInterval: 30_000, initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => repository.list(pageParam), getNextPageParam: last => last.nextCursor ?? undefined });
}
export function useDecision(id: string) {
  const owner = useOwner(), cache = useQueryClient(), notify = useUI(s => s.notify);
  const [draft, setDraft] = useState<Decision>(), [isSaving, setSaving] = useState(false);
  const saving = useRef(false);
  const query = useQuery({ queryKey: ['decision', owner, id], queryFn: () => repository.get(id), enabled: id !== 'new' });
  const [blank] = useState(() => ({ ...blankDecision(owner), id: 'new' }));
  useEffect(() => { setDraft(undefined); }, [id]);
  const data = draft ?? query.data ?? (id === 'new' ? blank : undefined);
  const update = (updater: (d: Decision) => void): boolean => {
    if (!data || data.status === 'FINALIZED' || saving.current) return false;
    setDraft(previous => { const next = structuredClone(previous ?? data); updater(next); return next; });
    return true;
  };
  useEffect(() => {
    if (!draft) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [draft]);
  const save = async (): Promise<Decision | null> => {
    if (!data || saving.current) return null;
    saving.current = true; setSaving(true);
    try {
      const saved = await repository.save(query.data, data);
      cache.setQueryData(['decision', owner, saved.id], saved); setDraft(undefined);
      await cache.invalidateQueries({ queryKey: ['decisions', owner] });
      return saved;
    } catch (error) {
      notify(error instanceof Error ? error.message : '저장하지 못했어요.');
      // Reconcile partial multi-request saves with the actual server state.
      if (id !== 'new') { const fresh = await query.refetch(); if (fresh.data) setDraft(rebaseDraft(data, fresh.data)); }
      return null;
    } finally { saving.current = false; setSaving(false); }
  };
  return { ...query, data, isPending: id !== 'new' && query.isPending, update, save, isSaving, dirty: !!draft };
}
export function useAction() {
  const [busy, setBusy] = useState(false), [error,setError] = useState(''), lock = useRef(false), notify = useUI(s => s.notify), cache = useQueryClient();
  const owner = useOwner();
  async function run<T>(work: () => Promise<T>, success?: (value: T) => void) {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError('');
    try { const value = await work(); await cache.invalidateQueries({ predicate: q => q.queryKey.includes(owner) }); success?.(value); return value; }
    catch (e) { const message=e instanceof Error ? e.message : '요청을 처리하지 못했어요.'; setError(message); notify(message); }
    finally { lock.current = false; setBusy(false); }
  }
  return { busy, run, error };
}
