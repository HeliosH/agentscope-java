import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  cancelRun,
  decidePlan,
  getArtifacts,
  getEvents,
  getPlan,
  getRun,
  getTasks,
  type ArtifactView,
  type PlanView,
  type RunEventView,
  type RunView,
  type TaskView,
} from '../api/runs';
import { downloadFileVersion } from '../api/workspace';
import './RunInspector.css';

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED']);

function statusClass(status: string) {
  return `run-inspector__status run-inspector__status--${status.toLowerCase()}`;
}

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name.split('/').pop() || 'artifact';
  link.click();
  URL.revokeObjectURL(url);
}

export default function RunInspector({ agentId, runId }: { agentId: string; runId: string }) {
  const [run, setRun] = useState<RunView | null>(null);
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [plan, setPlan] = useState<PlanView | null>(null);
  const [events, setEvents] = useState<RunEventView[]>([]);
  const [artifacts, setArtifacts] = useState<ArtifactView[]>([]);
  const [error, setError] = useState('');
  const [acting, setActing] = useState(false);
  const lastSeq = useRef(0);

  const refresh = useCallback(async () => {
    try {
      const [nextRun, nextTasks, nextPlan, nextArtifacts, nextEvents] = await Promise.all([
        getRun(agentId, runId),
        getTasks(agentId, runId),
        getPlan(agentId, runId),
        getArtifacts(agentId, runId),
        getEvents(agentId, runId, lastSeq.current),
      ]);
      setRun(nextRun);
      setTasks(nextTasks);
      setPlan(nextPlan);
      setArtifacts(nextArtifacts);
      if (nextEvents.length) {
        lastSeq.current = Math.max(lastSeq.current, ...nextEvents.map(event => event.seq));
        setEvents(previous => [...previous, ...nextEvents].slice(-40));
      }
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Run state is unavailable');
    }
  }, [agentId, runId]);

  useEffect(() => {
    lastSeq.current = 0;
    setEvents([]);
    void refresh();
    const timer = window.setInterval(() => void refresh(), 2000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const dependencies = useMemo(() => {
    const result = new Map<string, string[]>();
    for (const edge of plan?.edges ?? []) {
      result.set(edge.toTaskId, [...(result.get(edge.toTaskId) ?? []), edge.fromTaskId]);
    }
    return result;
  }, [plan]);

  async function decide(decision: 'APPROVE' | 'REJECT') {
    if (!plan || acting) return;
    setActing(true);
    try {
      await decidePlan(agentId, runId, plan.planId, decision);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Plan decision failed');
    } finally {
      setActing(false);
    }
  }

  async function cancel() {
    if (acting || !run || TERMINAL.has(run.status)) return;
    setActing(true);
    try {
      await cancelRun(agentId, runId);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Run cancellation failed');
    } finally {
      setActing(false);
    }
  }

  async function download(artifact: ArtifactView) {
    try {
      saveBlob(
        await downloadFileVersion(agentId, artifact.fileVersionId),
        artifact.logicalPath,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Artifact download failed');
    }
  }

  return (
    <aside className="run-inspector" aria-label="Run details">
      <div className="run-inspector__header">
        <h2 className="run-inspector__heading">Run</h2>
        <div className="run-inspector__meta" title={runId}>{runId.slice(0, 12)}</div>
        {run && <span className={statusClass(run.status)}>{run.status}</span>}
        {run && !TERMINAL.has(run.status) && (
          <div className="run-inspector__actions">
            <button
              className="run-inspector__button run-inspector__button--danger"
              type="button"
              disabled={acting}
              onClick={() => void cancel()}
            >
              Cancel run
            </button>
          </div>
        )}
      </div>

      {error && <div className="run-inspector__error">{error}</div>}

      {plan && (
        <section className="run-inspector__section">
          <div className="run-inspector__item-row">
            <h3 className="run-inspector__section-title">Plan v{plan.version}</h3>
            <span className={statusClass(plan.status)}>{plan.status}</span>
          </div>
          <p className="run-inspector__goal">{plan.goal}</p>
          {plan.approvalRequired && plan.status === 'PENDING_APPROVAL' && (
            <div className="run-inspector__actions">
              <button
                className="run-inspector__button run-inspector__button--primary"
                type="button"
                disabled={acting}
                onClick={() => void decide('APPROVE')}
              >
                Approve
              </button>
              <button
                className="run-inspector__button"
                type="button"
                disabled={acting}
                onClick={() => void decide('REJECT')}
              >
                Reject
              </button>
            </div>
          )}
        </section>
      )}

      <section className="run-inspector__section">
        <h3 className="run-inspector__section-title">Tasks</h3>
        <div className="run-inspector__list">
          {tasks.map(task => {
            const deps = dependencies.get(task.id) ?? [];
            return (
              <div className="run-inspector__item" key={task.id}>
                <div className="run-inspector__item-row">
                  <div className="run-inspector__item-title">{task.title}</div>
                  <span className={statusClass(task.status)}>{task.status}</span>
                </div>
                <div className="run-inspector__muted">
                  {task.taskType}
                  {deps.length ? ` · ${deps.length} dependencies` : ''}
                </div>
              </div>
            );
          })}
          {!tasks.length && <div className="run-inspector__muted">No tasks yet</div>}
        </div>
      </section>

      <section className="run-inspector__section">
        <h3 className="run-inspector__section-title">Artifacts</h3>
        <div className="run-inspector__list">
          {artifacts.map(artifact => (
            <button
              className="run-inspector__artifact"
              key={artifact.id}
              type="button"
              title="Download artifact"
              onClick={() => void download(artifact)}
            >
              {artifact.logicalPath}
            </button>
          ))}
          {!artifacts.length && <div className="run-inspector__muted">No artifacts yet</div>}
        </div>
      </section>

      <section className="run-inspector__section">
        <h3 className="run-inspector__section-title">Recent events</h3>
        <div className="run-inspector__list">
          {events.slice(-12).reverse().map(event => (
            <div className="run-inspector__item" key={event.seq}>
              <div className="run-inspector__item-title">{event.eventType}</div>
              <div className="run-inspector__muted">#{event.seq}</div>
            </div>
          ))}
          {!events.length && <div className="run-inspector__muted">No events yet</div>}
        </div>
      </section>
    </aside>
  );
}
