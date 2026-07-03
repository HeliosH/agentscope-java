export interface SandboxView {
  id: string;
  orgId: string;
  userId: string;
  agentId?: string | null;
  sessionId?: string | null;
  sandboxType?: string | null;
  externalId?: string | null;
  status: string;
  createdAt?: string | null;
  lastUsedAt?: string | null;
  expiresAt?: string | null;
  backendReleaseStatus?: string | null;
  backendReleaseAttempts: number;
  backendReleasedAt?: string | null;
  backendReleaseError?: string | null;
  expired: boolean;
}

export interface SandboxActionView {
  id: string;
  orgId: string;
  userId: string;
  sandboxType?: string | null;
  externalId?: string | null;
  previousStatus: string;
  status: string;
  changed: boolean;
  backendTerminationStatus: string;
  backendTerminationMessage?: string | null;
}

export interface SandboxFilters {
  userId?: string;
  status?: string;
  sandboxType?: string;
  expiredOnly?: boolean;
  limit?: number;
}

export async function listSandboxes(filters: SandboxFilters = {}): Promise<SandboxView[]> {
  const params = new URLSearchParams();
  if (filters.userId?.trim()) params.set('userId', filters.userId.trim());
  if (filters.status?.trim()) params.set('status', filters.status.trim());
  if (filters.sandboxType?.trim()) params.set('sandboxType', filters.sandboxType.trim());
  if (filters.expiredOnly) params.set('expiredOnly', 'true');
  if (filters.limit && filters.limit > 0) params.set('limit', String(filters.limit));

  const suffix = params.size > 0 ? `?${params.toString()}` : '';
  const res = await fetch(`/api/admin/sandboxes${suffix}`);
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to list sandboxes: ${res.status}`);
  }
  return res.json();
}

export async function forceEvictSandbox(
  sandboxId: string,
  reason = 'admin console',
  terminateBackend = true,
): Promise<SandboxActionView> {
  const res = await fetch(`/api/admin/sandboxes/${encodeURIComponent(sandboxId)}/force-evict`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason, terminateBackend }),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to force-evict sandbox: ${res.status}`);
  }
  return res.json();
}

export interface MemoryEventView {
  id: string;
  orgId: string;
  userId: string;
  agentId?: string | null;
  sessionId?: string | null;
  source: string;
  eventType: string;
  syncStatus: string;
  syncAttempts: number;
  lastError?: string | null;
  syncedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  contentJson?: string | null;
  metadataJson?: string | null;
}

export interface MemoryEventFilters {
  userId?: string;
  sessionId?: string;
  syncStatus?: string;
  limit?: number;
}

export async function listMemoryEvents(
  filters: MemoryEventFilters = {},
): Promise<MemoryEventView[]> {
  const params = new URLSearchParams();
  if (filters.userId?.trim()) params.set('userId', filters.userId.trim());
  if (filters.sessionId?.trim()) params.set('sessionId', filters.sessionId.trim());
  if (filters.syncStatus?.trim()) params.set('syncStatus', filters.syncStatus.trim());
  if (filters.limit && filters.limit > 0) params.set('limit', String(filters.limit));

  const suffix = params.size > 0 ? `?${params.toString()}` : '';
  const res = await fetch(`/api/admin/memory-events${suffix}`);
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to list memory events: ${res.status}`);
  }
  return res.json();
}
