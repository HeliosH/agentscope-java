export interface RunView {
  id: string;
  sessionId: string;
  agentId: string;
  mode: string;
  status: string;
  cancelRequested: boolean;
  failureCode?: string | null;
  failureMessage?: string | null;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface TaskView {
  id: string;
  parentId?: string | null;
  title: string;
  taskType: string;
  status: string;
  workspaceMode: string;
  createdAt: string;
  completedAt?: string | null;
}

export interface PlanTaskView {
  taskId: string;
  clientTaskId: string;
  title: string;
  agentType: string;
  status: string;
  workspaceMode: string;
  acceptanceJson: string;
  ownerAgentRunId?: string | null;
}

export interface PlanEdgeView {
  fromTaskId: string;
  toTaskId: string;
  edgeType: string;
}

export interface PlanView {
  planId: string;
  runId: string;
  version: number;
  status: string;
  goal: string;
  approvalRequired: boolean;
  tasks: PlanTaskView[];
  edges: PlanEdgeView[];
}

export interface RunEventView {
  seq: number;
  eventType: string;
  taskId?: string | null;
  payloadJson: string;
  createdAt: string;
}

export interface ArtifactView {
  id: string;
  taskId: string;
  attemptId: string;
  fileId: string;
  fileVersionId: string;
  logicalPath: string;
  artifactType: string;
  evidenceJson: string;
  createdAt: string;
}

const base = (agentId: string, runId: string) =>
  `/api/agents/${encodeURIComponent(agentId)}/runs/${encodeURIComponent(runId)}`;

async function json<T>(response: Response, operation: string): Promise<T> {
  if (!response.ok) throw new Error(`${operation}: ${response.status}`);
  return response.json();
}

export async function getRun(agentId: string, runId: string): Promise<RunView> {
  return json(await fetch(base(agentId, runId)), 'Failed to load run');
}

/** Lists the most recent runs for an agent, newest first. */
export async function listRuns(agentId: string, limit = 50): Promise<RunView[]> {
  return json(
    await fetch(`/api/agents/${encodeURIComponent(agentId)}/runs?limit=${limit}`),
    'Failed to load runs',
  );
}

export async function getTasks(agentId: string, runId: string): Promise<TaskView[]> {
  return json(await fetch(`${base(agentId, runId)}/tasks`), 'Failed to load tasks');
}

export async function getPlan(agentId: string, runId: string): Promise<PlanView | null> {
  const response = await fetch(`${base(agentId, runId)}/plan`);
  if (response.status === 404) return null;
  return json(response, 'Failed to load plan');
}

export async function getEvents(
  agentId: string,
  runId: string,
  afterSeq = 0,
): Promise<RunEventView[]> {
  const query = new URLSearchParams({ afterSeq: String(afterSeq), limit: '200' });
  return json(
    await fetch(`${base(agentId, runId)}/events?${query}`),
    'Failed to load run events',
  );
}

export async function getArtifacts(agentId: string, runId: string): Promise<ArtifactView[]> {
  return json(await fetch(`${base(agentId, runId)}/artifacts`), 'Failed to load artifacts');
}

export async function decidePlan(
  agentId: string,
  runId: string,
  planId: string,
  decision: 'APPROVE' | 'REJECT',
): Promise<void> {
  const response = await fetch(`${base(agentId, runId)}/approve`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `${decision.toLowerCase()}-${planId}`,
    },
    body: JSON.stringify({ planId, decision }),
  });
  if (!response.ok) throw new Error(`Failed to ${decision.toLowerCase()} plan: ${response.status}`);
}

export async function cancelRun(agentId: string, runId: string): Promise<void> {
  const response = await fetch(`${base(agentId, runId)}/cancel`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `cancel-${runId}` },
  });
  if (!response.ok) throw new Error(`Failed to cancel run: ${response.status}`);
}
