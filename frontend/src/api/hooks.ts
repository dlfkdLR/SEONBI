import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import * as decisions from "./decisions";
import * as questions from "./questions";
import * as analysis from "./analysis";
import { confirmInput, extractInput, rejectInput, type ExtractInputRequest, type ExtractedItem } from "./inputs";
import { exportDecisionMarkdown, finalizeChoice, type FinalChoiceRequest } from "./results";
import { queryKeys } from "./queryKeys";

function invalidateDecision(client: ReturnType<typeof useQueryClient>, id: string) {
  return Promise.all([
    client.invalidateQueries({ queryKey: queryKeys.decisions() }),
    client.invalidateQueries({ queryKey: queryKeys.decision(id) }),
    client.invalidateQueries({ queryKey: queryKeys.options(id) }),
    client.invalidateQueries({ queryKey: queryKeys.criteria(id) }),
    client.invalidateQueries({ queryKey: queryKeys.values(id) }),
    client.invalidateQueries({ queryKey: queryKeys.conditions(id) }),
    client.invalidateQueries({ queryKey: queryKeys.analysis(id) }),
    client.invalidateQueries({ queryKey: queryKeys.scores(id) }),
    client.invalidateQueries({ queryKey: queryKeys.sensitivity(id) }),
  ]);
}
function invalidateQuestionFlow(client: ReturnType<typeof useQueryClient>, id: string) {
  return Promise.all([
    invalidateDecision(client, id),
    client.invalidateQueries({ queryKey: queryKeys.question(id) }),
    client.invalidateQueries({ queryKey: queryKeys.questionStatus(id) }),
    client.invalidateQueries({ queryKey: queryKeys.questionProgress(id) }),
    client.invalidateQueries({ queryKey: queryKeys.answers(id) }),
  ]);
}

// Decision and setup queries
export function useDecisions(filters: Parameters<typeof decisions.listDecisions>[0] = {}) {
  return useQuery({ queryKey: queryKeys.decisions(filters), queryFn: () => decisions.listDecisions(filters) });
}
export function useDecision(id: string) { return useQuery({ queryKey: queryKeys.decision(id), queryFn: () => decisions.getDecision(id), enabled: Boolean(id) }); }
export function useOptions(id: string) { return useQuery({ queryKey: queryKeys.options(id), queryFn: () => decisions.listOptions(id), enabled: Boolean(id) }); }
export function useCriteria(id: string) { return useQuery({ queryKey: queryKeys.criteria(id), queryFn: () => decisions.listCriteria(id), enabled: Boolean(id) }); }
export function useValues(id: string) { return useQuery({ queryKey: queryKeys.values(id), queryFn: () => decisions.getValues(id), enabled: Boolean(id) }); }
export function useConditions(id: string) { return useQuery({ queryKey: queryKeys.conditions(id), queryFn: () => decisions.getConditions(id), enabled: Boolean(id) }); }

export function useCreateDecisionMutation() {
  const client = useQueryClient();
  return useMutation({ mutationFn: decisions.createDecision, onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.decisions() }) });
}
export function useUpdateDecisionMutation(id: string) {
  const client = useQueryClient();
  return useMutation({ mutationFn: (input: decisions.PatchDecisionRequest) => decisions.updateDecision(id, input), onSuccess: () => Promise.all([invalidateDecision(client, id), client.invalidateQueries({ queryKey: queryKeys.decisions() })]) });
}
export function useDeleteDecisionMutation() {
  const client = useQueryClient();
  return useMutation({ mutationFn: decisions.deleteDecision, onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.decisions() }) });
}
export function useDuplicateDecisionMutation() {
  const client = useQueryClient();
  return useMutation({ mutationFn: ({ id, title }: { id: string; title?: string }) => decisions.duplicateDecision(id, title), onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.decisions() }) });
}

// Options, criteria, values, and constraints
export function useAddOptionMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (input: { name: string; description?: string; url?: string }) => decisions.addOption(id, input), onSuccess: () => invalidateDecision(c, id) }); }
export function useUpdateOptionMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: ({ optionId, input }: { optionId: string; input: Partial<{ name: string; description: string; url: string }> }) => decisions.updateOption(id, optionId, input), onSuccess: () => invalidateDecision(c, id) }); }
export function useDeleteOptionMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (optionId: string) => decisions.deleteOption(id, optionId), onSuccess: () => invalidateDecision(c, id) }); }
export function useRecommendOptionsMutation(id: string) { return useMutation({ mutationFn: (count: number) => decisions.recommendOptions(id, count) }); }
export function useAddCriterionMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (input: decisions.CriterionRequest) => decisions.addCriterion(id, input), onSuccess: () => invalidateQuestionFlow(c, id) }); }
export function useUpdateCriterionMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: ({ criterionId, input }: { criterionId: string; input: Partial<decisions.CriterionRequest> }) => decisions.updateCriterion(id, criterionId, input), onSuccess: () => invalidateDecision(c, id) }); }
export function useDeleteCriterionMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (criterionId: string) => decisions.deleteCriterion(id, criterionId), onSuccess: () => invalidateQuestionFlow(c, id) }); }
export function useRecommendCriteriaMutation(id: string) { return useMutation({ mutationFn: () => decisions.recommendCriteria(id) }); }
export function useSaveValuesMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (input: { items?: decisions.ValueItem[]; rankings?: decisions.Ranking[] }) => decisions.saveValues(id, input), onSuccess: () => invalidateDecision(c, id) }); }
export function useReplaceConditionsMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (items: decisions.Condition[]) => decisions.replaceConditions(id, items), onSuccess: () => invalidateDecision(c, id) }); }

// Question and answer flow
export function useNextQuestion(id: string) { return useQuery({ queryKey: queryKeys.question(id), queryFn: () => questions.getNextQuestion(id), enabled: Boolean(id) }); }
export function useQuestionStatus(id: string) { return useQuery({ queryKey: queryKeys.questionStatus(id), queryFn: () => questions.getQuestionStatus(id), enabled: Boolean(id) }); }
export function useQuestionProgress(id: string) { return useQuery({ queryKey: queryKeys.questionProgress(id), queryFn: () => questions.getQuestionProgress(id), enabled: Boolean(id) }); }
export function useAnswers(id: string) { return useQuery({ queryKey: queryKeys.answers(id), queryFn: () => questions.listAnswers(id), enabled: Boolean(id) }); }
export function useStartQuestionsMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: () => questions.startQuestions(id), onSuccess: () => invalidateQuestionFlow(c, id) }); }
export function useStopQuestionsMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: () => questions.stopQuestions(id), onSuccess: () => invalidateQuestionFlow(c, id) }); }
export function useSubmitAnswerMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: (input: { questionId: string; response: Record<string, unknown> }) => questions.submitAnswer(id, input), onSuccess: () => invalidateQuestionFlow(c, id) }); }
export function useEditAnswerMutation(id: string) { const c = useQueryClient(); return useMutation({ mutationFn: ({ answerId, response }: { answerId: string; response: Record<string, unknown> }) => questions.editAnswer(id, answerId, response), onSuccess: () => invalidateQuestionFlow(c, id) }); }

// Analysis and result queries
export function useAnalysis(id: string) { return useQuery({ queryKey: queryKeys.analysis(id), queryFn: () => analysis.getAnalysis(id), enabled: Boolean(id) }); }
export function useWeights(id: string) { return useQuery({ queryKey: queryKeys.weights(id), queryFn: () => analysis.getWeights(id), enabled: Boolean(id) }); }
export function useRankings(id: string) { return useQuery({ queryKey: queryKeys.rankings(id), queryFn: () => analysis.getRankings(id), enabled: Boolean(id) }); }
export function useScores(id: string) { return useQuery({ queryKey: queryKeys.scores(id), queryFn: () => analysis.getScores(id), enabled: Boolean(id) }); }
export function useSensitivity(id: string, criterionId?: string) { return useQuery({ queryKey: queryKeys.sensitivity(id, criterionId), queryFn: () => analysis.getSensitivity(id, criterionId), enabled: Boolean(id) }); }
export function useStability(id: string) { return useQuery({ queryKey: queryKeys.stability(id), queryFn: () => analysis.getStability(id), enabled: Boolean(id) }); }
export function useChangeConditions(id: string) { return useQuery({ queryKey: queryKeys.changeConditions(id), queryFn: () => analysis.getChangeConditions(id), enabled: Boolean(id) }); }
export function useSimulateWeightsMutation(id: string) { return useMutation({ mutationFn: (input: analysis.SimulationRequest) => analysis.simulateWeights(id, input) }); }

// C APIs: explicit user confirmation before extracted data is committed.
export function useExtractInputMutation(id: string) { return useMutation({ mutationFn: (input: ExtractInputRequest) => extractInput(id, input) }); }
export function useConfirmInputMutation(id: string) {
  const client = useQueryClient();
  return useMutation({ mutationFn: ({ inputId, items }: { inputId: string; items: ExtractedItem[] }) => confirmInput(id, inputId, items), onSuccess: (result) => Promise.all([invalidateDecision(client, id), client.invalidateQueries({ queryKey: queryKeys.answers(id) }), ...(result.statusChanged ? [client.invalidateQueries({ queryKey: queryKeys.question(id) })] : [])]) });
}
export function useRejectInputMutation(id: string) { return useMutation({ mutationFn: (inputId: string) => rejectInput(id, inputId) }); }
export function useFinalizeChoiceMutation(id: string) { const client = useQueryClient(); return useMutation({ mutationFn: (input: FinalChoiceRequest) => finalizeChoice(id, input), onSuccess: () => Promise.all([invalidateDecision(client, id), client.invalidateQueries({ queryKey: queryKeys.decisions() })]) }); }
export function useExportDecisionMarkdownMutation(id: string) { return useMutation({ mutationFn: () => exportDecisionMarkdown(id) }); }
