import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  Check,
  CircleStop,
  Eye,
  FileOutput,
  ListChecks,
  X,
} from 'lucide-react';
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
import { FilePreviewDialog, type PreviewFile } from './FilePreview';
import './RunInspector.css';

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED']);
type InspectorTab = 'tasks' | 'artifacts' | 'events';

function statusClass(status: string) {
  return `run-status run-status--${status.toLowerCase()}`;
}

function statusLabel(status: string) {
  return status.toLowerCase().replace(/_/g, ' ');
}

interface RunInspectorProps {
  agentId: string;
  runId: string;
  onClose?: () => void;
}

export default function RunInspector({ agentId, runId, onClose }: RunInspectorProps) {
  const [run, setRun] = useState<RunView | null>(null);
  const [tasks, setTasks] = useState<TaskView[]>([]);
  const [plan, setPlan] = useState<PlanView | null>(null);
  const [events, setEvents] = useState<RunEventView[]>([]);
  const [artifacts, setArtifacts] = useState<ArtifactView[]>([]);
  const [tab, setTab] = useState<InspectorTab>('tasks');
  const [error, setError] = useState('');
  const [acting, setActing] = useState(false);
  const [previewFile, setPreviewFile] = useState<PreviewFile | null>(null);
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
        setEvents(previous => [...previous, ...nextEvents].slice(-80));
      }
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Run state is unavailable');
    }
  }, [agentId, runId]);

  useEffect(() => {
    lastSeq.current = 0;
    setEvents([]);
    setPreviewFile(null);
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

  const completed = tasks.filter(task => ['SUCCEEDED', 'COMPLETED'].includes(task.status)).length;

  async function decide(decision: 'APPROVE' | 'REJECT') {
    if (!plan || acting) return;
    setActing(true);
    try {
      await decidePlan(agentId, runId, plan.planId, decision);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Plan decision failed');
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
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Run cancellation failed');
    } finally {
      setActing(false);
    }
  }

  const tabs: { key: InspectorTab; label: string; count: number; icon: typeof ListChecks }[] = [
    { key: 'tasks', label: 'Tasks', count: tasks.length, icon: ListChecks },
    { key: 'artifacts', label: 'Files', count: artifacts.length, icon: FileOutput },
    { key: 'events', label: 'Activity', count: events.length, icon: Activity },
  ];

  return (
    <aside className="run-inspector" aria-label="Run details">
      <header className="run-inspector__header">
        <div>
          <h2 className="run-inspector__heading">Run details</h2>
          <div className="run-inspector__id" title={runId}>{runId.slice(0, 12)}</div>
        </div>
        <span className="run-inspector__spacer" />
        {run && <span className={statusClass(run.status)}>{statusLabel(run.status)}</span>}
        {onClose && (
          <button className="icon-button" type="button" title="Close run details" aria-label="Close run details" onClick={onClose}>
            <X size={16} />
          </button>
        )}
      </header>

      {error && <div className="run-inspector__error">{error}</div>}

      <section className="run-summary">
        {plan?.goal && <p className="run-summary__goal">{plan.goal}</p>}
        <div className="run-summary__progress-row">
          <span>{tasks.length ? `${completed} of ${tasks.length} tasks complete` : 'Preparing tasks'}</span>
          {run && !TERMINAL.has(run.status) && (
            <button className="run-summary__cancel" type="button" disabled={acting} onClick={() => void cancel()}>
              <CircleStop size={13} />
              Stop
            </button>
          )}
        </div>
        {tasks.length > 0 && (
          <div className="run-progress" aria-label={`${completed} of ${tasks.length} tasks complete`}>
            <span style={{ width: `${Math.round((completed / tasks.length) * 100)}%` }} />
          </div>
        )}
      </section>

      {plan?.approvalRequired && plan.status === 'PENDING_APPROVAL' && (
        <section className="plan-approval">
          <div className="plan-approval__title">Plan approval required</div>
          <div className="plan-approval__actions">
            <button className="quiet-button" type="button" disabled={acting} onClick={() => void decide('REJECT')}>
              <X size={14} /> Reject
            </button>
            <button className="primary-button" type="button" disabled={acting} onClick={() => void decide('APPROVE')}>
              <Check size={14} /> Approve
            </button>
          </div>
        </section>
      )}

      <div className="run-tabs" role="tablist" aria-label="Run detail views">
        {tabs.map(item => {
          const Icon = item.icon;
          return (
            <button
              key={item.key}
              className={`run-tab${tab === item.key ? ' is-active' : ''}`}
              type="button"
              role="tab"
              aria-selected={tab === item.key}
              onClick={() => setTab(item.key)}
            >
              <Icon size={13} />
              {item.label}
              <span>{item.count}</span>
            </button>
          );
        })}
      </div>

      <div className="run-inspector__body">
        {tab === 'tasks' && (
          <div className="run-list">
            {tasks.map(task => {
              const deps = dependencies.get(task.id) ?? [];
              return (
                <div className="run-task" key={task.id}>
                  <span className={`run-task__marker run-task__marker--${task.status.toLowerCase()}`} />
                  <div className="run-task__content">
                    <div className="run-task__top">
                      <div className="run-task__title">{task.title || task.id}</div>
                      <span className={statusClass(task.status)}>{statusLabel(task.status)}</span>
                    </div>
                    <div className="run-task__meta">
                      {task.taskType}{deps.length ? ` · ${deps.length} dependencies` : ''}
                    </div>
                  </div>
                </div>
              );
            })}
            {!tasks.length && <div className="run-empty">Waiting for the plan...</div>}
          </div>
        )}

        {tab === 'artifacts' && (
          <div className="run-list">
            {artifacts.map(artifact => (
              <button
                className="run-file"
                key={artifact.id}
                type="button"
                title={`Preview ${artifact.logicalPath}`}
                onClick={() => setPreviewFile({
                  path: artifact.logicalPath,
                  name: artifact.logicalPath.split('/').pop(),
                  versionId: artifact.fileVersionId,
                })}
              >
                <FileOutput size={15} />
                <span>{artifact.logicalPath}</span>
                <Eye size={14} />
              </button>
            ))}
            {!artifacts.length && <div className="run-empty">No files produced yet.</div>}
          </div>
        )}

        {tab === 'events' && (
          <div className="run-list">
            {events.slice().reverse().map(event => (
              <div className="run-event" key={event.seq}>
                <span className="run-event__seq">#{event.seq}</span>
                <div>
                  <div className="run-event__title">{statusLabel(event.eventType)}</div>
                  <div className="run-event__time">{new Date(event.createdAt).toLocaleTimeString()}</div>
                </div>
              </div>
            ))}
            {!events.length && <div className="run-empty">No activity yet.</div>}
          </div>
        )}
      </div>
      <FilePreviewDialog agentId={agentId} file={previewFile} onClose={() => setPreviewFile(null)} />
    </aside>
  );
}
