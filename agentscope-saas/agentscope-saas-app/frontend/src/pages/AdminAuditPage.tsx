import { useEffect, useMemo, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import { listAuditLogs, type AuditLogView } from '../api/admin';
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
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const totals = useMemo(() => ({
    actors: new Set(rows.map(row => row.actor).filter(Boolean)).size,
    actions: new Set(rows.map(row => row.action).filter(Boolean)).size,
    resources: new Set(rows.map(row => row.resource).filter(Boolean)).size,
  }), [rows]);

  function clearFilters() {
    setActor('');
    setAction('');
    setResourcePrefix('');
    setLimit(100);
  }

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <ManagementPage admin>
      <ManagementHeader
        icon={ShieldCheck}
        title="Audit log"
        description="Review administrative and security-sensitive operations across the organization."
        actions={<RefreshButton loading={loading} onClick={() => void refresh()} />}
      />

      <MetricStrip items={[
        { label: 'Events', value: rows.length },
        { label: 'Actors', value: totals.actors },
        { label: 'Action types', value: totals.actions },
        { label: 'Resources', value: totals.resources },
      ]} />

      <FilterBar actions={(
        <>
          <button className="quiet-button" type="button" onClick={clearFilters}>Clear</button>
          <button className="primary-button" type="button" disabled={loading} onClick={() => void refresh()}>Apply</button>
        </>
      )}>
        <Field label="Actor">
          <input className="management-input" value={actor} onChange={event => setActor(event.target.value)} placeholder="Actor UUID" />
        </Field>
        <Field label="Action">
          <input className="management-input" value={action} onChange={event => setAction(event.target.value)} placeholder="admin.user.update" />
        </Field>
        <Field label="Resource prefix">
          <input className="management-input" value={resourcePrefix} onChange={event => setResourcePrefix(event.target.value)} placeholder="user:" />
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

      <DataPanel title={`Audit events · ${rows.length}`}>
        <div className="data-table-wrap">
          <table className="data-table" style={{ minWidth: 1050 }}>
            <thead>
              <tr>
                <th>ID</th>
                <th>Actor</th>
                <th>Action</th>
                <th>Resource</th>
                <th>Detail</th>
                <th>Time</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(row => (
                <tr key={row.id}>
                  <td className="mono-text">#{row.id}</td>
                  <td className="mono-text" title={row.actor ?? undefined}>{shortId(row.actor)}</td>
                  <td><strong>{row.action ?? '-'}</strong></td>
                  <td className="mono-text" title={row.resource ?? undefined}>{row.resource ?? '-'}</td>
                  <td className="mono-text" title={detailPreview(row.detail)}>{detailPreview(row.detail)}</td>
                  <td>{formatDate(row.ts)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {loading && <EmptyState>Loading audit events...</EmptyState>}
          {!loading && rows.length === 0 && <EmptyState>No audit records match the current filters.</EmptyState>}
        </div>
      </DataPanel>
    </ManagementPage>
  );
}
