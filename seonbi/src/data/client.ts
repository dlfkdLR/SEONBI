/** Browser-scoped identifier, matching the planning document's X-Client-Id contract.
 * This is an identifier for local records, not an authentication credential.
 */
export function getClientId(): string {
  let id = localStorage.getItem('seonbi.client-id');
  if (!id) { id = crypto.randomUUID(); localStorage.setItem('seonbi.client-id', id); }
  return id;
}
