import { useEffect, useRef, useState } from 'react';
import { notifyManager, QueryClient, useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Decision } from '../domain/types';
import { repository, rebaseDraft } from './repository';
import { getClientId } from '../api/clientId';
import { readNewDecisionDraft, retainNewDecisionDraft, clearNewDecisionDraft, submitKeyFor } from './new-decision-draft';
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
  const [blank] = useState(() => readNewDecisionDraft(owner));
  useEffect(() => { setDraft(undefined); }, [id]);
  const data = draft ?? query.data ?? (id === 'new' ? blank : undefined);
  const update = (updater: (d: Decision) => void): boolean => {
    if (!data || data.status === 'FINALIZED' || saving.current) return false;
    setDraft(previous => { const next = structuredClone(previous ?? data); updater(next); return next; });
    return true;
  };
  useEffect(() => {
    if (!draft) return;
    // A retained new form survives refresh; existing server edits still warn.
    if (id === 'new' && retainNewDecisionDraft(owner, draft)) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [draft, id, owner]);
  /** 서버에 이미 있는 결정의 수정을 저장한다. 새 결정(id가 new)은 complete()로만 서버에 만들어진다. */
  const save = async (override?: Decision): Promise<Decision | null> => {
    if (!data || !query.data || id === 'new' || saving.current) return null;
    saving.current = true; setSaving(true);
    try {
      const saved = await repository.save(query.data, override ?? data);
      cache.setQueryData(['decision', owner, saved.id], saved); setDraft(undefined);
      await cache.invalidateQueries({ queryKey: ['decisions', owner] });
      return saved;
    } catch (error) {
      notify(error instanceof Error ? error.message : '저장하지 못했어요.');
      // Reconcile partial multi-request saves with the actual server state.
      const fresh = await query.refetch(); if (fresh.data) setDraft(rebaseDraft(data, fresh.data));
      return null;
    } finally { saving.current = false; setSaving(false); }
  };
  /**
   * 초기 설정을 모두 마친 새 결정을 서버에 한 번에 저장하고 처음으로 결정 ID를 받는다. 성공했을 때만 브라우저의 임시 입력을 지우고,
   * 실패하면 입력을 그대로 둬 다시 시도할 수 있다. 같은 내용으로 다시 보내면 서버가 같은 결정을 돌려준다(멱등 키).
   */
  const complete = async (override?: Decision): Promise<Decision | null> => {
    const target = override ?? data;
    if (!target || id !== 'new' || saving.current) return null;
    saving.current = true; setSaving(true);
    try {
      const saved = await repository.complete(target, submitKeyFor(owner, target));
      clearNewDecisionDraft(owner);
      cache.setQueryData(['decision', owner, saved.id], saved);
      await cache.invalidateQueries({ queryKey: ['decisions', owner] });
      return saved;
    } catch (error) {
      notify(error instanceof Error ? error.message : '저장하지 못했어요.');
      return null;
    } finally { saving.current = false; setSaving(false); }
  };
  return { ...query, data, isPending: id !== 'new' && query.isPending, update, save, complete, isSaving, dirty: !!draft };
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
