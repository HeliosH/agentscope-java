import { useEffect, useMemo, useState } from 'react';
import { FileClock } from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { listMemoryEvents, type MemoryEventView } from '../api/admin';
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
  StatusBadge,
} from '../components/ManagementUI';

const STATUSES = ['', 'pending', 'syncing', 'synced', 'failed'];

function formatDate(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function shortId(value?: string | null): string {
  if (!value) return '-';
  return value.length > 12 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value;
}

function statusTone(status: string): 'success' | 'danger' | 'info' | 'warning' {
  if (status === 'synced') return 'success';
  if (status === 'failed') return 'danger';
  if (status === 'syncing') return 'info';
  return 'warning';
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
      setRows(await listMemoryEvents({ userId, sessionId, syncStatus, limit }));
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => ({
    pending: rows.filter(row => row.syncStatus === 'pending' || row.syncStatus === 'syncing').length,
    failed: rows.filter(row => row.syncStatus === 'failed').length,
    synced: rows.filter(row => row.syncStatus === 'synced').length,
    attempts: rows.reduce((sum, row) => sum + row.syncAttempts, 0),
  }), [rows]);

  function clearFilters() {
    setUserId('');
    setSessionId('');
    setSyncStatus('');
    setLimit(100);
  }

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <ManagementPage admin>
      <ManagementHeader
        icon={FileClock}
        title="Memory projection"
        description="Inspect durable memory events and their projection status into the semantic memory service."
        actions={<RefreshButton loading={loading} onClick={() => void refresh()} />}
      />

      <MetricStrip items={[
        { label: 'Queued / syncing', value: totals.pending, tone: totals.pending ? 'warning' : 'default' },
        { label: 'Failed', value: totals.failed, tone: totals.failed ? 'danger' : 'default' },
        { label: 'Synced', value: totals.synced, tone: 'success' },
        { label: 'Sync attempts', value: totals.attempts },
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
        <Field label="Session">
          <input className="management-input" value={sessionId} onChange={event => setSessionId(event.target.value)} placeholder="Session ID" />
        </Field>
        <Field label="Projection status">
          <select className="management-select" value={syncStatus} onChange={event => setSyncStatus(event.target.value)}>
            {STATUSES.map(value => <option key={value || 'all'} value={value}>{value || 'All statuses'}</option>)}
          </select>
        </Field>
        <Field label="Row limit">
          <input
            className="management-input"
            type="number"
            min={1}
            max={500}
            value={limit}
            onChange={event => setLimit(Math.max(1, Math.min(500, Number(event.target.value) || 100)))}
          />
        </Field>
      </FilterBar>

      {err && <Notice tone="error">{err}</Notice>}

      <DataPanel title={`Memory events · ${rows.length}`}>
        <div className="data-table-wrap">
          <table className="data-table" style={{ minWidth: 980 }}>
            <thead>
              <tr>
                <th>Status</th>
                <th>Source</th>
                <th>User</th>
                <th>Agent</th>
                <th>Session</th>
                <th>Attempts</th>
                <th>Created</th>
                <th>Error</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={row.id}>
                  <td><StatusBadge status={row.syncStatus} tone={statusTone(row.syncStatus)} /></td>
                  <td><strong>{row.source}</strong><div className="mono-text">{row.eventType}</div></td>
                  <td className="mono-text" title={row.userId}>{shortId(row.userId)}</td>
                  <td className="mono-text" title={row.agentId ?? undefined}>{shortId(row.agentId)}</td>
                  <td className="mono-text" title={row.sessionId ?? undefined}>{shortId(row.sessionId)}</td>
                  <td>{row.syncAttempts}</td>
                  <td>{formatDate(row.createdAt)}</td>
                  <td title={row.lastError ?? undefined}>{row.lastError ?? '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <EmptyState>Loading memory events...</EmptyState>}
          {!loading && rows.length === 0 && <EmptyState>No memory events match the current filters.</EmptyState>}
        </div>
      </DataPanel>
    </ManagementPage>
  );
}
