import type { components } from './schema';

// B's generated OpenAPI currently covers configuration only and exposes string
// enums/optional fields. Narrow those fields with the fixed v1 planning contract.
export type Schema<K extends keyof components['schemas']> = components['schemas'][K];
export type Category = 'purchase' | 'career' | 'study' | 'activity' | 'living' | 'etc';
export type Status = 'DRAFT' | 'QUESTIONING' | 'ANALYZED' | 'FINALIZED';
export type Direction = 'higher_better' | 'lower_better' | 'true_better';
export type CriterionType = 'numeric' | 'rating' | 'boolean';
export type OptionDto = Required<Pick<Schema<'com.seonbi.dto.Responses.Option'>, 'id' | 'name' | 'excluded' | 'excludedReasons'>>;
export type CriterionDto = Omit<Schema<'com.seonbi.dto.Responses.Criterion'>, 'type' | 'direction' | 'inputMode' | 'source'> & {
  id: string; name: string; type: CriterionType; direction: Direction; inputMode?: 'score' | 'rank'; source?: 'user' | 'ai_recommended' | 'free_text';
};
export type CreateDecision = Schema<'com.seonbi.dto.Requests.CreateDecision'>;
export type ValueItem = { optionId: string; criterionId: string; value: number | boolean };
export type RankingInput = { criterionId: string; order: string[][] };
export type ValuesRequest = Omit<Schema<'com.seonbi.dto.Requests.Values'>, 'items' | 'rankings'> & { items: (ValueItem | { optionId: string; criterionId: string; value: null })[]; rankings: RankingInput[] };
export type DecisionDto = Omit<Schema<'com.seonbi.dto.Responses.Decision'>, 'options' | 'criteria' | 'values' | 'rankings' | 'status' | 'category'> & {
  id: string; title: string; status: Status; category: Category;
  options: OptionDto[]; criteria: CriterionDto[]; values: ValueItem[]; rankings: RankingInput[];
  readiness: { canStartQuestions: boolean; missing: string[] }; createdAt: string; updatedAt: string;
};
export interface DecisionItem { id: string; title: string; status: Status; optionCount: number; updatedAt: string; finalChoice?: { optionId: string; name: string } | null }
export interface DecisionList { items: DecisionItem[]; nextCursor: string | null }
// Question/analysis operations are not present in B's generated OpenAPI yet.
// These are explicit v0.5 §7.3 interfaces, not generated server guarantees.
export type AnswerResponse = { choice?: 'A' | 'B'; value?: number; skip?: boolean };
export interface QuestionDto {
  id: string; seq: number; type: 'ab_criteria' | 'ab_scenario' | 'slider_pair' | 'numeric' | 'free_text';
  title: string; criteria: string[]; allowFreeText: boolean; allowSkip: boolean; origin?: 'engine' | 'free_text';
  payload: {
    optionA?: { criterionId?: string; label: string; description?: string; deltas?: { criterionId: string; display: string }[] };
    optionB?: { criterionId?: string; label: string; description?: string; deltas?: { criterionId: string; display: string }[] };
    left?: { criterionId: string; label: string; range?: string }; right?: { criterionId: string; label: string; range?: string };
    min?: number; max?: number; step?: number; unit?: string; placeholder?: string;
  };
}
export interface AnswerDto { id: string; questionId: string; question: QuestionDto; response: AnswerResponse; isValid: boolean; createdAt: string }
export type NextQuestion = { done: true; reason: string } | { done: false; question: QuestionDto };
export interface WeightDto { criterionId: string; name: string; mean: number; interval: [number, number]; statedRank?: number | null; revealedRank?: number | null }
export interface RankDto { rank: number; optionId: string; name?: string; score: number; topProbability?: number }
export interface AnalysisDto {
  provisional: boolean; recommendation: { optionId: string; name: string; topProbability: number };
  rankings: RankDto[]; weights: WeightDto[];
  stability: { level: 'HIGH' | 'MEDIUM' | 'LOW'; score: number; localPerturbationKeepRate: number; text: string };
  changeConditions: { type: 'weight' | 'value'; criterionId: string; threshold: number | null; text: string }[];
  hiddenPreferences: { text: string }[]; excludedOptions: { optionId: string; reasons: string[] }[];
  explanation: { summary: string; reasons: string[]; caveats: string[]; source: 'ai' | 'template' };
  lowConfidence: boolean; computedAt: string;
}
export interface SimulationDto { normalizedWeights: { criterionId: string; weight: number }[]; rankings: RankDto[]; changedFromCurrent: boolean }
export interface SensitivityDto { items: { criterionId: string; currentWeight: number; points: { weight: number; scores: Record<string, number> }[]; crossovers: { weight: number; from: string; to: string }[] }[] }
