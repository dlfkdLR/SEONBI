import { api } from './client';
import type { EndpointName } from './endpoints';

type Args = { params?: Record<string, string>; query?: Record<string, string | number | boolean | undefined>; body?: unknown };
type ApiClient = typeof api;

/** Endpoint functions follow the supplied API method/path list; DTOs stay unknown until the team confirms them. */
export function createDecisionApi(client: ApiClient) {
  const call = (name: Exclude<EndpointName, 'exportDecision'>, args: Args = {}) => client.request(name, args as never);
  const decision = (decisionId: string) => ({ decisionId });
  return {
    list: (query?: Args['query']) => call('listDecisions', { query }),
    create: (body: unknown) => call('createDecision', { body }),
    get: (decisionId: string) => call('getDecision', { params: decision(decisionId) }),
    update: (decisionId: string, body: unknown) => call('updateDecision', { params: decision(decisionId), body }),
    delete: (decisionId: string) => call('deleteDecision', { params: decision(decisionId) }),
    duplicate: (decisionId: string) => call('duplicateDecision', { params: decision(decisionId) }),
    getOptions: (decisionId: string) => call('listOptions', { params: decision(decisionId) }),
    addOption: (decisionId: string, body: unknown) => call('createOption', { params: decision(decisionId), body }),
    updateOption: (decisionId: string, optionId: string, body: unknown) => call('updateOption', { params: { ...decision(decisionId), optionId }, body }),
    deleteOption: (decisionId: string, optionId: string) => call('deleteOption', { params: { ...decision(decisionId), optionId } }),
    getCriteria: (decisionId: string) => call('listCriteria', { params: decision(decisionId) }),
    addCriterion: (decisionId: string, body: unknown) => call('createCriterion', { params: decision(decisionId), body }),
    updateCriterion: (decisionId: string, criterionId: string, body: unknown) => call('updateCriterion', { params: { ...decision(decisionId), criterionId }, body }),
    deleteCriterion: (decisionId: string, criterionId: string) => call('deleteCriterion', { params: { ...decision(decisionId), criterionId } }),
    getValues: (decisionId: string) => call('getValues', { params: decision(decisionId) }),
    saveValues: (decisionId: string, body: unknown) => call('saveValues', { params: decision(decisionId), body }),
    getConditions: (decisionId: string) => call('getConditions', { params: decision(decisionId) }),
    setConditions: (decisionId: string, body: unknown) => call('setConditions', { params: decision(decisionId), body }),
    startQuestions: (decisionId: string) => call('startQuestions', { params: decision(decisionId) }),
    nextQuestion: (decisionId: string) => call('nextQuestion', { params: decision(decisionId) }),
    submitAnswer: (decisionId: string, body: unknown) => call('submitAnswer', { params: decision(decisionId), body }),
    updateAnswer: (decisionId: string, answerId: string, body: unknown) => call('updateAnswer', { params: { ...decision(decisionId), answerId }, body }),
    questionProgress: (decisionId: string) => call('questionProgress', { params: decision(decisionId) }),
    questionStatus: (decisionId: string) => call('questionStatus', { params: decision(decisionId) }),
    stopQuestions: (decisionId: string) => call('stopQuestions', { params: decision(decisionId) }),
    listAnswers: (decisionId: string) => call('listAnswers', { params: decision(decisionId) }),
    recommendCriteria: (decisionId: string) => call('recommendCriteria', { params: decision(decisionId) }),
    recommendOptions: (decisionId: string) => call('recommendOptions', { params: decision(decisionId) }),
    analysis: (decisionId: string) => call('analysis', { params: decision(decisionId) }),
    weights: (decisionId: string) => call('weights', { params: decision(decisionId) }),
    rankings: (decisionId: string) => call('rankings', { params: decision(decisionId) }),
    scores: (decisionId: string) => call('scores', { params: decision(decisionId) }),
    sensitivity: (decisionId: string) => call('sensitivity', { params: decision(decisionId) }),
    simulate: (decisionId: string, body: unknown) => call('simulate', { params: decision(decisionId), body }),
    stability: (decisionId: string) => call('stability', { params: decision(decisionId) }),
    changeConditions: (decisionId: string) => call('changeConditions', { params: decision(decisionId) }),
    finalize: (decisionId: string, body: unknown) => call('finalizeDecision', { params: decision(decisionId), body }),
    export: (decisionId: string) => client.exportDecision({ params: decision(decisionId), query: { format: 'md' } }),
  };
}

export const decisionApi = createDecisionApi(api);
