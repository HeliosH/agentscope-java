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
  expired: boolean;
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
