import { decisionPath, requestJson } from "./http";

export type Confidence = "high" | "medium" | "low";

export type ExtractedItem =
  | {
      type: "criterion";
      name: string;
      criterionType: "numeric" | "rating" | "boolean";
      direction?: string;
      unit?: string;
      levels?: string[];
      inputMode?: string;
      confidence: Confidence;
    }
  | {
      type: "constraint";
      kind: "threshold" | "required_tag";
      criterionId?: string;
      operator?: "<=" | ">=" | "==";
      value?: number | boolean;
      label?: string;
      satisfiedBy?: string[];
      confidence: Confidence;
    }
  | {
      type: "option";
      name: string;
      description?: string;
      url?: string;
      confidence: Confidence;
    }
  | {
      type: "preference";
      criterionA: string;
      criterionB: string;
      value: number;
      confidence: Confidence;
    };

export interface ExtractInputRequest {
  context: "other_criterion" | "other_condition" | "reason" | "add_option";
  questionId?: string | null;
  text: string;
}

export interface ExtractInputResponse {
  id: string;
  status: "PENDING";
  extracted: ExtractedItem[];
  needsClarification: string | null;
}

export interface ConfirmInputResponse {
  status: "CONFIRMED";
  created: {
    conditions: string[];
    criteria: string[];
    options: string[];
    answers: string[];
  };
  statusChanged: "QUESTIONING" | null;
}

export function extractInput(decisionId: string, input: ExtractInputRequest) {
  return requestJson<ExtractInputResponse>(`${decisionPath(decisionId)}/inputs`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function confirmInput(decisionId: string, inputId: string, items: ExtractedItem[]) {
  return requestJson<ConfirmInputResponse>(
    `${decisionPath(decisionId)}/inputs/${encodeURIComponent(inputId)}/confirm`,
    { method: "POST", body: JSON.stringify({ items }) },
  );
}

export function rejectInput(decisionId: string, inputId: string) {
  return requestJson<{ status: "REJECTED" }>(
    `${decisionPath(decisionId)}/inputs/${encodeURIComponent(inputId)}/reject`,
    { method: "POST" },
  );
}

/** Rejects an ambiguous extraction and retries with the user's clarification. */
export async function retryInputWithClarification(
  decisionId: string,
  inputId: string,
  input: ExtractInputRequest,
  clarification: string,
): Promise<ExtractInputResponse> {
  await rejectInput(decisionId, inputId);
  return extractInput(decisionId, {
    ...input,
    text: `${input.text.trim()}
추가 설명: ${clarification.trim()}`,
  });
}
