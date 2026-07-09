import { useEffect, useMemo, useState } from 'react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { listUsageSummary, type UsageSummaryView } from '../api/admin';
import type { MeResponse } from '../auth';

function formatDate(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export default function AdminUsagePage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [rows, setRows] = useState<UsageSummaryView[]>([]);
  const [userId, setUserId] = useState('');
  const [metric, setMetric] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      setRows(await listUsageSummary({ userId, metric, from, to }));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const total = useMemo(() => rows.reduce((sum, row) => sum + row.totalValue, 0), [rows]);

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <div style={{ padding: '34px 40px', maxWidth: 1280 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 20, alignItems: 'flex-start', marginBottom: 22 }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '1.65rem', fontWeight: 700, color: '#0f172a' }}>Usage</h1>
          <p style={{ margin: '8px 0 0', color: '#64748b', lineHeight: 1.5, maxWidth: 760 }}>
            Review durable metering records for the current organization.
          </p>
        </div>
        <button type="button" onClick={() => void refresh()} disabled={loading} style={primaryButton}>
          {loading ? 'Refreshing' : 'Refresh'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2,minmax(180px,1fr))', gap: 12, marginBottom: 18 }}>
        <Summary label="Metric groups" value={rows.length} />
        <Summary label="Total value" value={total} />
      </div>

      <div style={filterPanel}>
        <input value={userId} onChange={e => setUserId(e.target.value)} placeholder="User UUID" style={inputStyle} />
        <input value={metric} onChange={e => setMetric(e.target.value)} placeholder="Metric, e.g. tokens_total" style={inputStyle} />
        <input value={from} onChange={e => setFrom(e.target.value)} placeholder="From ISO time" style={inputStyle} />
        <input value={to} onChange={e => setTo(e.target.value)} placeholder="To ISO time" style={inputStyle} />
      </div>

      {err && <div style={errorStyle}>{err}</div>}

      <div style={tablePanel}>
        <div style={{ display: 'grid', gridTemplateColumns: '220px 180px 120px 140px 230px 230px', minWidth: 1120, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#475569', fontSize: '0.76rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
          {['Metric', 'Model', 'Records', 'Total', 'First', 'Last'].map(h => <div key={h} style={headCell}>{h}</div>)}
        </div>
        {loading && <div style={emptyStyle}>Loading...</div>}
        {!loading && rows.length === 0 && <div style={emptyStyle}>No usage records match the current filters.</div>}
        {!loading && rows.map((row, idx) => (
          <div key={`${row.metric}-${row.model ?? 'none'}-${idx}`} style={{ display: 'grid', gridTemplateColumns: '220px 180px 120px 140px 230px 230px', minWidth: 1120, borderBottom: '1px solid #f1f5f9', color: '#334155', fontSize: '0.84rem' }}>
            <div style={bodyCell}>{row.metric}</div>
            <div style={bodyCell}>{row.model || '-'}</div>
            <div style={bodyCell}>{row.records}</div>
            <div style={{ ...bodyCell, fontWeight: 700, color: '#0f172a' }}>{row.totalValue}</div>
            <div style={bodyCell}>{formatDate(row.firstRecordedAt)}</div>
            <div style={bodyCell}>{formatDate(row.lastRecordedAt)}</div>
          </div>
        ))}
      </div>
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
const emptyStyle: React.CSSProperties = { padding: 18, color: '#64748b', fontSize: '0.9rem' };
