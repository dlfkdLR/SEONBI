import { decisionPath, requestJson, requestVoid } from "./http";

export type DecisionStatus = "DRAFT" | "QUESTIONING" | "ANALYZED" | "FINALIZED";
export type DecisionCategory = "purchase" | "career" | "study" | "activity" | "living" | "etc";
export interface Option { id: string; name: string; description?: string; url?: string; excluded?: boolean; excludedReasons?: string[] }
export interface Criterion { id: string; name: string; type: "numeric" | "rating" | "boolean"; direction?: string; unit?: string; levels?: string[]; inputMode?: "score" | "rank"; scaleMin?: number; scaleMax?: number; scaleFrozen?: boolean; source?: string }
export interface Condition { id?: string; kind: "threshold" | "required_tag"; criterionId?: string; operator?: "<=" | ">=" | "=="; value?: number | boolean; label?: string; satisfiedBy?: string[]; source?: string }
export interface DecisionSummary { id: string; title: string; status: DecisionStatus; optionCount: number; finalChoice?: { optionId: string; name: string } | null; updatedAt: string }
export interface DecisionDetail { id: string; title: string; description?: string; category: DecisionCategory; status: DecisionStatus; options: Option[]; criteria: Criterion[]; values: Array<{ optionId: string; criterionId: string; value: number | boolean }>; rankings: Array<{ criterionId: string; order: string[][] }>; conditions: Condition[]; readiness: { canStartQuestions: boolean; missing: string[] }; createdAt: string; updatedAt: string }
/** 9번 응답: 기준·값·조건·readiness 없이 생성 직후의 결정 요약만 돌려준다. */
export interface CreatedDecision { id: string; title: string; description?: string | null; category: DecisionCategory; status: DecisionStatus; options: Option[]; createdAt: string; updatedAt: string }
export interface DecisionsResponse { items: DecisionSummary[]; nextCursor?: string | null }
export interface CreateDecisionRequest { title: string; description?: string; category: DecisionCategory; options?: Array<{ name: string; description?: string; url?: string }> }
export interface PatchDecisionRequest { title?: string; description?: string; category?: DecisionCategory }
export interface CriterionRequest { name: string; type: Criterion["type"]; direction?: string; unit?: string; levels?: string[]; inputMode?: Criterion["inputMode"]; scaleMin?: number; scaleMax?: number; source?: "user" | "ai_recommended" }
export interface ValueItem { optionId: string; criterionId: string; value: number | boolean }
export interface Ranking { criterionId: string; order: string[][] }
export interface ValuesResponse { items: ValueItem[]; rankings: Ranking[]; missing: Array<{ optionId: string; criterionId: string }> }
export interface ConditionsResponse { items: Condition[]; excludedOptions?: Array<{ optionId: string; reasons: string[] }>; warning?: "ALL_OPTIONS_EXCLUDED" }

export function listDecisions(params: { status?: DecisionStatus; cursor?: string; limit?: number } = {}) {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.cursor) query.set("cursor", params.cursor);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  const suffix = query.size ? `?${query.toString()}` : "";
  return requestJson<DecisionsResponse>(`/decisions${suffix}`);
}
export function getDecision(decisionId: string) { return requestJson<DecisionDetail>(decisionPath(decisionId)); }
export function createDecision(input: CreateDecisionRequest) { return requestJson<CreatedDecision>("/decisions", { method: "POST", body: JSON.stringify(input) }); }
export function updateDecision(decisionId: string, input: PatchDecisionRequest) { return requestJson<DecisionDetail>(decisionPath(decisionId), { method: "PATCH", body: JSON.stringify(input) }); }
export function deleteDecision(decisionId: string) { return requestVoid(decisionPath(decisionId), { method: "DELETE" }); }
export function duplicateDecision(decisionId: string, title?: string) {
  return requestJson<DecisionDetail>(`${decisionPath(decisionId)}/duplicate`, { method: "POST", ...(title ? { body: JSON.stringify({ title }) } : {}) });
}
export function listOptions(decisionId: string) { return requestJson<{ items: Option[] }>(`${decisionPath(decisionId)}/options`); }
export function addOption(decisionId: string, input: { name: string; description?: string; url?: string }) { return requestJson<Option>(`${decisionPath(decisionId)}/options`, { method: "POST", body: JSON.stringify(input) }); }
export function updateOption(decisionId: string, optionId: string, input: Partial<{ name: string; description: string; url: string }>) { return requestJson<Option>(`${decisionPath(decisionId)}/options/${encodeURIComponent(optionId)}`, { method: "PATCH", body: JSON.stringify(input) }); }
export function deleteOption(decisionId: string, optionId: string) { return requestVoid(`${decisionPath(decisionId)}/options/${encodeURIComponent(optionId)}`, { method: "DELETE" }); }
export function recommendOptions(decisionId: string, count: number) { return requestJson<{ items: Array<{ name: string; reason: string }>; notice?: string }>(`${decisionPath(decisionId)}/options/recommendations`, { method: "POST", body: JSON.stringify({ count }) }); }
export function listCriteria(decisionId: string) { return requestJson<{ items: Criterion[] }>(`${decisionPath(decisionId)}/criteria`); }
export function addCriterion(decisionId: string, input: CriterionRequest) { return requestJson<Criterion>(`${decisionPath(decisionId)}/criteria`, { method: "POST", body: JSON.stringify(input) }); }
export function updateCriterion(decisionId: string, criterionId: string, input: Partial<CriterionRequest>) { return requestJson<Criterion>(`${decisionPath(decisionId)}/criteria/${encodeURIComponent(criterionId)}`, { method: "PATCH", body: JSON.stringify(input) }); }
export function deleteCriterion(decisionId: string, criterionId: string) { return requestJson<{ invalidatedAnswers: number }>(`${decisionPath(decisionId)}/criteria/${encodeURIComponent(criterionId)}`, { method: "DELETE" }); }
export function recommendCriteria(decisionId: string) { return requestJson<{ items: Array<Omit<CriterionRequest, "source"> & { reason: string }>; source: "ai" | "template" }>(`${decisionPath(decisionId)}/criteria/recommendations`, { method: "POST" }); }
export function getValues(decisionId: string) { return requestJson<ValuesResponse>(`${decisionPath(decisionId)}/values`); }
export function saveValues(decisionId: string, input: { items?: ValueItem[]; rankings?: Ranking[] }) { return requestJson<ValuesResponse>(`${decisionPath(decisionId)}/values`, { method: "PUT", body: JSON.stringify(input) }); }
export function getConditions(decisionId: string) { return requestJson<ConditionsResponse>(`${decisionPath(decisionId)}/conditions`); }
export function replaceConditions(decisionId: string, items: Condition[]) { return requestJson<ConditionsResponse>(`${decisionPath(decisionId)}/conditions`, { method: "PUT", body: JSON.stringify({ items }) }); }
