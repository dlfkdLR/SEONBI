/** Browser-scoped identifier. Falls back to memory when browser storage is blocked. */
export function createClientIdProvider(
  storage: () => Storage | undefined = () => globalThis.localStorage,
  makeId: () => string = () => crypto.randomUUID(),
): () => string {
  let memoryId: string | undefined;
  return () => {
    if (memoryId) return memoryId;
    try {
      const saved = storage()?.getItem('seonbi.client-id');
      if (saved) return (memoryId = saved);
    } catch { /* Private mode or browser policy can block storage access. */ }
    memoryId = makeId();
    try { storage()?.setItem('seonbi.client-id', memoryId); }
    catch { /* Keep the identifier for this page session. */ }
    return memoryId;
  };
}

export const getClientId = createClientIdProvider();
