import React, { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { getTasks, listRuns, RunView, TaskView } from '../api/runs';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1000 },
  title: { margin: '0 0 18px', fontSize: '1.4rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  empty: { padding: '60px 0', color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '16px 20px', marginBottom: 12, cursor: 'pointer',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  rowTop: { display: 'flex', alignItems: 'center', gap: 12 },
  status: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 700,
    letterSpacing: '0.02em', textTransform: 'uppercase',
  },
  mode: { fontSize: '0.78rem', color: '#64748b', fontFamily: 'monospace' },
  time: { fontSize: '0.78rem', color: '#94a3b8', flexShrink: 0 },
  failure: { marginTop: 8, fontSize: '0.82rem', color: '#dc2626' },
  tasks: { marginTop: 12, borderTop: '1px solid #f1f5f9', paddingTop: 10 },
  task: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '6px 4px',
    fontSize: '0.84rem', color: '#475569',
  },
  err: { color: '#dc2626', fontSize: '0.9rem' },
};

function statusColor(status: string): { bg: string; fg: string; bd: string } {
  const s = (status ?? '').toUpperCase();
  if (s.includes('SUCCEED') || s === 'COMPLETED') return { bg: '#ecfdf5', fg: '#047857', bd: '#a7f3d0' };
  if (s.includes('FAIL') || s.includes('CANCEL')) return { bg: '#fef2f2', fg: '#b91c1c', bd: '#fecaca' };
  if (s.includes('RUNNING') || s.includes('PENDING') || s.includes('READY') || s.includes('CLAIMED') || s.includes('LEASED')) {
    return { bg: '#eef2ff', fg: '#4338ca', bd: '#c7d2fe' };
  }
  return { bg: '#f1f5f9', fg: '#475569', bd: '#e2e8f0' };
}

function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString();
}

export default function TasksPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [runs, setRuns] = useState<RunView[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<Record<string, TaskView[] | null>>({});

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listRuns(agentId, 50)
      .then(list => { if (!cancelled) setRuns(list); })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId]);

  async function toggle(run: RunView) {
    if (expanded[run.id] !== undefined) {
      setExpanded(prev => {
        const next = { ...prev };
        delete next[run.id];
        return next;
      });
      return;
    }
    setExpanded(prev => ({ ...prev, [run.id]: null }));
    try {
      const tasks = await getTasks(agentId, run.id);
      setExpanded(prev => ({ ...prev, [run.id]: tasks }));
    } catch {
      setExpanded(prev => ({ ...prev, [run.id]: [] }));
    }
  }

  return (
    <div style={S.root}>
      <h2 style={S.title}>Tasks &amp; Runs</h2>
      {err && <div style={S.err}>{err}</div>}
      {loading && <div style={S.empty}>Loading…</div>}
      {!loading && !err && runs.length === 0 && (
        <div style={S.empty}>No runs yet — send a chat message to create one.</div>
      )}
      {runs.map(run => {
        const sc = statusColor(run.status);
        const tasks = expanded[run.id];
        return (
          <div key={run.id} style={S.card} onClick={() => void toggle(run)}>
            <div style={S.rowTop}>
              <span style={{ ...S.status, background: sc.bg, color: sc.fg, border: `1px solid ${sc.bd}` }}>
                {run.status}
              </span>
              <span style={S.mode}>{run.mode}</span>
              <span style={{ fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'monospace', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {run.id}
              </span>
              <span style={{ flex: 1 }} />
              <span style={S.time}>{fmtTime(run.createdAt)}</span>
              <span style={{ fontSize: '0.7rem', color: '#94a3b8' }}>
                {tasks === undefined ? '▸' : tasks === null ? '…' : '▾'}
              </span>
            </div>
            {run.failureMessage && <div style={S.failure}>{run.failureMessage}</div>}
            {tasks && tasks.length > 0 && (
              <div style={S.tasks}>
                {tasks.map(t => (
                  <div key={t.id} style={S.task}>
                    <span style={{ width: 90, fontSize: '0.72rem', color: '#94a3b8' }}>{t.taskType}</span>
                    <span style={{ flex: 1 }}>{t.title || t.id}</span>
                    <span style={{ ...S.status, ...statusColor(t.status) }}>{t.status}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}