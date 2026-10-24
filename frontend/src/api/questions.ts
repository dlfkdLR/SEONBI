import { decisionPath, requestJson } from "./http";

export interface Question { id: string; type: string; origin: "engine" | "free_text"; spec: Record<string, unknown>; display: Record<string, unknown> }
export interface QuestionProgress { answered: number; expectedRange: [number, number]; confidence?: number }
export interface NextQuestion { done: boolean; question?: Question; reason?: string }
export interface StartQuestionsResponse { status: "QUESTIONING"; round: "initial" | "extra"; question?: Question; progress: QuestionProgress }
export interface QuestionStatus { shouldStop: boolean; reason: string | null; metrics: { answered: number; topOption?: string; topProbability?: number; decisionEntropy?: number; bestNextInfoGain?: number }; remainingUncertainty: Array<{ criterionId: string; interval: [number, number] }> }
export interface Answer { id: string; questionId: string; question: Question; response: Record<string, unknown>; isValid: boolean; createdAt: string }
export interface Estimate { weights: Array<{ criterionId: string; mean: number; interval: [number, number] }>; topProbability: number }
export interface SubmitAnswerResponse { answer: Answer; estimate: Estimate; next: NextQuestion }
export function startQuestions(decisionId: string) { return requestJson<StartQuestionsResponse>(`${decisionPath(decisionId)}/questions/start`, { method: "POST" }); }
export function getNextQuestion(decisionId: string) { return requestJson<NextQuestion>(`${decisionPath(decisionId)}/questions/next`); }
export function getQuestionStatus(decisionId: string) { return requestJson<QuestionStatus>(`${decisionPath(decisionId)}/questions/status`); }
export function getQuestionProgress(decisionId: string) { return requestJson<QuestionProgress>(`${decisionPath(decisionId)}/questions/progress`); }
export function stopQuestions(decisionId: string) { return requestJson<{ reason: "USER_STOPPED"; lowConfidence: boolean }>(`${decisionPath(decisionId)}/questions/stop`, { method: "POST" }); }
export function listAnswers(decisionId: string) { return requestJson<{ items: Answer[] }>(`${decisionPath(decisionId)}/answers`); }
export function submitAnswer(decisionId: string, input: { questionId: string; response: Record<string, unknown> }) { return requestJson<SubmitAnswerResponse>(`${decisionPath(decisionId)}/answers`, { method: "POST", body: JSON.stringify(input) }); }
export function editAnswer(decisionId: string, answerId: string, response: Record<string, unknown>) { return requestJson<{ estimate: Estimate }>(`${decisionPath(decisionId)}/answers/${encodeURIComponent(answerId)}`, { method: "PATCH", body: JSON.stringify({ response }) }); }
