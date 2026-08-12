import { useEffect, useState } from 'react';
import { Info, LockKeyhole, Save, Trash2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { AgentDefinition, deleteAgent, updateAgent } from '../api/agents';
import { Notice } from './ManagementUI';

export default function AgentSettingsForm({ agent }: { agent: AgentDefinition }) {
  const navigate = useNavigate();
  const readOnly = agent.builtin;
  const [name, setName] = useState(agent.name);
  const [description, setDescription] = useState(agent.description ?? '');
  const [sysPrompt, setSysPrompt] = useState(agent.sysPrompt ?? '');
  const [maxIters, setMaxIters] = useState(String(agent.maxIters ?? 12));
  const [saving, setSaving] = useState(false);
  const [ok, setOk] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setName(agent.name);
    setDescription(agent.description ?? '');
    setSysPrompt(agent.sysPrompt ?? '');
    setMaxIters(String(agent.maxIters ?? 12));
  }, [agent]);

  async function handleSave() {
    setOk(false);
    setErr(null);
    setSaving(true);
    try {
      const iters = Number.parseInt(maxIters, 10);
      await updateAgent(agent.id, {
        name: name.trim() || agent.id,
        description: description.trim() || undefined,
        sysPrompt: sysPrompt || undefined,
        maxIters: Number.isFinite(iters) && iters > 0 ? iters : undefined,
      });
      setOk(true);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!confirm(`Delete agent "${agent.name}"? This removes its workspace and sessions.`)) return;
    try {
      await deleteAgent(agent.id);
      navigate('/agents', { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div className="settings-page">
      {readOnly && (
        <Notice tone="info">
          <LockKeyhole size={15} /> Built-in agents are read-only. Their managed configuration cannot be changed here.
        </Notice>
      )}

      <section className="settings-section" aria-labelledby="identity-title">
        <div className="settings-section-heading">
          <div><h2 id="identity-title">Identity</h2><p>How this agent appears across the workspace.</p></div>
          <span className="status-badge status-badge-neutral">{readOnly ? 'Built-in' : 'Custom'}</span>
        </div>
        <div className="settings-form-grid">
          <label className="management-field">
            <span>Agent ID</span>
            <code className="settings-static-value">{agent.id}</code>
          </label>
          <label className="management-field">
            <span>Name</span>
            <input className="management-input" value={name} onChange={event => setName(event.target.value)} disabled={readOnly} />
          </label>
          <label className="management-field settings-field-wide">
            <span>Description</span>
            <input className="management-input" value={description} onChange={event => setDescription(event.target.value)} disabled={readOnly} placeholder="Short summary shown in agent lists" />
          </label>
        </div>
      </section>

      <section className="settings-section" aria-labelledby="behavior-title">
        <div className="settings-section-heading">
          <div><h2 id="behavior-title">Runtime behavior</h2><p>Default operating instructions and task execution limits.</p></div>
        </div>
        <div className="settings-form-grid">
          <label className="management-field settings-field-wide">
            <span>System prompt</span>
            <textarea className="management-input settings-prompt" value={sysPrompt} onChange={event => setSysPrompt(event.target.value)} disabled={readOnly} placeholder="High-level instructions for this agent" />
            <small><Info size={13} /> Workspace instructions still take precedence at runtime.</small>
          </label>
          <label className="management-field settings-number-field">
            <span>Max iterations</span>
            <input className="management-input" type="number" min={1} max={64} value={maxIters} onChange={event => setMaxIters(event.target.value)} disabled={readOnly} />
          </label>
        </div>
      </section>

      {!readOnly && (
        <div className="settings-actions">
          <button className="primary-button" onClick={handleSave} disabled={saving}><Save size={16} />{saving ? 'Saving...' : 'Save changes'}</button>
          <button className="danger-button" onClick={handleDelete}><Trash2 size={16} />Delete agent</button>
        </div>
      )}
      {ok && <Notice tone="success">Agent settings saved.</Notice>}
      {err && <Notice tone="error">{err}</Notice>}

      <section className="settings-section settings-metadata" aria-labelledby="metadata-title">
        <div className="settings-section-heading"><div><h2 id="metadata-title">Metadata</h2><p>Read-only lifecycle information.</p></div></div>
        <dl className="settings-definition-list">
          <div><dt>Kind</dt><dd>{readOnly ? 'Built-in' : 'Custom'}</dd></div>
          <div><dt>Created</dt><dd>{new Date(agent.createdAt).toLocaleString()}</dd></div>
          <div><dt>Updated</dt><dd>{new Date(agent.updatedAt).toLocaleString()}</dd></div>
        </dl>
      </section>
    </div>
  );
}
