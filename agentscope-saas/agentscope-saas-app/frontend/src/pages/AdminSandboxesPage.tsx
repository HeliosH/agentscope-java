import { useEffect, useMemo, useState } from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { forceEvictSandbox, listSandboxes, type SandboxView } from '../api/admin';
import type { MeResponse } from '../auth';

const STATUSES = ['', 'active', 'released', 'evicted'];
const TYPES = ['', 'e2b', 'opensandbox', 'cube', 'docker'];
const TABLE_GRID = '120px 115px 150px 145px 170px 145px 125px 180px 120px';

function formatDate(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

function shortId(value?: string | null): string {
  if (!value) return '-';
  return value.length > 12 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value;
}

function statusStyle(row: SandboxView): React.CSSProperties {
  if (row.expired) {
    return { background: '#fef2f2', color: '#b91c1c', border: '1px solid #fecaca' };
  }
  if (row.status === 'active') {
    return { background: '#ecfdf5', color: '#047857', border: '1px solid #a7f3d0' };
  }
  if (row.status === 'released') {
    return { background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0' };
  }
  return { background: '#fff7ed', color: '#c2410c', border: '1px solid #fed7aa' };
}

function backendStyle(status?: string | null): React.CSSProperties {
  if (status === 'succeeded') {
    return { background: '#ecfdf5', color: '#047857', border: '1px solid #a7f3d0' };
  }
  if (status === 'failed') {
    return { background: '#fef2f2', color: '#b91c1c', border: '1px solid #fecaca' };
  }
  if (status === 'terminating' || status === 'pending') {
    return { background: '#eff6ff', color: '#1d4ed8', border: '1px solid #bfdbfe' };
  }
  return { background: '#f8fafc', color: '#64748b', border: '1px solid #e2e8f0' };
}

export default function AdminSandboxesPage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [rows, setRows] = useState<SandboxView[]>([]);
  const [status, setStatus] = useState('');
  const [sandboxType, setSandboxType] = useState('');
  const [userId, setUserId] = useState('');
  const [expiredOnly, setExpiredOnly] = useState(false);
  const [limit, setLimit] = useState(100);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [action, setAction] = useState<string | null>(null);
  const [actingId, setActingId] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const list = await listSandboxes({ status, sandboxType, userId, expiredOnly, limit });
      setRows(list);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  async function forceEvict(row: SandboxView) {
    setActingId(row.id);
    setErr(null);
    setAction(null);
    try {
      const result = await forceEvictSandbox(row.id, 'admin console force evict', true);
      setAction(`Force evict ${shortId(row.id)}: ${result.backendTerminationStatus}`);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setActingId(null);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => {
    const active = rows.filter(r => r.status === 'active').length;
    const expired = rows.filter(r => r.expired).length;
    const released = rows.filter(r => r.status === 'released').length;
    return { active, expired, released };
  }, [rows]);

  if (me?.role !== 'admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <div style={{ padding: '34px 40px', maxWidth: 1280 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, alignItems: 'flex-start', marginBottom: 22 }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '1.65rem', fontWeight: 700, color: '#0f172a' }}>
            Sandbox inventory
          </h1>
          <p style={{ margin: '8px 0 0', color: '#64748b', lineHeight: 1.5, maxWidth: 720 }}>
            Track active, released and expired runtime rows for the current organization.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void refresh()}
          disabled={loading}
          style={{
            background: loading ? '#cbd5e1' : '#0f172a',
            color: '#ffffff',
            border: 'none',
            borderRadius: 9,
            padding: '10px 16px',
            fontWeight: 600,
            cursor: loading ? 'not-allowed' : 'pointer',
          }}
        >
          {loading ? 'Refreshing' : 'Refresh'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,minmax(160px,1fr))', gap: 12, marginBottom: 18 }}>
        <Summary label="Active" value={totals.active} />
        <Summary label="Expired active" value={totals.expired} danger={totals.expired > 0} />
        <Summary label="Released" value={totals.released} />
      </div>

      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))',
        gap: 10,
        alignItems: 'center',
        background: '#ffffff',
        border: '1px solid #e2e8f0',
        borderRadius: 10,
        padding: 14,
        marginBottom: 18,
      }}>
        <input
          value={userId}
          onChange={e => setUserId(e.target.value)}
          placeholder="Filter by user UUID"
          style={inputStyle}
        />
        <select value={status} onChange={e => setStatus(e.target.value)} style={inputStyle}>
          {STATUSES.map(v => <option key={v || 'all'} value={v}>{v || 'all status'}</option>)}
        </select>
        <select value={sandboxType} onChange={e => setSandboxType(e.target.value)} style={inputStyle}>
          {TYPES.map(v => <option key={v || 'all'} value={v}>{v || 'all types'}</option>)}
        </select>
        <input
          type="number"
          min={1}
          max={500}
          value={limit}
          onChange={e => setLimit(Math.max(1, Math.min(500, Number(e.target.value) || 100)))}
          style={inputStyle}
        />
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, color: '#475569', fontSize: '0.86rem' }}>
          <input type="checkbox" checked={expiredOnly} onChange={e => setExpiredOnly(e.target.checked)} />
          Expired only
        </label>
      </div>

      {err && <div style={{ color: '#b91c1c', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 8, padding: '10px 12px', marginBottom: 14 }}>{err}</div>}
      {action && <div style={{ color: '#0369a1', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: 8, padding: '10px 12px', marginBottom: 14 }}>{action}</div>}

      <div style={{ background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 10, overflowX: 'auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: TABLE_GRID, minWidth: 1250, gap: 0, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#475569', fontSize: '0.76rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {['Status', 'Type', 'User', 'External', 'Backend', 'Last used', 'Attempts', 'Error', 'Action'].map(h => (
            <div key={h} style={headCell}>{h}</div>
          ))}
        </div>
        {loading && <div style={emptyStyle}>Loading...</div>}
        {!loading && rows.length === 0 && <div style={emptyStyle}>No sandbox rows match the current filters.</div>}
        {!loading && rows.map(row => (
          <div
            key={row.id}
            style={{ display: 'grid', gridTemplateColumns: TABLE_GRID, minWidth: 1250, borderBottom: '1px solid #f1f5f9', color: '#334155', fontSize: '0.84rem' }}
          >
            <div style={bodyCell}>
              <span style={{ ...statusBadge, ...statusStyle(row) }}>{row.expired ? 'expired' : row.status}</span>
            </div>
            <div style={bodyCell}>{row.sandboxType ?? '-'}</div>
            <div style={monoCell} title={row.userId}>{shortId(row.userId)}</div>
            <div style={monoCell} title={row.externalId ?? undefined}>{shortId(row.externalId)}</div>
            <div style={bodyCell} title={row.backendReleasedAt ? `released ${formatDate(row.backendReleasedAt)}` : undefined}>
              <span style={{ ...statusBadge, ...backendStyle(row.backendReleaseStatus) }}>
                {row.backendReleaseStatus ?? 'unknown'}
              </span>
            </div>
            <div style={bodyCell}>{formatDate(row.lastUsedAt)}</div>
            <div style={bodyCell}>{row.backendReleaseAttempts ?? 0}</div>
            <div style={bodyCell} title={row.backendReleaseError ?? undefined}>{row.backendReleaseError ?? '-'}</div>
            <div style={bodyCell}>
              <button
                type="button"
                disabled={actingId === row.id}
                onClick={() => void forceEvict(row)}
                style={smallButton}
              >
                {actingId === row.id ? 'Working' : 'Evict'}
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function Summary({ label, value, danger = false }: { label: string; value: number; danger?: boolean }) {
  return (
    <div style={{
      background: '#ffffff',
      border: `1px solid ${danger ? '#fecaca' : '#e2e8f0'}`,
      borderRadius: 10,
      padding: '14px 16px',
    }}>
      <div style={{ color: '#64748b', fontSize: '0.78rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ color: danger ? '#b91c1c' : '#0f172a', fontSize: '1.55rem', fontWeight: 700, marginTop: 4 }}>{value}</div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  minHeight: 36,
  border: '1px solid #cbd5e1',
  borderRadius: 8,
  padding: '8px 10px',
  background: '#ffffff',
  color: '#0f172a',
  fontSize: '0.86rem',
};

const headCell: React.CSSProperties = {
  padding: '11px 12px',
};

const bodyCell: React.CSSProperties = {
  padding: '12px',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const monoCell: React.CSSProperties = {
  ...bodyCell,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  color: '#475569',
};

const statusBadge: React.CSSProperties = {
  display: 'inline-flex',
  borderRadius: 999,
  padding: '3px 9px',
  fontSize: '0.74rem',
  fontWeight: 700,
};

const emptyStyle: React.CSSProperties = {
  padding: '28px 16px',
  color: '#94a3b8',
  fontSize: '0.9rem',
};

const smallButton: React.CSSProperties = {
  minHeight: 30,
  border: '1px solid #cbd5e1',
  borderRadius: 8,
  background: '#ffffff',
  color: '#0f172a',
  padding: '5px 10px',
  fontSize: '0.8rem',
  fontWeight: 700,
  cursor: 'pointer',
};
