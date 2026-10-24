/** Authoritative methods/paths: the supplied 35-row API index.
 * Five user/auth endpoints are omitted at the user's explicit request.
 * No body or response schemas were supplied with that index.
 */
export const endpoints = {
  listDecisions: { method: 'GET', path: '/api/v1/decisions' },
  createDecision: { method: 'POST', path: '/api/v1/decisions' },
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
  setConditions: { method: 'PATCH', path: '/api/v1/decisions/{decisionId}/conditions' },
  startQuestions: { method: 'POST', path: '/api/v1/decisions/{decisionId}/questions/start' },
  nextQuestion: { method: 'GET', path: '/api/v1/decisions/{decisionId}/questions/next' },
  submitAnswer: { method: 'POST', path: '/api/v1/decisions/{decisionId}/answers' },
  updateAnswer: { method: 'PATCH', path: '/api/v1/decisions/{decisionId}/answers/{answerId}' },
  questionProgress: { method: 'GET', path: '/api/v1/decisions/{decisionId}/questions/progress' },
  questionStatus: { method: 'GET', path: '/api/v1/decisions/{decisionId}/questions/status' },
  recommendCriteria: { method: 'GET', path: '/api/v1/decisions/{decisionId}/criteria/recommendations' },
  recommendOptions: { method: 'GET', path: '/api/v1/decisions/{decisionId}/options/recommendations' },
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
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
type PathKeys<S extends string> = S extends `${string}{${infer K}}${infer Rest}` ? K | PathKeys<Rest> : never;
export type PathParams<K extends EndpointName> = Record<PathKeys<(typeof endpoints)[K]['path']>, string>;
export type RequestOptions<K extends EndpointName> = {
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
} & (PathKeys<(typeof endpoints)[K]['path']> extends never ? { params?: never } : { params: PathParams<K> })
  & ((typeof endpoints)[K]['method'] extends 'POST' | 'PATCH' ? { body?: JsonValue } : { body?: never });
