export const queryKeys = {
  decisions: (filters?: { status?: string }) => ["decisions", filters ?? {}] as const,
  decision: (decisionId: string) => ["decision", decisionId] as const,
  question: (decisionId: string) => ["question", decisionId] as const,
  answers: (decisionId: string) => ["answers", decisionId] as const,
  analysis: (decisionId: string, section?: string) =>
    section ? (["analysis", decisionId, section] as const) : (["analysis", decisionId] as const),
  scores: (decisionId: string) => ["scores", decisionId] as const,
  sensitivity: (decisionId: string, criterionId: string) =>
    ["sensitivity", decisionId, criterionId] as const,
};
