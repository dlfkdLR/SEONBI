import { decisionPath, requestJson } from "./http";

// 기획서 7.3 "질문·답변" 응답 형식. 서버가 보내지 않는 DB 컬럼(spec, display)은 타입에 넣지 않는다.
export type QuestionType = "slider_pair" | "ab_criteria" | "ab_scenario" | "numeric" | "free_text";
export type StopReason = "CONFIDENT" | "LOW_INFO_GAIN" | "MAX_QUESTIONS" | "USER_STOPPED";

/** slider_pair: s > 0이면 왼쪽(left)이 더 중요하다. */
export interface SliderPairPayload {
  left: { criterionId: string; label: string; range: string };
  right: { criterionId: string; label: string; range: string };
  min: number;
  max: number;
}
export interface AbCriteriaPayload {
  optionA: { criterionId: string; label: string };
  optionB: { criterionId: string; label: string };
}
export interface ScenarioOption { label: string; description: string; deltas: Array<{ criterionId: string; display: string }> }
export interface AbScenarioPayload { optionA: ScenarioOption; optionB: ScenarioOption }

interface QuestionBase {
  id: string;
  seq: number;
  criteria: string[];
  title: string;
  allowFreeText: boolean;
  allowSkip: boolean;
}
/** 질문 객체. type으로 payload 모양이 정해진다(numeric·free_text는 명세에 payload 형식이 없다). */
export type Question =
  | (QuestionBase & { type: "slider_pair"; payload: SliderPairPayload })
  | (QuestionBase & { type: "ab_criteria"; payload: AbCriteriaPayload })
  | (QuestionBase & { type: "ab_scenario"; payload: AbScenarioPayload })
  | (QuestionBase & { type: "numeric" | "free_text"; payload: Record<string, unknown> });

/** 답변 값: choice | value(-5~5) | skip 중 정확히 하나. */
export type AnswerValue = { choice: "A" | "B" } | { value: number } | { skip: true };

export interface QuestionProgress { answered: number; expectedRange: [number, number]; confidence?: number }
export type NextQuestion = { done: false; question: Question } | { done: true; reason: StopReason };
export interface StartQuestionsResponse { status: "QUESTIONING"; round: "initial" | "extra"; question: Question; progress: { answered: number; expectedRange: [number, number] } }
export interface QuestionStatus { shouldStop: boolean; reason: StopReason | null; metrics: { answered: number; topOption: string; topProbability: number; decisionEntropy: number; bestNextInfoGain: number }; remainingUncertainty: Array<{ criterionId: string; interval: [number, number] }> }
/** 36번 목록의 질문은 질문 객체에 origin이 붙는다. */
export type AnswerQuestion = Question & { origin: "engine" | "free_text" };
export interface Answer { id: string; questionId: string; question: AnswerQuestion; response: AnswerValue; isValid: boolean; createdAt: string }
/** 37번 응답의 answer: 36번 항목에서 question을 뺀 필드. */
export type AnswerObject = Omit<Answer, "question">;
export interface Estimate { weights: Array<{ criterionId: string; mean: number; interval: [number, number] }>; topProbability: number }
export interface SubmitAnswerResponse { answer: AnswerObject; estimate: Estimate; next: NextQuestion }
export function startQuestions(decisionId: string) { return requestJson<StartQuestionsResponse>(`${decisionPath(decisionId)}/questions/start`, { method: "POST" }); }
export function getNextQuestion(decisionId: string) { return requestJson<NextQuestion>(`${decisionPath(decisionId)}/questions/next`); }
export function getQuestionStatus(decisionId: string) { return requestJson<QuestionStatus>(`${decisionPath(decisionId)}/questions/status`); }
export function getQuestionProgress(decisionId: string) { return requestJson<QuestionProgress>(`${decisionPath(decisionId)}/questions/progress`); }
export function stopQuestions(decisionId: string) { return requestJson<{ reason: "USER_STOPPED"; lowConfidence: boolean }>(`${decisionPath(decisionId)}/questions/stop`, { method: "POST" }); }
export function listAnswers(decisionId: string) { return requestJson<{ items: Answer[] }>(`${decisionPath(decisionId)}/answers`); }
export function submitAnswer(decisionId: string, input: { questionId: string; response: AnswerValue }) { return requestJson<SubmitAnswerResponse>(`${decisionPath(decisionId)}/answers`, { method: "POST", body: JSON.stringify(input) }); }
export function editAnswer(decisionId: string, answerId: string, response: AnswerValue) { return requestJson<{ estimate: Estimate }>(`${decisionPath(decisionId)}/answers/${encodeURIComponent(answerId)}`, { method: "PATCH", body: JSON.stringify({ response }) }); }
