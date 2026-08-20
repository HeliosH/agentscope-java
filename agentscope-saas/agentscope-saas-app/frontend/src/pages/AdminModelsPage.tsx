import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import {
  CheckCircle2,
  Cpu,
  Pencil,
  Plus,
  TestTube2,
  Trash2,
  X,
} from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import {
  createAdminModel,
  deleteAdminModel,
  listAdminModels,
  testAdminModel,
  updateAdminModel,
  type AdminModelTestResult,
  type AdminModelView,
  type AdminModelWriteRequest,
} from '../api/admin';
import type { MeResponse } from '../auth';
import {
  DataPanel,
  EmptyState,
  ManagementHeader,
  ManagementPage,
  MetricStrip,
  Notice,
  RefreshButton,
  StatusBadge,
} from '../components/ManagementUI';

const EMPTY_MODEL: AdminModelWriteRequest = {
  id: '',
  displayName: '',
  providerType: 'gateway',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  contextWindowTokens: 32768,
  maxOutputTokens: 4096,
  safetyMarginTokens: 1024,
  enabled: true,
  defaultModel: false,
};

function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value % 1_000_000 ? 1 : 0)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(value % 1_000 ? 1 : 0)}K`;
  return String(value);
}

export default function AdminModelsPage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [models, setModels] = useState<AdminModelView[]>([]);
  const [editing, setEditing] = useState<AdminModelView | 'new' | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<Record<string, AdminModelTestResult>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      setModels(await listAdminModels());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const metrics = useMemo(() => ({
    total: models.length,
    enabled: models.filter(model => model.enabled).length,
    managed: models.filter(model => model.source === 'managed').length,
    providers: new Set(models.filter(model => model.enabled).map(model => model.providerType)).size,
  }), [models]);

  async function save(body: AdminModelWriteRequest) {
    const saved = editing === 'new'
      ? await createAdminModel(body)
      : await updateAdminModel((editing as AdminModelView).id, body);
    setEditing(null);
    setNotice(`${saved.displayName} saved and activated.`);
    await refresh();
  }

  async function remove(model: AdminModelView) {
    if (!window.confirm(`Delete managed model "${model.displayName}"?`)) return;
    setError(null);
    setNotice(null);
    try {
      await deleteAdminModel(model.id);
      setNotice(`${model.displayName} deleted.`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  async function test(model: AdminModelView) {
    setTestingId(model.id);
    setError(null);
    try {
      const result = await testAdminModel(model.id);
      setTestResult(current => ({ ...current, [model.id]: result }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setTestingId(null);
    }
  }

  if (me?.role !== 'admin' && me?.role !== 'platform_admin') {
    return <Navigate to="/agents" replace />;
  }

  return (
    <ManagementPage admin>
      <ManagementHeader
        icon={Cpu}
        title="Models"
        description="Manage organization model endpoints and their runtime context limits."
        actions={(
          <>
            <RefreshButton loading={loading} onClick={() => void refresh()} />
            <button className="primary-button" type="button" onClick={() => setEditing('new')}>
              <Plus size={14} /> Add model
            </button>
          </>
        )}
      />

      <MetricStrip items={[
        { label: 'Available', value: metrics.total },
        { label: 'Enabled', value: metrics.enabled, tone: metrics.enabled ? 'success' : 'danger' },
        { label: 'Managed', value: metrics.managed },
        { label: 'Providers', value: metrics.providers },
      ]} />

      {error && <Notice tone="error">{error}</Notice>}
      {notice && <Notice tone="success">{notice}</Notice>}

      <DataPanel title="Organization model catalog">
        <div className="data-table-wrap">
          <table className="data-table model-admin-table">
            <thead>
              <tr>
                <th>Model</th>
                <th>Provider</th>
                <th>Context</th>
                <th>Output</th>
                <th>Status</th>
                <th>Connection</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {models.map(model => {
                const result = testResult[model.id];
                const managed = model.source === 'managed';
                return (
                  <tr key={model.id}>
                    <td>
                      <strong>{model.displayName}</strong>
                      <div className="model-admin-id">{model.id} · {model.modelName}</div>
                    </td>
                    <td>
                      <span>{model.providerType}</span>
                      <div className="model-admin-id">{managed ? 'Managed' : 'Deployment'}</div>
                    </td>
                    <td>{formatTokens(model.contextWindowTokens)}</td>
                    <td>{formatTokens(model.maxOutputTokens)}</td>
                    <td>
                      <div className="model-admin-badges">
                        <StatusBadge
                          status={model.enabled ? 'enabled' : 'disabled'}
                          tone={model.enabled ? 'success' : 'neutral'}
                        />
                        {model.defaultModel && <StatusBadge status="default" tone="info" />}
                      </div>
                    </td>
                    <td>
                      {result ? (
                        <span className={`model-test-result ${result.ok ? 'is-ok' : 'is-error'}`}>
                          {result.ok && <CheckCircle2 size={13} />}
                          {result.message} · {result.latencyMs} ms
                        </span>
                      ) : <span className="model-admin-id">Not tested</span>}
                    </td>
                    <td>
                      <div className="table-actions">
                        <button
                          className="icon-button"
                          type="button"
                          title="Test connection"
                          disabled={!managed || testingId === model.id}
                          onClick={() => void test(model)}
                        >
                          <TestTube2 size={14} />
                        </button>
                        <button
                          className="icon-button"
                          type="button"
                          title="Edit model"
                          disabled={!managed}
                          onClick={() => setEditing(model)}
                        >
                          <Pencil size={14} />
                        </button>
                        <button
                          className="icon-button icon-button--danger"
                          type="button"
                          title="Delete model"
                          disabled={!managed}
                          onClick={() => void remove(model)}
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {loading && <EmptyState>Loading models...</EmptyState>}
          {!loading && models.length === 0 && <EmptyState>No models are available.</EmptyState>}
        </div>
      </DataPanel>

      {editing && (
        <ModelEditor
          initial={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSave={save}
        />
      )}
    </ManagementPage>
  );
}

function ModelEditor({
  initial,
  onClose,
  onSave,
}: {
  initial?: AdminModelView;
  onClose: () => void;
  onSave: (body: AdminModelWriteRequest) => Promise<void>;
}) {
  const [form, setForm] = useState<AdminModelWriteRequest>(initial ? {
    id: initial.id,
    displayName: initial.displayName,
    providerType: initial.providerType as 'gateway' | 'dashscope',
    baseUrl: initial.baseUrl ?? '',
    apiKey: '',
    modelName: initial.modelName,
    contextWindowTokens: initial.contextWindowTokens,
    maxOutputTokens: initial.maxOutputTokens,
    safetyMarginTokens: initial.safetyMarginTokens,
    enabled: initial.enabled,
    defaultModel: initial.defaultModel,
    version: initial.version,
  } : { ...EMPTY_MODEL });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set<K extends keyof AdminModelWriteRequest>(key: K, value: AdminModelWriteRequest[K]) {
    setForm(current => ({ ...current, [key]: value }));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSave({ ...form, apiKey: form.apiKey?.trim() || undefined });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="management-modal-overlay" role="presentation" onMouseDown={onClose}>
      <form className="management-modal model-editor" onSubmit={submit} onMouseDown={event => event.stopPropagation()}>
        <header className="management-modal-header">
          <div>
            <h2>{initial ? 'Edit model' : 'Add model'}</h2>
            <p>Changes become available to organization users immediately after save.</p>
          </div>
          <button className="icon-button" type="button" title="Close" onClick={onClose}>
            <X size={16} />
          </button>
        </header>
        <div className="management-modal-body model-editor__body">
          {error && <Notice tone="error">{error}</Notice>}
          <div className="model-editor__grid">
            <ModelField label="Model ID">
              <input required disabled={!!initial} value={form.id} onChange={event => set('id', event.target.value)} />
            </ModelField>
            <ModelField label="Display name">
              <input required value={form.displayName} onChange={event => set('displayName', event.target.value)} />
            </ModelField>
            <ModelField label="Provider">
              <select value={form.providerType} onChange={event => set('providerType', event.target.value as 'gateway' | 'dashscope')}>
                <option value="gateway">OpenAI-compatible gateway</option>
                <option value="dashscope">DashScope</option>
              </select>
            </ModelField>
            <ModelField label="Provider model name">
              <input required value={form.modelName} onChange={event => set('modelName', event.target.value)} />
            </ModelField>
            {form.providerType === 'gateway' && (
              <ModelField label="Gateway URL" wide>
                <input required type="url" value={form.baseUrl ?? ''} onChange={event => set('baseUrl', event.target.value)} placeholder="https://gateway.internal/v1" />
              </ModelField>
            )}
            <ModelField label="API key" wide hint={initial?.apiKeyConfigured ? 'A key is configured. Leave blank to keep it.' : 'Optional for gateways that do not require authentication.'}>
              <input type="password" autoComplete="new-password" value={form.apiKey ?? ''} onChange={event => set('apiKey', event.target.value)} />
            </ModelField>
            <ModelField label="Context window">
              <input type="number" min={1} required value={form.contextWindowTokens} onChange={event => set('contextWindowTokens', Number(event.target.value))} />
            </ModelField>
            <ModelField label="Maximum output">
              <input type="number" min={1} required value={form.maxOutputTokens} onChange={event => set('maxOutputTokens', Number(event.target.value))} />
            </ModelField>
            <ModelField label="Safety margin">
              <input type="number" min={0} required value={form.safetyMarginTokens} onChange={event => set('safetyMarginTokens', Number(event.target.value))} />
            </ModelField>
            <div className="model-editor__toggles">
              <label><input type="checkbox" checked={form.enabled} onChange={event => set('enabled', event.target.checked)} /> Enabled</label>
              <label><input type="checkbox" checked={form.defaultModel} disabled={!form.enabled} onChange={event => set('defaultModel', event.target.checked)} /> Default model</label>
              {initial?.apiKeyConfigured && (
                <label><input type="checkbox" checked={!!form.clearApiKey} onChange={event => set('clearApiKey', event.target.checked)} /> Remove API key</label>
              )}
            </div>
          </div>
        </div>
        <footer className="model-editor__footer">
          <button className="quiet-button" type="button" onClick={onClose}>Cancel</button>
          <button className="primary-button" type="submit" disabled={saving}>{saving ? 'Saving' : 'Save model'}</button>
        </footer>
      </form>
    </div>
  );
}

function ModelField({ label, hint, wide = false, children }: { label: string; hint?: string; wide?: boolean; children: ReactNode }) {
  return (
    <label className={`model-editor__field${wide ? ' is-wide' : ''}`}>
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
