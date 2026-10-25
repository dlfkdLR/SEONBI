import type { Category, Status, Direction } from '../api/contracts';
export type { Category, Status } from '../api/contracts';
export type CellValue = number | boolean | null;
export interface Option { id: string; name: string }
export interface Criterion {
  id: string;
  name: string;
  type: 'numeric' | 'rating' | 'boolean';
  direction: Direction;
  unit: string;
  levels: string[];
  inputMode: 'score' | 'rank';
  scaleMin?: number;
  scaleMax?: number;
  source?: 'user' | 'ai_recommended' | 'free_text';
}
export type Condition =
  | { id: string; kind: 'threshold'; criterionId: string; operator: '<=' | '>=' | '=='; value: number }
  | { id: string; kind: 'required_tag'; label: string; satisfiedBy: string[] };
export interface Decision {
  id: string;
  title: string;
  description: string;
  category: Category;
  status: Status;
  options: Option[];
  criteria: Criterion[];
  values: Record<string, Record<string, CellValue>>;
  conditions: Condition[];
  readiness?: { canStartQuestions: boolean; missing: string[] };
  excludedOptions?: { optionId: string; reasons: string[] }[];
  createdAt: string;
  updatedAt: string;
}
export const statusLabels: Record<Status, string> = { DRAFT: '작성 중', QUESTIONING: '질문 중', ANALYZED: '분석 완료', FINALIZED: '확정' };
export const categories: Record<Category, string> = { purchase: '구매', career: '진로·전공', study: '학습', activity: '활동', living: '생활', etc: '기타' };
export const defaultLevels = ['매우 아쉬움', '아쉬움', '보통', '좋음', '매우 좋음'];
export const uid = () => crypto.randomUUID();
