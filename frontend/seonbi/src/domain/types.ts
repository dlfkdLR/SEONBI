export type Status = 'DRAFT' | 'QUESTIONING' | 'ANALYZED' | 'FINALIZED';
export type Category = 'purchase' | 'career' | 'study' | 'activity' | 'living' | 'etc';
export type CellValue = number | boolean | null;
export interface Option { id: string; name: string }
export interface Criterion {
  id: string;
  name: string;
  type: 'numeric' | 'rating' | 'boolean';
  direction: 'higher_better' | 'lower_better';
  unit: string;
  levels: string[];
  inputMode: 'score' | 'rank';
  scaleMin?: number;
  scaleMax?: number;
}
export type Condition =
  | { id: string; kind: 'threshold'; criterionId: string; operator: '<=' | '>=' | '=='; value: number }
  | { id: string; kind: 'required_tag'; label: string; satisfiedBy: string[] };
export interface Question {
  id: string;
  type: 'ab_criteria' | 'slider_pair' | 'ab_scenario';
  criteria: [string, string];
  delta?: [number, number];
  scenario?: { a: [number, number]; b: [number, number] };
}
export interface Answer {
  id: string;
  question: Question;
  response: { choice?: 'A' | 'B'; value?: number; skip?: boolean };
  isValid: boolean;
}
export interface Decision {
  id: string;
  ownerId: string;
  title: string;
  description: string;
  category: Category;
  status: Status;
  step: number;
  options: Option[];
  criteria: Criterion[];
  values: Record<string, Record<string, CellValue>>;
  conditions: Condition[];
  answers: Answer[];
  pendingQuestion?: Question;
  questionLimit?: number;
  seed: number;
  finalized?: { optionId: string; memo: string; at: string };
  createdAt: string;
  updatedAt: string;
  example?: boolean;
}
export interface Weight { criterionId: string; mean: number; interval: [number, number] }
export interface Ranking { optionId: string; name: string; score: number; probability: number; rank: number }
export interface Exclusion { optionId: string; reasons: string[] }
export interface Crossover { criterionId: string; threshold: number; newTop: string; direction: 'increase' | 'decrease' }
export interface Analysis {
  weights: Weight[];
  rankings: Ranking[];
  excluded: Exclusion[];
  stability: '높음' | '보통' | '낮음';
  keepRate: number;
  changes: Crossover[];
  entropy: number;
  ess: number;
}
export const statusLabels: Record<Status, string> = { DRAFT: '작성 중', QUESTIONING: '질문 중', ANALYZED: '분석 완료', FINALIZED: '확정' };
export const categories: Record<Category, string> = { purchase: '구매', career: '진로·전공', study: '학습', activity: '활동', living: '생활', etc: '기타' };
export const defaultLevels = ['매우 아쉬움', '아쉬움', '보통', '좋음', '매우 좋음'];
export const uid = () => crypto.randomUUID();
export const maxQuestions = (k: number) => Math.min(12, Math.max(5, k * 2));
