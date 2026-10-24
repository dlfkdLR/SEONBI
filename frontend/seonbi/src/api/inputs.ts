import { api } from './client';
import { clarifiedInputText, validateInputText } from './inputText';

export type InputContext = 'other_criterion' | 'other_condition' | 'reason' | 'add_option';
export interface ExtractInputRequest { context: InputContext; questionId?: string | null; text: string }
export interface ExtractedItem { type: string; [key: string]: unknown }
export interface ExtractInputResponse { id: string; status: 'PENDING'; extracted: ExtractedItem[]; needsClarification: string | null }

type ApiClient = typeof api;
export function createInputApi(client: ApiClient) {
  const extractInput = (decisionId: string, input: ExtractInputRequest): Promise<unknown> =>
    client.request('extractInput', { params: { decisionId }, body: { ...input, text: validateInputText(input.text) } } as never);
  const confirmInput = (decisionId: string, inputId: string, items: ExtractedItem[]): Promise<unknown> =>
    client.request('confirmInput', { params: { decisionId, inputId }, body: { items } } as never);
  const rejectInput = (decisionId: string, inputId: string): Promise<unknown> =>
    client.request('rejectInput', { params: { decisionId, inputId } });
  /** Validate the combined text before rejecting the pending extraction or making another request. */
  const retryInputWithClarification = async (
    decisionId: string,
    inputId: string,
    input: ExtractInputRequest,
    clarification: string,
  ): Promise<unknown> => {
    const text = clarifiedInputText(input.text, clarification);
    await rejectInput(decisionId, inputId);
    return extractInput(decisionId, { ...input, text });
  };
  return { extractInput, confirmInput, rejectInput, retryInputWithClarification };
}

export const { extractInput, confirmInput, rejectInput, retryInputWithClarification } = createInputApi(api);
