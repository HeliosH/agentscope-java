import { useEffect, useMemo, useState } from 'react';
import { Save, Search, Users } from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import {
  listAdminUsers,
  listTierPolicies,
  updateAdminUser,
  type AdminUserView,
  type TierPolicyView,
} from '../api/admin';
import type { MeResponse } from '../auth';
import {
  DataPanel,
  EmptyState,
  ManagementHeader,
  ManagementPage,
  MetricStrip,
  Notice,
  RefreshButton,
  StatusBadge,
} from '../components/ManagementUI';

type Draft = Pick<AdminUserView, 'displayName' | 'role' | 'tier'>;

function formatDate(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function shortId(value?: string | null): string {
  if (!value) return '-';
  return value.length > 12 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value;
}

export default function AdminUsersPage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [tiers, setTiers] = useState<TierPolicyView[]>([]);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const [userRows, tierRows] = await Promise.all([listAdminUsers(200), listTierPolicies()]);
      setUsers(userRows);
      setTiers(tierRows);
      setDrafts(Object.fromEntries(userRows.map(user => [user.id, {
        displayName: user.displayName ?? '',
        role: user.role ?? 'member',
        tier: user.tier ?? 'standard',
      }])));
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  async function save(user: AdminUserView) {
    const draft = drafts[user.id];
    if (!draft) return;
    setSavingId(user.id);
    setErr(null);
    setNotice(null);
    try {
      const updated = await updateAdminUser(user.id, draft);
      setUsers(rows => rows.map(row => (row.id === user.id ? updated : row)));
      setDrafts(current => ({ ...current, [user.id]: {
        displayName: updated.displayName ?? '',
        role: updated.role,
        tier: updated.tier,
      } }));
      setNotice(`Updated ${updated.email}`);
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSavingId(null);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => ({
    users: users.length,
    admins: users.filter(user => user.role === 'admin' || user.role === 'platform_admin').length,
    tiers: new Set(users.map(user => user.tier)).size,
  }), [users]);

  const visibleUsers = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return users;
    return users.filter(user => [user.email, user.displayName, user.role, user.tier, user.id]
      .some(value => value?.toLowerCase().includes(needle)));
  }, [query, users]);

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <ManagementPage admin>
      <ManagementHeader
        icon={Users}
        title="Users & access"
        description="Manage organization members, administrative roles, and quota tiers."
        actions={<RefreshButton loading={loading} onClick={() => void refresh()} />}
      />

      <MetricStrip items={[
        { label: 'Members', value: totals.users, detail: 'Organization users' },
        { label: 'Administrators', value: totals.admins, tone: totals.admins ? 'default' : 'warning' },
        { label: 'Assigned tiers', value: totals.tiers },
        { label: 'Tier policies', value: tiers.length },
      ]} />

      {err && <Notice tone="error">{err}</Notice>}
      {notice && <Notice tone="success">{notice}</Notice>}

      <DataPanel title="Tier policies">
        {tiers.length > 0 ? (
          <div className="tier-grid">
            {tiers.map(tier => (
              <div className="tier-item" key={tier.tier}>
                <div className="tier-item__title">{tier.tier}</div>
                <div className="tier-item__metrics">
                  <TierMetric label="Agents" value={tier.maxAgents} />
                  <TierMetric label="Sandboxes" value={tier.maxSandboxes} />
                  <TierMetric label="Tokens / month" value={tier.monthlyTokenQuota} />
                  <TierMetric label="Storage GB" value={tier.storageGb} />
                </div>
              </div>
            ))}
          </div>
        ) : !loading ? <EmptyState>No tier policies configured.</EmptyState> : null}
      </DataPanel>

      <DataPanel
        title={`Organization users · ${visibleUsers.length}`}
        actions={(
          <label className="compact-search">
            <Search size={14} />
            <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search users" />
          </label>
        )}
      >
        <div className="data-table-wrap">
          <table className="data-table data-table--wide">
            <thead>
              <tr>
                <th>User</th>
                <th>Display name</th>
                <th>Role</th>
                <th>Tier</th>
                <th>Created</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {visibleUsers.map(user => {
                const draft = drafts[user.id] ?? { displayName: '', role: user.role, tier: user.tier };
                const platformAdmin = user.role === 'platform_admin';
                const canEdit = !platformAdmin || me?.role === 'platform_admin';
                const dirty = draft.displayName !== (user.displayName ?? '') || draft.role !== user.role || draft.tier !== user.tier;
                return (
                  <tr key={user.id}>
                    <td>
                      <strong>{user.email}</strong>
                      <div className="mono-text" title={user.id}>{shortId(user.id)}</div>
                    </td>
                    <td>
                      <input
                        className="management-input"
                        value={draft.displayName ?? ''}
                        onChange={event => setDrafts(current => ({ ...current, [user.id]: { ...draft, displayName: event.target.value } }))}
                        disabled={!canEdit}
                      />
                    </td>
                    <td>
                      <select
                        className="management-select"
                        value={draft.role}
                        onChange={event => setDrafts(current => ({ ...current, [user.id]: { ...draft, role: event.target.value } }))}
                        disabled={!canEdit || platformAdmin}
                      >
                        {platformAdmin && <option value="platform_admin">platform_admin</option>}
                        <option value="member">member</option>
                        <option value="admin">admin</option>
                      </select>
                    </td>
                    <td>
                      <select
                        className="management-select"
                        value={draft.tier}
                        onChange={event => setDrafts(current => ({ ...current, [user.id]: { ...draft, tier: event.target.value } }))}
                        disabled={!canEdit}
                      >
                        {tiers.map(tier => <option key={tier.tier} value={tier.tier}>{tier.tier}</option>)}
                      </select>
                    </td>
                    <td>{formatDate(user.createdAt)}</td>
                    <td>
                      {canEdit ? (
                        <button
                          className="quiet-button"
                          type="button"
                          onClick={() => void save(user)}
                          disabled={!dirty || savingId === user.id}
                        >
                          <Save size={13} />
                          {savingId === user.id ? 'Saving' : 'Save'}
                        </button>
                      ) : <StatusBadge status="restricted" />}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {loading && <EmptyState>Loading users...</EmptyState>}
          {!loading && visibleUsers.length === 0 && <EmptyState>No users match the current search.</EmptyState>}
        </div>
      </DataPanel>
    </ManagementPage>
  );
}

function TierMetric({ label, value }: { label: string; value?: number | null }) {
  return (
    <div className="tier-item__metric">
      <span>{label}</span>
      <strong>{value?.toLocaleString() ?? '-'}</strong>
    </div>
  );
}
