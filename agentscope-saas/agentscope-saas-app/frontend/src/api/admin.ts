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

export interface AdminUserView {
  id: string;
  orgId: string;
  email: string;
  displayName?: string | null;
  role: string;
  tier: string;
  createdAt?: string | null;
}

export interface UpdateAdminUserRequest {
  displayName?: string | null;
  role?: string | null;
  tier?: string | null;
}

export async function listAdminUsers(limit = 100): Promise<AdminUserView[]> {
  const params = new URLSearchParams();
  params.set('limit', String(limit));
  const res = await fetch(`/api/admin/users?${params.toString()}`);
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to list users: ${res.status}`);
  }
  return res.json();
}

export async function updateAdminUser(
  userId: string,
  body: UpdateAdminUserRequest,
): Promise<AdminUserView> {
  const res = await fetch(`/api/admin/users/${encodeURIComponent(userId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to update user: ${res.status}`);
  }
  return res.json();
}

export interface TierPolicyView {
  tier: string;
  maxAgents?: number | null;
  maxSandboxes?: number | null;
  monthlyTokenQuota?: number | null;
  storageGb?: number | null;
  idleTtlSeconds?: number | null;
}

export async function listTierPolicies(): Promise<TierPolicyView[]> {
  const res = await fetch('/api/admin/tier-policies');
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to list tier policies: ${res.status}`);
  }
  return res.json();
}

export interface UsageSummaryView {
  metric: string;
  model?: string | null;
  records: number;
  totalValue: number;
  firstRecordedAt?: string | null;
  lastRecordedAt?: string | null;
}

export interface UsageFilters {
  userId?: string;
  metric?: string;
  from?: string;
  to?: string;
}

export async function listUsageSummary(filters: UsageFilters = {}): Promise<UsageSummaryView[]> {
  const params = new URLSearchParams();
  if (filters.userId?.trim()) params.set('userId', filters.userId.trim());
  if (filters.metric?.trim()) params.set('metric', filters.metric.trim());
  if (filters.from?.trim()) params.set('from', filters.from.trim());
  if (filters.to?.trim()) params.set('to', filters.to.trim());
  const suffix = params.size > 0 ? `?${params.toString()}` : '';
  const res = await fetch(`/api/admin/usage/summary${suffix}`);
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to list usage summary: ${res.status}`);
  }
  return res.json();
}

export interface AuditLogView {
  id: number;
  orgId: string;
  actor?: string | null;
  action?: string | null;
  resource?: string | null;
  detail?: string | null;
  ts?: string | null;
}

export interface AuditFilters {
  actor?: string;
  action?: string;
  resourcePrefix?: string;
  limit?: number;
}

export async function listAuditLogs(filters: AuditFilters = {}): Promise<AuditLogView[]> {
  const params = new URLSearchParams();
  if (filters.actor?.trim()) params.set('actor', filters.actor.trim());
  if (filters.action?.trim()) params.set('action', filters.action.trim());
  if (filters.resourcePrefix?.trim()) params.set('resourcePrefix', filters.resourcePrefix.trim());
  if (filters.limit && filters.limit > 0) params.set('limit', String(filters.limit));
  const suffix = params.size > 0 ? `?${params.toString()}` : '';
  const res = await fetch(`/api/admin/audit${suffix}`);
  if (!res.ok) {
    const msg = await res.text().catch(() => `${res.status}`);
    throw new Error(msg || `Failed to list audit logs: ${res.status}`);
  }
  return res.json();
}
