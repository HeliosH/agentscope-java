export interface ToolOperationView {
  id: string;
  runId: string;
  userId: string;
  agentId: string;
  taskId: string;
  toolName: string;
  toolCallId: string;
  inputHash: string;
  retrySafety: string;
  status: string;
  effectiveStatus: string;
  version: number;
  runVersion: number;
  resolutionVersion: number;
  preparedAt: string;
  toolContractHash?: string | null;
  toolContractRecoverable?: boolean;
  logicalOriginId?: string | null;
}

export interface OperationPage {
  items: ToolOperationView[];
  nextCursor: string | null;
}

export type OutcomeDecision = 'CONFIRMED_SUCCESS' | 'CONFIRMED_NOT_PERFORMED' | 'UNRESOLVED';

export interface EvidenceView {
  id: string;
  sequence: number;
  operationVersion: number;
  actorId: string;
  decision: OutcomeDecision;
  reason: string;
  evidenceRef: string;
  replayApproved: boolean;
  createdAt: string;
}

export interface EvidenceRequest {
  expectedRunVersion: number;
  expectedOperationVersion: number;
  expectedResolutionVersion: number;
  decision: OutcomeDecision;
  reason: string;
  evidenceRef: string;
  resultText: string | null;
  approveReplay: boolean;
}

export class OperationError extends Error {
  constructor(public readonly status: number) {
    super(status === 409 ? 'The record changed. Refresh before submitting evidence again.'
      : status === 403 ? 'Administrator permission is no longer available.'
      : status === 404 ? 'This operation is no longer available.'
      : `Operation request failed (${status}).`);
  }
}

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) throw new OperationError(response.status);
  return response.json();
}

function cursor(after?: string | null) {
  const query = new URLSearchParams({ limit: '25' });
  if (after) query.set('after', after);
  return query;
}

export async function listOwnedOperations(agent: string, run: string, after?: string | null): Promise<OperationPage> {
  return json(await fetch(`/api/agents/${encodeURIComponent(agent)}/runs/${encodeURIComponent(run)}/operations?${cursor(after)}`));
}

export async function listAdminOperations(state: string, after?: string | null): Promise<OperationPage> {
  const query = cursor(after);
  query.set('state', state);
  return json(await fetch(`/api/admin/tool-operations?${query}`));
}

const base = (operation: ToolOperationView) =>
  `/api/admin/runs/${encodeURIComponent(operation.runId)}/operations/${encodeURIComponent(operation.id)}`;

export async function listEvidence(operation: ToolOperationView, after = 0): Promise<EvidenceView[]> {
  return json(await fetch(`${base(operation)}/resolutions?after=${after}&limit=25`));
}

export async function reconcileOperation(operation: ToolOperationView, key: string, input: EvidenceRequest): Promise<{ reused: boolean }> {
  return json(await fetch(`${base(operation)}/reconcile`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(input),
  }));
}

export const outcomeLabel = (status: string) => status.toLowerCase().replace(/_/g, ' ');
