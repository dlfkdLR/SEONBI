/** Development plan v0.5 §7.2, schema v1. Authentication endpoints are excluded. */
export const endpoints = {
  health: { method: 'GET', path: '/api/health' },
  getDecision: { method: 'GET', path: '/api/v1/decisions/{decisionId}' },
  duplicateDecision: { method: 'POST', path: '/api/v1/decisions/{decisionId}/duplicate' },
  listValues: { method: 'GET', path: '/api/v1/decisions/{decisionId}/values' },
  saveValues: { method: 'PUT', path: '/api/v1/decisions/{decisionId}/values' },
  listConditions: { method: 'GET', path: '/api/v1/decisions/{decisionId}/conditions' },
  extractInput: { method: 'POST', path: '/api/v1/decisions/{decisionId}/inputs' },
  confirmInput: { method: 'POST', path: '/api/v1/decisions/{decisionId}/inputs/{inputId}/confirm' },
  rejectInput: { method: 'POST', path: '/api/v1/decisions/{decisionId}/inputs/{inputId}/reject' },
  stopQuestions: { method: 'POST', path: '/api/v1/decisions/{decisionId}/questions/stop' },
  listAnswers: { method: 'GET', path: '/api/v1/decisions/{decisionId}/answers' },
  scores: { method: 'GET', path: '/api/v1/decisions/{decisionId}/scores' },

  listDecisions: { method: 'GET', path: '/api/v1/decisions' },
  createDecision: { method: 'POST', path: '/api/v1/decisions' },
  /** 9-1번: 초기 설정 5단계를 끝낸 뒤 한 번에 저장하고 결정 ID를 처음 발급받는다 */
  completeSetup: { method: 'POST', path: '/api/v1/decisions/setup' },
  /** 9-2번: 결정을 만들기 전(초기 설정 3단계)에 기준 추천만 받는다. 저장하지 않는다 */
  recommendCriteriaDraft: { method: 'POST', path: '/api/v1/decisions/criteria/recommendations' },
  updateDecision: { method: 'PATCH', path: '/api/v1/decisions/{decisionId}' },
  deleteDecision: { method: 'DELETE', path: '/api/v1/decisions/{decisionId}' },
  createOption: { method: 'POST', path: '/api/v1/decisions/{decisionId}/options' },
  listOptions: { method: 'GET', path: '/api/v1/decisions/{decisionId}/options' },
  updateOption: { method: 'PATCH', path: '/api/v1/decisions/{decisionId}/options/{optionId}' },
  deleteOption: { method: 'DELETE', path: '/api/v1/decisions/{decisionId}/options/{optionId}' },
  createCriterion: { method: 'POST', path: '/api/v1/decisions/{decisionId}/criteria' },
  listCriteria: { method: 'GET', path: '/api/v1/decisions/{decisionId}/criteria' },
  updateCriterion: { method: 'PATCH', path: '/api/v1/decisions/{decisionId}/criteria/{criterionId}' },
  deleteCriterion: { method: 'DELETE', path: '/api/v1/decisions/{decisionId}/criteria/{criterionId}' },
  setConditions: { method: 'PUT', path: '/api/v1/decisions/{decisionId}/conditions' },
  startQuestions: { method: 'POST', path: '/api/v1/decisions/{decisionId}/questions/start' },
  nextQuestion: { method: 'GET', path: '/api/v1/decisions/{decisionId}/questions/next' },
  submitAnswer: { method: 'POST', path: '/api/v1/decisions/{decisionId}/answers' },
  updateAnswer: { method: 'PATCH', path: '/api/v1/decisions/{decisionId}/answers/{answerId}' },
  questionProgress: { method: 'GET', path: '/api/v1/decisions/{decisionId}/questions/progress' },
  questionStatus: { method: 'GET', path: '/api/v1/decisions/{decisionId}/questions/status' },
  recommendCriteria: { method: 'POST', path: '/api/v1/decisions/{decisionId}/criteria/recommendations' },
  recommendOptions: { method: 'POST', path: '/api/v1/decisions/{decisionId}/options/recommendations' },
  analysis: { method: 'GET', path: '/api/v1/decisions/{decisionId}/analysis' },
  weights: { method: 'GET', path: '/api/v1/decisions/{decisionId}/weights' },
  rankings: { method: 'GET', path: '/api/v1/decisions/{decisionId}/rankings' },
  sensitivity: { method: 'GET', path: '/api/v1/decisions/{decisionId}/sensitivity' },
  simulate: { method: 'POST', path: '/api/v1/decisions/{decisionId}/simulations' },
  stability: { method: 'GET', path: '/api/v1/decisions/{decisionId}/stability' },
  changeConditions: { method: 'GET', path: '/api/v1/decisions/{decisionId}/change-conditions' },
  finalizeDecision: { method: 'POST', path: '/api/v1/decisions/{decisionId}/final-choice' },
  exportDecision: { method: 'GET', path: '/api/v1/decisions/{decisionId}/export' },
} as const;
export type EndpointName = keyof typeof endpoints;
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue | undefined };
type PathKeys<S extends string> = S extends `${string}{${infer K}}${infer Rest}` ? K | PathKeys<Rest> : never;
export type PathParams<K extends EndpointName> = Record<PathKeys<(typeof endpoints)[K]['path']>, string>;
export type RequestOptions<K extends EndpointName> = {
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
} & (PathKeys<(typeof endpoints)[K]['path']> extends never ? { params?: never } : { params: PathParams<K> })
  & ((typeof endpoints)[K]['method'] extends 'POST' | 'PATCH' | 'PUT' ? { body?: JsonValue } : { body?: never });
