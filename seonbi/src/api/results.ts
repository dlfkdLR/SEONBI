import { decisionPath, requestFile, requestJson, type DownloadedFile } from "./http";

export interface FinalChoiceRequest {
  optionId: string;
  memo?: string;
}

export interface FinalChoiceResponse {
  decisionId: string;
  optionId: string;
  matchedRecommendation: boolean;
  finalizedAt: string;
  status: "FINALIZED";
}

export function finalizeChoice(decisionId: string, input: FinalChoiceRequest) {
  return requestJson<FinalChoiceResponse>(`${decisionPath(decisionId)}/final-choice`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function exportDecisionMarkdown(decisionId: string): Promise<DownloadedFile> {
  return requestFile(
    `${decisionPath(decisionId)}/export?format=md`,
    `decision-${decisionId}.md`,
  );
}

/** Call from a user gesture so the browser permits the download. */
export async function downloadDecisionMarkdown(decisionId: string): Promise<void> {
  const { blob, filename } = await exportDecisionMarkdown(decisionId);
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  try {
    link.click();
  } finally {
    link.remove();
    URL.revokeObjectURL(url);
  }
}
