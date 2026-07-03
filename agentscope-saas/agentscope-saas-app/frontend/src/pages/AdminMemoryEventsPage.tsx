import { useEffect, useMemo, useState } from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { listMemoryEvents, type MemoryEventView } from '../api/admin';
import type { MeResponse } from '../auth';

const STATUSES = ['', 'pending', 'syncing', 'synced', 'failed'];
const TABLE_GRID = '120px 110px 150px 145px 170px 120px 170px 220px';

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

function statusStyle(status: string): React.CSSProperties {
  if (status === 'synced') {
    return { background: '#ecfdf5', color: '#047857', border: '1px solid #a7f3d0' };
  }
  if (status === 'failed') {
    return { background: '#fef2f2', color: '#b91c1c', border: '1px solid #fecaca' };
  }
  if (status === 'syncing') {
    return { background: '#eff6ff', color: '#1d4ed8', border: '1px solid #bfdbfe' };
  }
  return { background: '#fff7ed', color: '#c2410c', border: '1px solid #fed7aa' };
}

export default function AdminMemoryEventsPage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [rows, setRows] = useState<MemoryEventView[]>([]);
  const [userId, setUserId] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [syncStatus, setSyncStatus] = useState('');
  const [limit, setLimit] = useState(100);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const list = await listMemoryEvents({ userId, sessionId, syncStatus, limit });
      setRows(list);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => ({
    pending: rows.filter(r => r.syncStatus === 'pending').length,
    failed: rows.filter(r => r.syncStatus === 'failed').length,
    synced: rows.filter(r => r.syncStatus === 'synced').length,
  }), [rows]);

  if (me?.role !== 'admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <div style={{ padding: '34px 40px', maxWidth: 1280 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, alignItems: 'flex-start', marginBottom: 22 }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '1.65rem', fontWeight: 700, color: '#0f172a' }}>
            Memory events
          </h1>
          <p style={{ margin: '8px 0 0', color: '#64748b', lineHeight: 1.5, maxWidth: 720 }}>
            Inspect durable long-term-memory ledger rows and Mem0 projection status for the current organization.
          </p>
        </div>
        <button type="button" onClick={() => void refresh()} disabled={loading} style={primaryButton}>
          {loading ? 'Refreshing' : 'Refresh'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,minmax(160px,1fr))', gap: 12, marginBottom: 18 }}>
        <Summary label="Pending" value={totals.pending} danger={totals.pending > 0} />
        <Summary label="Failed" value={totals.failed} danger={totals.failed > 0} />
        <Summary label="Synced" value={totals.synced} />
      </div>

      <div style={filters}>
        <input value={userId} onChange={e => setUserId(e.target.value)} placeholder="Filter by user UUID" style={inputStyle} />
        <input value={sessionId} onChange={e => setSessionId(e.target.value)} placeholder="Filter by session" style={inputStyle} />
        <select value={syncStatus} onChange={e => setSyncStatus(e.target.value)} style={inputStyle}>
          {STATUSES.map(v => <option key={v || 'all'} value={v}>{v || 'all status'}</option>)}
        </select>
        <input
          type="number"
          min={1}
          max={500}
          value={limit}
          onChange={e => setLimit(Math.max(1, Math.min(500, Number(e.target.value) || 100)))}
          style={inputStyle}
        />
      </div>

      {err && <div style={{ color: '#b91c1c', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 8, padding: '10px 12px', marginBottom: 14 }}>{err}</div>}

      <div style={{ background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 10, overflowX: 'auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: TABLE_GRID, minWidth: 1205, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#475569', fontSize: '0.76rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {['Status', 'Source', 'User', 'Agent', 'Session', 'Attempts', 'Created', 'Error'].map(h => (
            <div key={h} style={headCell}>{h}</div>
          ))}
        </div>
        {loading && <div style={emptyStyle}>Loading...</div>}
        {!loading && rows.length === 0 && <div style={emptyStyle}>No memory events match the current filters.</div>}
        {!loading && rows.map(row => (
          <div key={row.id} style={{ display: 'grid', gridTemplateColumns: TABLE_GRID, minWidth: 1205, borderBottom: '1px solid #f1f5f9', color: '#334155', fontSize: '0.84rem' }}>
            <div style={bodyCell}>
              <span style={{ ...statusBadge, ...statusStyle(row.syncStatus) }}>{row.syncStatus}</span>
            </div>
            <div style={bodyCell}>{row.source}/{row.eventType}</div>
            <div style={monoCell} title={row.userId}>{shortId(row.userId)}</div>
            <div style={monoCell} title={row.agentId ?? undefined}>{shortId(row.agentId)}</div>
            <div style={monoCell} title={row.sessionId ?? undefined}>{shortId(row.sessionId)}</div>
            <div style={bodyCell}>{row.syncAttempts}</div>
            <div style={bodyCell}>{formatDate(row.createdAt)}</div>
            <div style={bodyCell} title={row.lastError ?? undefined}>{row.lastError ?? '-'}</div>
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

const filters: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))',
  gap: 10,
  alignItems: 'center',
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 10,
  padding: 14,
  marginBottom: 18,
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

const inputStyle: React.CSSProperties = {
  minHeight: 36,
  border: '1px solid #cbd5e1',
  borderRadius: 8,
  padding: '8px 10px',
  background: '#ffffff',
  color: '#0f172a',
  fontSize: '0.86rem',
};

const headCell: React.CSSProperties = { padding: '11px 12px' };

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
