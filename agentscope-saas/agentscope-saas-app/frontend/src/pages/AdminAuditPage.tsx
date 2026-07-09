import { useEffect, useState } from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { listAuditLogs, type AuditLogView } from '../api/admin';
import type { MeResponse } from '../auth';

function formatDate(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function shortId(value?: string | null): string {
  if (!value) return '-';
  return value.length > 12 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value;
}

function detailPreview(value?: string | null): string {
  if (!value) return '-';
  try {
    return JSON.stringify(JSON.parse(value));
  } catch {
    return value;
  }
}

export default function AdminAuditPage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [rows, setRows] = useState<AuditLogView[]>([]);
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [resourcePrefix, setResourcePrefix] = useState('');
  const [limit, setLimit] = useState(100);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      setRows(await listAuditLogs({ actor, action, resourcePrefix, limit }));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <div style={{ padding: '34px 40px', maxWidth: 1320 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, alignItems: 'flex-start', marginBottom: 22 }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '1.65rem', fontWeight: 700, color: '#0f172a' }}>Audit log</h1>
          <p style={{ margin: '8px 0 0', color: '#64748b', lineHeight: 1.5, maxWidth: 760 }}>
            Review administrative and security-sensitive changes for the current organization.
          </p>
        </div>
        <button type="button" onClick={() => void refresh()} disabled={loading} style={primaryButton}>
          {loading ? 'Refreshing' : 'Refresh'}
        </button>
      </div>

      <div style={filterPanel}>
        <input value={actor} onChange={e => setActor(e.target.value)} placeholder="Actor UUID" style={inputStyle} />
        <input value={action} onChange={e => setAction(e.target.value)} placeholder="Action, e.g. admin.user.update" style={inputStyle} />
        <input value={resourcePrefix} onChange={e => setResourcePrefix(e.target.value)} placeholder="Resource prefix" style={inputStyle} />
        <input
          type="number"
          min={1}
          max={500}
          value={limit}
          onChange={e => setLimit(Math.max(1, Math.min(500, Number(e.target.value) || 100)))}
          style={inputStyle}
        />
      </div>

      {err && <div style={errorStyle}>{err}</div>}

      <div style={tablePanel}>
        <div style={{ display: 'grid', gridTemplateColumns: '90px 170px 220px 260px 360px 210px', minWidth: 1310, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#475569', fontSize: '0.76rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {['ID', 'Actor', 'Action', 'Resource', 'Detail', 'Time'].map(h => <div key={h} style={headCell}>{h}</div>)}
        </div>
        {loading && <div style={emptyStyle}>Loading...</div>}
        {!loading && rows.length === 0 && <div style={emptyStyle}>No audit records match the current filters.</div>}
        {!loading && rows.map(row => (
          <div key={row.id} style={{ display: 'grid', gridTemplateColumns: '90px 170px 220px 260px 360px 210px', minWidth: 1310, borderBottom: '1px solid #f1f5f9', color: '#334155', fontSize: '0.84rem' }}>
            <div style={bodyCell}>{row.id}</div>
            <div style={monoCell} title={row.actor ?? undefined}>{shortId(row.actor)}</div>
            <div style={bodyCell}>{row.action ?? '-'}</div>
            <div style={monoCell} title={row.resource ?? undefined}>{row.resource ?? '-'}</div>
            <div style={bodyCell} title={detailPreview(row.detail)}>{detailPreview(row.detail)}</div>
            <div style={bodyCell}>{formatDate(row.ts)}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

const primaryButton: React.CSSProperties = {
  background: '#0f172a',
  color: '#ffffff',
  border: 'none',
  borderRadius: 9,
  padding: '10px 16px',
  fontWeight: 600,
  cursor: 'pointer',
};

const filterPanel: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))',
  gap: 10,
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 10,
  padding: 14,
  marginBottom: 18,
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

const tablePanel: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 10,
  overflowX: 'auto',
};

const errorStyle: React.CSSProperties = {
  color: '#b91c1c',
  background: '#fef2f2',
  border: '1px solid #fecaca',
  borderRadius: 8,
  padding: '10px 12px',
  marginBottom: 14,
};

const headCell: React.CSSProperties = { padding: '11px 12px' };
const bodyCell: React.CSSProperties = { padding: '12px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' };
const monoCell: React.CSSProperties = { ...bodyCell, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace', color: '#475569' };
const emptyStyle: React.CSSProperties = { padding: 18, color: '#64748b', fontSize: '0.9rem' };
