import { useEffect, useMemo, useState } from 'react';
import { Gauge } from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { listUsageSummary, type UsageSummaryView } from '../api/admin';
import type { MeResponse } from '../auth';
import {
  DataPanel,
  EmptyState,
  Field,
  FilterBar,
  ManagementHeader,
  ManagementPage,
  MetricStrip,
  Notice,
  RefreshButton,
} from '../components/ManagementUI';

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
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => ({
    value: rows.reduce((sum, row) => sum + row.totalValue, 0),
    records: rows.reduce((sum, row) => sum + row.records, 0),
    models: new Set(rows.map(row => row.model).filter(Boolean)).size,
  }), [rows]);

  function clearFilters() {
    setUserId('');
    setMetric('');
    setFrom('');
    setTo('');
  }

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <ManagementPage admin>
      <ManagementHeader
        icon={Gauge}
        title="Usage & metering"
        description="Review durable consumption records by metric, model, user, and time range."
        actions={<RefreshButton loading={loading} onClick={() => void refresh()} />}
      />

      <MetricStrip items={[
        { label: 'Metric groups', value: rows.length },
        { label: 'Records', value: totals.records.toLocaleString() },
        { label: 'Total value', value: totals.value.toLocaleString() },
        { label: 'Models', value: totals.models },
      ]} />

      <FilterBar actions={(
        <>
          <button className="quiet-button" type="button" onClick={clearFilters}>Clear</button>
          <button className="primary-button" type="button" disabled={loading} onClick={() => void refresh()}>Apply</button>
        </>
      )}>
        <Field label="User ID">
          <input className="management-input" value={userId} onChange={event => setUserId(event.target.value)} placeholder="User UUID" />
        </Field>
        <Field label="Metric">
          <input className="management-input" value={metric} onChange={event => setMetric(event.target.value)} placeholder="tokens_total" />
        </Field>
        <Field label="From">
          <input className="management-input" type="text" value={from} onChange={event => setFrom(event.target.value)} placeholder="2026-08-01T00:00:00Z" />
        </Field>
        <Field label="To">
          <input className="management-input" type="text" value={to} onChange={event => setTo(event.target.value)} placeholder="2026-08-31T23:59:59Z" />
        </Field>
      </FilterBar>

      {err && <Notice tone="error">{err}</Notice>}

      <DataPanel title={`Usage summary · ${rows.length}`}>
        <div className="data-table-wrap">
          <table className="data-table data-table--medium">
            <thead>
              <tr>
                <th>Metric</th>
                <th>Model</th>
                <th>Records</th>
                <th>Total</th>
                <th>First recorded</th>
                <th>Last recorded</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => (
                <tr key={`${row.metric}-${row.model ?? 'none'}-${index}`}>
                  <td><strong>{row.metric}</strong></td>
                  <td className="mono-text">{row.model || '-'}</td>
                  <td>{row.records.toLocaleString()}</td>
                  <td><strong>{row.totalValue.toLocaleString()}</strong></td>
                  <td>{formatDate(row.firstRecordedAt)}</td>
                  <td>{formatDate(row.lastRecordedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <EmptyState>Loading usage records...</EmptyState>}
          {!loading && rows.length === 0 && <EmptyState>No usage records match the current filters.</EmptyState>}
        </div>
      </DataPanel>
    </ManagementPage>
  );
}
