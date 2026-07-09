import { useEffect, useMemo, useState } from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import {
  listAdminUsers,
  listTierPolicies,
  updateAdminUser,
  type AdminUserView,
  type TierPolicyView,
} from '../api/admin';
import type { MeResponse } from '../auth';

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
      setDrafts(Object.fromEntries(userRows.map(u => [u.id, {
        displayName: u.displayName ?? '',
        role: u.role ?? 'member',
        tier: u.tier ?? 'standard',
      }])));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
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
      setNotice(`Updated ${updated.email}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingId(null);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => {
    const admins = users.filter(u => u.role === 'admin').length;
    return { users: users.length, admins };
  }, [users]);

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <div style={{ padding: '34px 40px', maxWidth: 1280 }}>
      <Header title="Users and quotas" onRefresh={() => void refresh()} loading={loading} />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,minmax(180px,1fr))', gap: 12, marginBottom: 18 }}>
        <Summary label="Users" value={totals.users} />
        <Summary label="Admins" value={totals.admins} />
        <Summary label="Tier policies" value={tiers.length} />
      </div>

      {err && <Banner tone="error">{err}</Banner>}
      {notice && <Banner tone="info">{notice}</Banner>}

      <section style={panelStyle}>
        <div style={{ ...sectionTitle, marginBottom: 12 }}>Tier policies</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(210px,1fr))', gap: 10 }}>
          {tiers.map(tier => (
            <div key={tier.tier} style={tierCardStyle}>
              <div style={{ fontWeight: 700, color: '#0f172a', marginBottom: 8 }}>{tier.tier}</div>
              <Metric label="agents" value={tier.maxAgents} />
              <Metric label="sandboxes" value={tier.maxSandboxes} />
              <Metric label="tokens/mo" value={tier.monthlyTokenQuota} />
              <Metric label="storage GB" value={tier.storageGb} />
            </div>
          ))}
          {!loading && tiers.length === 0 && <div style={emptyStyle}>No tier policies configured.</div>}
        </div>
      </section>

      <section style={panelStyle}>
        <div style={{ ...sectionTitle, marginBottom: 12 }}>Organization users</div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '260px 220px 130px 150px 190px 130px', minWidth: 1080, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#475569', fontSize: '0.76rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
            {['Email', 'Display name', 'Role', 'Tier', 'Created', 'Action'].map(h => <div key={h} style={headCell}>{h}</div>)}
          </div>
          {loading && <div style={emptyStyle}>Loading...</div>}
          {!loading && users.length === 0 && <div style={emptyStyle}>No users in this organization.</div>}
          {!loading && users.map(user => {
            const draft = drafts[user.id] ?? { displayName: '', role: user.role, tier: user.tier };
            const platformAdminUser = user.role === 'platform_admin';
            const canEdit = !platformAdminUser || me?.role === 'platform_admin';
            return (
              <div key={user.id} style={{ display: 'grid', gridTemplateColumns: '260px 220px 130px 150px 190px 130px', minWidth: 1080, borderBottom: '1px solid #f1f5f9', alignItems: 'center' }}>
                <div style={bodyCell}>
                  <div style={{ color: '#0f172a', fontWeight: 600 }}>{user.email}</div>
                  <div style={monoSmall} title={user.id}>{shortId(user.id)}</div>
                </div>
                <div style={bodyCell}>
                  <input
                    value={draft.displayName ?? ''}
                    onChange={e => setDrafts(d => ({ ...d, [user.id]: { ...draft, displayName: e.target.value } }))}
                    disabled={!canEdit}
                    style={inputStyle}
                  />
                </div>
                <div style={bodyCell}>
                  <select
                    value={draft.role}
                    onChange={e => setDrafts(d => ({ ...d, [user.id]: { ...draft, role: e.target.value } }))}
                    disabled={!canEdit || platformAdminUser}
                    style={inputStyle}
                  >
                    {platformAdminUser && <option value="platform_admin">platform_admin</option>}
                    <option value="member">member</option>
                    <option value="admin">admin</option>
                  </select>
                </div>
                <div style={bodyCell}>
                  <select
                    value={draft.tier}
                    onChange={e => setDrafts(d => ({ ...d, [user.id]: { ...draft, tier: e.target.value } }))}
                    disabled={!canEdit}
                    style={inputStyle}
                  >
                    {tiers.map(tier => <option key={tier.tier} value={tier.tier}>{tier.tier}</option>)}
                  </select>
                </div>
                <div style={bodyCell}>{formatDate(user.createdAt)}</div>
                <div style={bodyCell}>
                  <button type="button" onClick={() => void save(user)} disabled={!canEdit || savingId === user.id} style={smallButton}>
                    {!canEdit ? 'Restricted' : savingId === user.id ? 'Saving' : 'Save'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </section>
    </div>
  );
}

function Header({ title, onRefresh, loading }: { title: string; onRefresh: () => void; loading: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, alignItems: 'flex-start', marginBottom: 22 }}>
      <div>
        <h1 style={{ margin: 0, fontSize: '1.65rem', fontWeight: 700, color: '#0f172a' }}>{title}</h1>
        <p style={{ margin: '8px 0 0', color: '#64748b', lineHeight: 1.5, maxWidth: 760 }}>
          Manage organization users, roles, and the tier policy each user consumes.
        </p>
      </div>
      <button type="button" onClick={onRefresh} disabled={loading} style={primaryButton}>
        {loading ? 'Refreshing' : 'Refresh'}
      </button>
    </div>
  );
}

function Summary({ label, value }: { label: string; value: number }) {
  return (
    <div style={{ background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 10, padding: '14px 16px' }}>
      <div style={{ color: '#64748b', fontSize: '0.78rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ color: '#0f172a', fontSize: '1.55rem', fontWeight: 700, marginTop: 4 }}>{value}</div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value?: number | null }) {
  return <div style={{ color: '#475569', fontSize: '0.82rem', marginTop: 4 }}>{label}: <b>{value ?? '-'}</b></div>;
}

function Banner({ tone, children }: { tone: 'error' | 'info'; children: React.ReactNode }) {
  const error = tone === 'error';
  return (
    <div style={{ color: error ? '#b91c1c' : '#0369a1', background: error ? '#fef2f2' : '#eff6ff', border: `1px solid ${error ? '#fecaca' : '#bfdbfe'}`, borderRadius: 8, padding: '10px 12px', marginBottom: 14 }}>
      {children}
    </div>
  );
}

const panelStyle: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 10,
  padding: 16,
  marginBottom: 18,
};

const tierCardStyle: React.CSSProperties = {
  border: '1px solid #e2e8f0',
  borderRadius: 8,
  padding: 12,
  background: '#f8fafc',
};

const sectionTitle: React.CSSProperties = {
  color: '#0f172a',
  fontSize: '1rem',
  fontWeight: 700,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 34,
  border: '1px solid #cbd5e1',
  borderRadius: 8,
  padding: '7px 9px',
  background: '#ffffff',
  color: '#0f172a',
  fontSize: '0.84rem',
  boxSizing: 'border-box',
};

const primaryButton: React.CSSProperties = {
  background: '#0f172a',
  color: '#ffffff',
  border: 'none',
  borderRadius: 9,
  padding: '10px 16px',
  fontWeight: 600,
  cursor: 'pointer',
};

const smallButton: React.CSSProperties = {
  background: '#0f172a',
  color: '#ffffff',
  border: 'none',
  borderRadius: 8,
  padding: '8px 12px',
  fontSize: '0.82rem',
  fontWeight: 600,
  cursor: 'pointer',
};

const headCell: React.CSSProperties = { padding: '11px 12px' };
const bodyCell: React.CSSProperties = { padding: '12px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' };
const monoSmall: React.CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace', color: '#64748b', fontSize: '0.75rem', marginTop: 4 };
const emptyStyle: React.CSSProperties = { padding: 18, color: '#64748b', fontSize: '0.9rem' };
