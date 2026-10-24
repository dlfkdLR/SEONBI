const STORAGE_KEY = "seonbi.clientId";

/** Returns the browser UUID used by the X-Client-Id API contract. */
export function getClientId(): string {
  let id = localStorage.getItem(STORAGE_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(STORAGE_KEY, id);
  }
  return id;
}
