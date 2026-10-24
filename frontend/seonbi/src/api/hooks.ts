import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { decisionApi } from './operations';
import { confirmInput, extractInput, rejectInput, retryInputWithClarification, type ExtractInputRequest, type ExtractedItem } from './inputs';

export const apiQueryKeys = {
  decisions: ['api', 'decisions'] as const,
  decision: (id: string) => ['api', 'decision', id] as const,
  options: (id: string) => ['api', 'decision', id, 'options'] as const,
  criteria: (id: string) => ['api', 'decision', id, 'criteria'] as const,
  values: (id: string) => ['api', 'decision', id, 'values'] as const,
  conditions: (id: string) => ['api', 'decision', id, 'conditions'] as const,
  question: (id: string) => ['api', 'decision', id, 'question'] as const,
  answers: (id: string) => ['api', 'decision', id, 'answers'] as const,
  analysis: (id: string) => ['api', 'decision', id, 'analysis'] as const,
  weights: (id: string) => ['api', 'decision', id, 'weights'] as const,
  rankings: (id: string) => ['api', 'decision', id, 'rankings'] as const,
  sensitivity: (id: string) => ['api', 'decision', id, 'sensitivity'] as const,
  stability: (id: string) => ['api', 'decision', id, 'stability'] as const,
  changeConditions: (id: string) => ['api', 'decision', id, 'change-conditions'] as const,
};

function useDecisionMutation<T>(id: string, mutationFn: (input: T) => Promise<unknown>) {
  const cache = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: async () => {
      await Promise.all([
        cache.invalidateQueries({ queryKey: apiQueryKeys.decisions }),
        cache.invalidateQueries({ queryKey: apiQueryKeys.decision(id) }),
        cache.invalidateQueries({ queryKey: ['api', 'decision', id] }),
      ]);
    },
  });
}

export function useApiDecisions(query?: Record<string, string | number | boolean | undefined>) {
  return useQuery({ queryKey: [...apiQueryKeys.decisions, query ?? {}], queryFn: () => decisionApi.list(query) });
}
export function useApiDecision(id: string) {
  return useQuery({ queryKey: apiQueryKeys.decision(id), queryFn: () => decisionApi.get(id), enabled: Boolean(id) });
}
export function useApiOptions(id: string) {
  return useQuery({ queryKey: apiQueryKeys.options(id), queryFn: () => decisionApi.getOptions(id), enabled: Boolean(id) });
}
export function useApiCriteria(id: string) {
  return useQuery({ queryKey: apiQueryKeys.criteria(id), queryFn: () => decisionApi.getCriteria(id), enabled: Boolean(id) });
}
export function useApiValues(id: string) {
  return useQuery({ queryKey: apiQueryKeys.values(id), queryFn: () => decisionApi.getValues(id), enabled: Boolean(id) });
}
export function useApiConditions(id: string) {
  return useQuery({ queryKey: apiQueryKeys.conditions(id), queryFn: () => decisionApi.getConditions(id), enabled: Boolean(id) });
}
export function useApiNextQuestion(id: string) {
  return useQuery({ queryKey: apiQueryKeys.question(id), queryFn: () => decisionApi.nextQuestion(id), enabled: Boolean(id) });
}
export function useApiQuestionProgress(id: string) {
  return useQuery({ queryKey: [...apiQueryKeys.question(id), 'progress'], queryFn: () => decisionApi.questionProgress(id), enabled: Boolean(id) });
}
export function useApiQuestionStatus(id: string) {
  return useQuery({ queryKey: [...apiQueryKeys.question(id), 'status'], queryFn: () => decisionApi.questionStatus(id), enabled: Boolean(id) });
}
export function useApiAnswers(id: string) {
  return useQuery({ queryKey: apiQueryKeys.answers(id), queryFn: () => decisionApi.listAnswers(id), enabled: Boolean(id) });
}
export function useApiAnalysis(id: string) {
  return useQuery({ queryKey: apiQueryKeys.analysis(id), queryFn: () => decisionApi.analysis(id), enabled: Boolean(id) });
}
export function useApiWeights(id: string) {
  return useQuery({ queryKey: apiQueryKeys.weights(id), queryFn: () => decisionApi.weights(id), enabled: Boolean(id) });
}
export function useApiRankings(id: string) {
  return useQuery({ queryKey: apiQueryKeys.rankings(id), queryFn: () => decisionApi.rankings(id), enabled: Boolean(id) });
}
export function useApiScores(id: string) {
  return useQuery({ queryKey: [...apiQueryKeys.analysis(id), 'scores'], queryFn: () => decisionApi.scores(id), enabled: Boolean(id) });
}
export function useApiSensitivity(id: string) {
  return useQuery({ queryKey: apiQueryKeys.sensitivity(id), queryFn: () => decisionApi.sensitivity(id), enabled: Boolean(id) });
}
export function useApiStability(id: string) {
  return useQuery({ queryKey: apiQueryKeys.stability(id), queryFn: () => decisionApi.stability(id), enabled: Boolean(id) });
}
export function useApiChangeConditions(id: string) {
  return useQuery({ queryKey: apiQueryKeys.changeConditions(id), queryFn: () => decisionApi.changeConditions(id), enabled: Boolean(id) });
}

export function useCreateDecision() { return useDecisionMutation('', (body: unknown) => decisionApi.create(body)); }
export function useUpdateDecision(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.update(id, body)); }
export function useDeleteDecision() {
  const cache = useQueryClient();
  return useMutation({ mutationFn: (id: string) => decisionApi.delete(id), onSuccess: () => cache.invalidateQueries({ queryKey: apiQueryKeys.decisions }) });
}
export function useDuplicateDecision() {
  const cache = useQueryClient();
  return useMutation({ mutationFn: (id: string) => decisionApi.duplicate(id), onSuccess: () => cache.invalidateQueries({ queryKey: apiQueryKeys.decisions }) });
}
export function useAddOption(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.addOption(id, body)); }
export function useUpdateOption(id: string) { return useDecisionMutation(id, ({ optionId, body }: { optionId: string; body: unknown }) => decisionApi.updateOption(id, optionId, body)); }
export function useDeleteOption(id: string) { return useDecisionMutation(id, (optionId: string) => decisionApi.deleteOption(id, optionId)); }
export function useAddCriterion(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.addCriterion(id, body)); }
export function useUpdateCriterion(id: string) { return useDecisionMutation(id, ({ criterionId, body }: { criterionId: string; body: unknown }) => decisionApi.updateCriterion(id, criterionId, body)); }
export function useDeleteCriterion(id: string) { return useDecisionMutation(id, (criterionId: string) => decisionApi.deleteCriterion(id, criterionId)); }
export function useSetConditions(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.setConditions(id, body)); }
export function useStartQuestions(id: string) { return useDecisionMutation(id, () => decisionApi.startQuestions(id)); }
export function useSubmitAnswer(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.submitAnswer(id, body)); }
export function useUpdateAnswer(id: string) { return useDecisionMutation(id, ({ answerId, body }: { answerId: string; body: unknown }) => decisionApi.updateAnswer(id, answerId, body)); }
export function useStopQuestions(id: string) { return useDecisionMutation(id, () => decisionApi.stopQuestions(id)); }
export function useSaveValues(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.saveValues(id, body)); }
export function useSimulateWeights(id: string) { return useMutation({ mutationFn: (body: unknown) => decisionApi.simulate(id, body) }); }
export function useFinalizeDecision(id: string) { return useDecisionMutation(id, (body: unknown) => decisionApi.finalize(id, body)); }
export function useExtractInput(id: string) { return useMutation({ mutationFn: (input: ExtractInputRequest) => extractInput(id, input) }); }
export function useConfirmInput(id: string) { return useDecisionMutation(id, ({ inputId, items }: { inputId: string; items: ExtractedItem[] }) => confirmInput(id, inputId, items)); }
export function useRejectInput(id: string) { return useMutation({ mutationFn: (inputId: string) => rejectInput(id, inputId) }); }
export function useRetryInputWithClarification(id: string) {
  return useMutation({ mutationFn: ({ inputId, input, clarification }: { inputId: string; input: ExtractInputRequest; clarification: string }) => retryInputWithClarification(id, inputId, input, clarification) });
}
export function useRecommendCriteria(id: string) { return useMutation({ mutationFn: () => decisionApi.recommendCriteria(id) }); }
export function useRecommendOptions(id: string) { return useMutation({ mutationFn: () => decisionApi.recommendOptions(id) }); }
export function useExportDecision() { return useMutation({ mutationFn: (id: string) => decisionApi.export(id) }); }
