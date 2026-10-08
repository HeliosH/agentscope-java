import { useState, type FormEvent } from 'react';
import { Pencil } from 'lucide-react';
import {
  updateModelInvocationPolicy,
  type AdminModelView,
  type ModelInvocationPolicy,
  type ModelInvocationPolicyWrite,
} from '../api/admin';
import { DataPanel, EmptyState, Field, ManagementDialog, Notice, StatusBadge } from './ManagementUI';

const LABELS = {
  COMPACTION: 'Context compression',
  MEMORY_EXTRACT: 'Memory extraction',
  MEMORY_CONSOLIDATE: 'Memory consolidation',
  VERIFY: 'Verification',
};

export default function ModelInvocationPolicies({ policies, models, loading, onSaved }: {
  policies: ModelInvocationPolicy[];
  models: AdminModelView[];
  loading: boolean;
  onSaved: (policy: ModelInvocationPolicy) => void;
}) {
  const [editing, setEditing] = useState<ModelInvocationPolicy | null>(null);
  return (
    <DataPanel title="Auxiliary model policies">
      <div className="data-table-wrap">
        <table className="data-table">
          <thead><tr><th>Purpose</th><th>Model</th><th>Input tokens</th><th>Output tokens</th><th>Timeout</th><th aria-label="Actions" /></tr></thead>
          <tbody>{policies.map(policy => (
            <tr key={policy.purpose}>
              <td><div className="model-admin-badges"><strong>{LABELS[policy.purpose]}</strong>{policy.purpose === 'VERIFY' && <StatusBadge status="reserved" tone="neutral" />}</div></td>
              <td>{policy.modelId ? models.find(model => model.id === policy.modelId)?.displayName ?? policy.modelId : 'Selected task model'}</td>
              <td>{policy.maxInputTokens.toLocaleString()}</td>
              <td>{policy.maxOutputTokens.toLocaleString()}</td>
              <td>{policy.timeoutSeconds} s</td>
              <td><button className="icon-button" type="button" title={`Edit ${LABELS[policy.purpose].toLowerCase()} policy`} onClick={() => setEditing(policy)}><Pencil size={14} /></button></td>
            </tr>
          ))}</tbody>
        </table>
        {loading && policies.length === 0 && <EmptyState>Loading policies...</EmptyState>}
      </div>
      {editing && <PolicyEditor key={editing.version} policy={editing} models={models} onClose={() => setEditing(null)} onSaved={saved => { setEditing(null); onSaved(saved); }} />}
    </DataPanel>
  );
}

function PolicyEditor({ policy, models, onClose, onSaved }: {
  policy: ModelInvocationPolicy; models: AdminModelView[];
  onClose: () => void; onSaved: (policy: ModelInvocationPolicy) => void;
}) {
  const [form, setForm] = useState<ModelInvocationPolicyWrite>({
    modelId: policy.modelId, maxInputTokens: policy.maxInputTokens,
    maxOutputTokens: policy.maxOutputTokens, timeoutSeconds: policy.timeoutSeconds, version: policy.version,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const available = models.filter(model => model.enabled);
  const missing = !!form.modelId && !available.some(model => model.id === form.modelId);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    setSaving(true); setError(null);
    try { onSaved(await updateModelInvocationPolicy(policy.purpose, form)); }
    catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setSaving(false); }
  }
  return (
    <ManagementDialog title={LABELS[policy.purpose]} onClose={onClose} onSubmit={submit}
      className="model-editor" bodyClassName="model-editor__body" footer={(
        <><button className="quiet-button" type="button" disabled={saving} onClick={onClose}>Cancel</button>
          <button className="primary-button" type="submit" disabled={saving || missing}>{saving ? 'Saving' : 'Save policy'}</button></>
      )}>
      {error && <Notice tone="error">{error}</Notice>}
      <div className="model-editor__grid">
        <Field label="Model" wide><select aria-label="Model" className="management-select" value={form.modelId ?? ''} disabled={saving}
          onChange={event => setForm(current => ({ ...current, modelId: event.target.value || null }))}>
          <option value="">Selected task model</option>
          {missing && <option value={form.modelId!} disabled>{form.modelId} (unavailable)</option>}
          {available.map(model => <option key={model.id} value={model.id}>{model.displayName}</option>)}
        </select></Field>
        <Field label="Maximum input tokens"><input className="management-input" type="number" min={1} max={2147483647} step={1} required disabled={saving} value={form.maxInputTokens}
          onChange={event => setForm(current => ({ ...current, maxInputTokens: Number(event.target.value) }))} /></Field>
        <Field label="Maximum output tokens"><input className="management-input" type="number" min={1} max={2147483647} step={1} required disabled={saving} value={form.maxOutputTokens}
          onChange={event => setForm(current => ({ ...current, maxOutputTokens: Number(event.target.value) }))} /></Field>
        <Field label="Timeout (seconds)"><input className="management-input" type="number" min={1} max={3600} step={1} required disabled={saving} value={form.timeoutSeconds}
          onChange={event => setForm(current => ({ ...current, timeoutSeconds: Number(event.target.value) }))} /></Field>
      </div>
    </ManagementDialog>
  );
}
