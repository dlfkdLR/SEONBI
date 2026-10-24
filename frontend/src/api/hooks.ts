import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  confirmInput,
  extractInput,
  rejectInput,
  type ExtractInputRequest,
  type ExtractedItem,
} from "./inputs";
import {
  exportDecisionMarkdown,
  finalizeChoice,
  type FinalChoiceRequest,
} from "./results";
import { queryKeys } from "./queryKeys";

export function useExtractInputMutation(decisionId: string) {
  return useMutation({
    mutationFn: (input: ExtractInputRequest) => extractInput(decisionId, input),
  });
}

export function useConfirmInputMutation(decisionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ inputId, items }: { inputId: string; items: ExtractedItem[] }) =>
      confirmInput(decisionId, inputId, items),
    onSuccess: async (result) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.decision(decisionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.answers(decisionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.analysis(decisionId) }),
        ...(result.statusChanged
          ? [queryClient.invalidateQueries({ queryKey: queryKeys.question(decisionId) })]
          : []),
      ]);
    },
  });
}

export function useRejectInputMutation(decisionId: string) {
  return useMutation({
    mutationFn: (inputId: string) => rejectInput(decisionId, inputId),
  });
}

export function useFinalizeChoiceMutation(decisionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: FinalChoiceRequest) => finalizeChoice(decisionId, input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.decision(decisionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.decisions() }),
        queryClient.invalidateQueries({ queryKey: queryKeys.analysis(decisionId) }),
      ]);
    },
  });
}

export function useExportDecisionMarkdownMutation(decisionId: string) {
  return useMutation({
    mutationFn: () => exportDecisionMarkdown(decisionId),
  });
}
