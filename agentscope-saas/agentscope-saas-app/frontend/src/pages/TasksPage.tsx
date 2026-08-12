import { Fragment, useEffect, useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, RefreshCw, Search } from 'lucide-react';
import { useOutletContext } from 'react-router-dom';
import { getTasks, listRuns, type RunView, type TaskView } from '../api/runs';
import { DataPanel, EmptyState, MetricStrip, Notice, StatusBadge } from '../components/ManagementUI';

function tone(status: string): 'neutral' | 'success' | 'danger' | 'info' | 'warning' {
  const value = status.toUpperCase();
  if (value.includes('SUCCEED') || value === 'COMPLETED' || value === 'APPROVED') return 'success';
  if (value.includes('FAIL') || value.includes('CANCEL') || value.includes('REJECT')) return 'danger';
  if (value.includes('RUNNING') || value.includes('CLAIMED') || value.includes('LEASED')) return 'info';
  if (value.includes('PENDING') || value.includes('READY')) return 'warning';
  return 'neutral';
}

function formatDate(value?: string | null): string {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}

export default function TasksPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [runs, setRuns] = useState<RunView[]>([]);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('all');
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<Record<string, TaskView[] | null>>({});

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      setRuns(await listRuns(agentId, 100));
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : 'Failed to load runs');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, [agentId]);

  async function toggle(run: RunView) {
    if (expanded[run.id] !== undefined) {
      setExpanded(current => {
        const next = { ...current };
        delete next[run.id];
        return next;
      });
      return;
    }
    setExpanded(current => ({ ...current, [run.id]: null }));
    try {
      const tasks = await getTasks(agentId, run.id);
      setExpanded(current => ({ ...current, [run.id]: tasks }));
    } catch {
      setExpanded(current => ({ ...current, [run.id]: [] }));
    }
  }

  const statuses = useMemo(() => Array.from(new Set(runs.map(run => run.status))).sort(), [runs]);
  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return runs.filter(run => (status === 'all' || run.status === status) && (
      !needle || run.id.toLowerCase().includes(needle) || run.mode.toLowerCase().includes(needle) || run.failureMessage?.toLowerCase().includes(needle)
    ));
  }, [query, runs, status]);
  const active = runs.filter(run => !['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(run.status)).length;
  const succeeded = runs.filter(run => run.status === 'SUCCEEDED').length;
  const failed = runs.filter(run => run.status === 'FAILED').length;

  return (
    <div className="agent-management-page">
      <MetricStrip items={[
        { label: 'Total runs', value: runs.length },
        { label: 'Active', value: active, tone: active ? 'warning' : 'default' },
        { label: 'Succeeded', value: succeeded, tone: 'success' },
        { label: 'Failed', value: failed, tone: failed ? 'danger' : 'default' },
      ]} />

      <div className="agent-management-toolbar">
        <label className="compact-search">
          <Search size={14} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search runs" />
        </label>
        <select className="management-select" value={status} onChange={event => setStatus(event.target.value)} aria-label="Run status">
          <option value="all">All statuses</option>
          {statuses.map(value => <option key={value} value={value}>{value}</option>)}
        </select>
        <button className="quiet-button" type="button" disabled={loading} onClick={() => void refresh()}>
          <RefreshCw className={loading ? 'is-spinning' : ''} size={14} />
          Refresh
        </button>
      </div>

      {err && <Notice tone="error">{err}</Notice>}

      <DataPanel title={`Runs · ${visible.length}`}>
        <div className="data-table-wrap">
          <table className="data-table" style={{ minWidth: 760 }}>
            <thead>
              <tr>
                <th aria-label="Expand" />
                <th>Status</th>
                <th>Mode</th>
                <th>Run ID</th>
                <th>Created</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(run => {
                const tasks = expanded[run.id];
                const open = tasks !== undefined;
                return (
                  <Fragment key={run.id}>
                    <tr className="clickable-row" onClick={() => void toggle(run)}>
                      <td>{open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</td>
                      <td><StatusBadge status={run.status} tone={tone(run.status)} /></td>
                      <td>{run.mode}</td>
                      <td className="mono-text" title={run.id}>{run.id}</td>
                      <td>{formatDate(run.createdAt)}</td>
                      <td title={run.failureMessage ?? undefined}>{run.failureMessage ?? (run.completedAt ? formatDate(run.completedAt) : '-')}</td>
                    </tr>
                    {open && (
                      <tr className="expanded-row">
                        <td colSpan={6}>
                          {tasks === null ? (
                            <div className="inline-loading">Loading tasks...</div>
                          ) : tasks.length === 0 ? (
                            <div className="inline-loading">No child tasks recorded.</div>
                          ) : (
                            <div className="task-breakdown">
                              {tasks.map(task => (
                                <div className="task-breakdown__row" key={task.id}>
                                  <StatusBadge status={task.status} tone={tone(task.status)} />
                                  <strong>{task.title || task.id}</strong>
                                  <span>{task.taskType}</span>
                                  <span>{task.workspaceMode}</span>
                                </div>
                              ))}
                            </div>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
          {loading && <EmptyState>Loading runs...</EmptyState>}
          {!loading && visible.length === 0 && <EmptyState>No runs match the current filters.</EmptyState>}
        </div>
      </DataPanel>
    </div>
  );
}
