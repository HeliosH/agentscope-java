import { useEffect, useMemo, useState } from 'react';
import { Database, Trash2 } from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { forceEvictSandbox, listSandboxes, type SandboxView } from '../api/admin';
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

const STATUSES = ['', 'active', 'released', 'evicted'];
const TYPES = ['', 'e2b', 'opensandbox', 'cube', 'docker'];

function formatDate(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function shortId(value?: string | null): string {
  if (!value) return '-';
  return value.length > 12 ? `${value.slice(0, 8)}...${value.slice(-4)}` : value;
}

function sandboxTone(row: SandboxView): 'neutral' | 'success' | 'danger' | 'warning' {
  if (row.expired) return 'danger';
  if (row.status === 'active') return 'success';
  if (row.status === 'released') return 'neutral';
  return 'warning';
}

function backendTone(status?: string | null): 'neutral' | 'success' | 'danger' | 'info' {
  if (status === 'succeeded') return 'success';
  if (status === 'failed') return 'danger';
  if (status === 'terminating' || status === 'pending') return 'info';
  return 'neutral';
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
      setRows(await listSandboxes({ status, sandboxType, userId, expiredOnly, limit }));
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  async function forceEvict(row: SandboxView) {
    if (!confirm(`Force evict sandbox ${shortId(row.id)} and terminate its backend runtime?`)) return;
    setActingId(row.id);
    setErr(null);
    setAction(null);
    try {
      const result = await forceEvictSandbox(row.id, 'admin console force evict', true);
      setAction(`Sandbox ${shortId(row.id)}: ${result.backendTerminationStatus}`);
      await refresh();
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setActingId(null);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => ({
    active: rows.filter(row => row.status === 'active' && !row.expired).length,
    expired: rows.filter(row => row.expired).length,
    released: rows.filter(row => row.status === 'released').length,
    failures: rows.filter(row => row.backendReleaseStatus === 'failed').length,
  }), [rows]);

  function clearFilters() {
    setStatus('');
    setSandboxType('');
    setUserId('');
    setExpiredOnly(false);
    setLimit(100);
  }

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <ManagementPage admin>
      <ManagementHeader
        icon={Database}
        title="Sandbox inventory"
        description="Monitor runtime allocation, backend release state, and expired resources."
        actions={<RefreshButton loading={loading} onClick={() => void refresh()} />}
      />

      <MetricStrip items={[
        { label: 'Active', value: totals.active, tone: 'success' },
        { label: 'Expired active', value: totals.expired, tone: totals.expired ? 'danger' : 'default' },
        { label: 'Released', value: totals.released },
        { label: 'Release failures', value: totals.failures, tone: totals.failures ? 'danger' : 'default' },
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
        <Field label="Lifecycle status">
          <select className="management-select" value={status} onChange={event => setStatus(event.target.value)}>
            {STATUSES.map(value => <option key={value || 'all'} value={value}>{value || 'All statuses'}</option>)}
          </select>
        </Field>
        <Field label="Runtime provider">
          <select className="management-select" value={sandboxType} onChange={event => setSandboxType(event.target.value)}>
            {TYPES.map(value => <option key={value || 'all'} value={value}>{value || 'All providers'}</option>)}
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
        <label className="management-check">
          <input type="checkbox" checked={expiredOnly} onChange={event => setExpiredOnly(event.target.checked)} />
          Expired only
        </label>
      </FilterBar>

      {err && <Notice tone="error">{err}</Notice>}
      {action && <Notice tone="info">{action}</Notice>}

      <DataPanel title={`Runtime records · ${rows.length}`}>
        <div className="data-table-wrap">
          <table className="data-table data-table--xwide">
            <thead>
              <tr>
                <th>Status</th>
                <th>Provider</th>
                <th>User</th>
                <th>External runtime</th>
                <th>Backend release</th>
                <th>Last used</th>
                <th>Attempts</th>
                <th>Error</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={row.id}>
                  <td><StatusBadge status={row.expired ? 'expired' : row.status} tone={sandboxTone(row)} /></td>
                  <td><strong>{row.sandboxType ?? '-'}</strong></td>
                  <td className="mono-text" title={row.userId}>{shortId(row.userId)}</td>
                  <td className="mono-text" title={row.externalId ?? undefined}>{shortId(row.externalId)}</td>
                  <td title={row.backendReleasedAt ? `Released ${formatDate(row.backendReleasedAt)}` : undefined}>
                    <StatusBadge status={row.backendReleaseStatus ?? 'unknown'} tone={backendTone(row.backendReleaseStatus)} />
                  </td>
                  <td>{formatDate(row.lastUsedAt)}</td>
                  <td>{row.backendReleaseAttempts ?? 0}</td>
                  <td title={row.backendReleaseError ?? undefined}>{row.backendReleaseError ?? '-'}</td>
                  <td>
                    <button
                      className="danger-button"
                      type="button"
                      disabled={actingId === row.id}
                      onClick={() => void forceEvict(row)}
                    >
                      <Trash2 size={13} />
                      {actingId === row.id ? 'Working' : 'Evict'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <EmptyState>Loading sandbox inventory...</EmptyState>}
          {!loading && rows.length === 0 && <EmptyState>No sandbox records match the current filters.</EmptyState>}
        </div>
      </DataPanel>
    </ManagementPage>
  );
}
