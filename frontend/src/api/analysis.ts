import { decisionPath, requestJson } from "./http";

export interface RankingItem { rank: number; optionId: string; name: string; score: number; topProbability: number }
export interface WeightItem { criterionId: string; name: string; mean: number; interval: [number, number]; statedRank: number | null; revealedRank: number | null }
export interface AnalysisResponse { provisional: boolean; recommendation?: { optionId: string; name: string; topProbability: number }; rankings: RankingItem[]; weights: WeightItem[]; stability: StabilityResponse; changeConditions: ChangeCondition[]; hiddenPreferences: Array<{ criterionA: string; criterionB: string; stated: string; revealed: string; strength: number; text: string }>; excludedOptions: Array<{ optionId: string; reasons: string[] }>; optionSummaries: Array<{ optionId: string; pros: string[]; cons: string[] }>; explanation: { summary: string; reasons: string[]; caveats: string[]; source: "ai" | "template" }; lowConfidence: boolean; computedAt: string }
export interface StabilityResponse { optionId: string; score: number; level: "HIGH" | "MEDIUM" | "LOW"; localPerturbationKeepRate: number; competitors: Array<{ optionId: string; probability: number }>; text: string }
export interface ChangeCondition { type: "weight" | "value"; criterionId?: string; optionId?: string; currentWeight?: number; threshold: number | null; direction?: "increase" | "decrease"; newTop?: string; text: string }
export interface ScoresResponse { criteria: string[]; items: Array<{ optionId: string; cells: Array<{ criterionId: string; rawValue: number | boolean; normalized: number; contribution: number }> }> }
export interface SensitivityResponse { items: Array<{ criterionId: string; currentWeight: number; points: Array<{ weight: number; scores: Record<string, number> }>; crossovers: Array<{ weight: number; from: string; to: string }> }> }
export interface SimulationRequest { weights: Array<{ criterionId: string; weight: number }> }
export interface SimulationResponse { normalizedWeights: SimulationRequest["weights"]; rankings: RankingItem[]; changedFromCurrent: boolean }
export function getAnalysis(decisionId: string) { return requestJson<AnalysisResponse>(`${decisionPath(decisionId)}/analysis`); }
export function getWeights(decisionId: string) { return requestJson<{ items: WeightItem[]; intervalLevel: number }>(`${decisionPath(decisionId)}/weights`); }
export function getRankings(decisionId: string) { return requestJson<{ items: RankingItem[] }>(`${decisionPath(decisionId)}/rankings`); }
export function getScores(decisionId: string) { return requestJson<ScoresResponse>(`${decisionPath(decisionId)}/scores`); }
export function getSensitivity(decisionId: string, criterionId?: string) { const query = criterionId ? `?criterionId=${encodeURIComponent(criterionId)}` : ""; return requestJson<SensitivityResponse>(`${decisionPath(decisionId)}/sensitivity${query}`); }
export function getStability(decisionId: string) { return requestJson<StabilityResponse>(`${decisionPath(decisionId)}/stability`); }
export function getChangeConditions(decisionId: string) { return requestJson<{ currentTop: string; items: ChangeCondition[] }>(`${decisionPath(decisionId)}/change-conditions`); }
export function simulateWeights(decisionId: string, input: SimulationRequest) { return requestJson<SimulationResponse>(`${decisionPath(decisionId)}/simulations`, { method: "POST", body: JSON.stringify(input) }); }
