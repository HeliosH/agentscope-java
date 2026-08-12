import { Bot, FilePlus2, LayoutTemplate, Sparkles } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentCreateRequest, AgentDraft, createAgent } from '../api/agents';
import AiDescribeForm from '../components/AiDescribeForm';
import TemplatePicker from '../components/TemplatePicker';

type Mode = 'blank' | 'template' | 'ai';

export default function AgentCreatePage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('blank');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workspacePath, setWorkspacePath] = useState('');
  const [sysPrompt, setSysPrompt] = useState('');
  const [templateId, setTemplateId] = useState<string | null>(null);
  const [draft, setDraft] = useState<AgentDraft | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  function handleDraftChange(next: AgentDraft | null) {
    setDraft(next);
    if (!next) return;
    if (!name) setName(next.name);
    if (!description && next.description) setDescription(next.description);
    if (!sysPrompt && next.sysPrompt) setSysPrompt(next.sysPrompt);
  }

  const canSubmit = !submitting && !!name.trim() && (mode !== 'template' || !!templateId) && (mode !== 'ai' || !!draft);

  async function handleSubmit() {
    setErr(null);
    setSubmitting(true);
    try {
      const request: AgentCreateRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        sysPrompt: sysPrompt.trim() || undefined,
        workspacePath: workspacePath.trim() || undefined,
        templateId: mode === 'template' && templateId ? templateId : undefined,
        aiDraft: mode === 'ai' && draft ? draft : undefined,
      };
      const created = await createAgent(request);
      navigate(`/agents/${encodeURIComponent(created.id)}/workspace`, { replace: true });
    } catch (error: unknown) {
      setErr(error instanceof Error ? error.message : 'Failed to create agent');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="agent-create-page">
      <header className="agent-create-header"><div className="agent-create-icon"><Bot size={19} /></div><div><h1>Create agent</h1><p>Provision a focused assistant with an isolated workspace and runtime policy.</p></div></header>
      <div className="segmented-control agent-create-modes" role="tablist" aria-label="Creation mode">
        <button className={mode === 'blank' ? 'is-active' : ''} onClick={() => setMode('blank')} role="tab"><FilePlus2 size={15} />Blank</button>
        <button className={mode === 'template' ? 'is-active' : ''} onClick={() => setMode('template')} role="tab"><LayoutTemplate size={15} />Template</button>
        <button className={mode === 'ai' ? 'is-active' : ''} onClick={() => setMode('ai')} role="tab"><Sparkles size={15} />Describe with AI</button>
      </div>

      <section className="agent-create-form">
        <div className="agent-create-mode-content">
          {mode === 'blank' && <p>Start with a clean workspace scaffold and configure capabilities later.</p>}
          {mode === 'template' && <><p>Select an approved starting point, then customize its identity.</p><TemplatePicker selected={templateId} onSelect={setTemplateId} /></>}
          {mode === 'ai' && <><p>Describe the role and review the generated configuration before creation.</p><AiDescribeForm available draft={draft} onDraft={handleDraftChange} /></>}
        </div>
        <div className="settings-form-grid agent-create-fields">
          <label className="management-field"><span>Name *</span><input className="management-input" value={name} onChange={event => setName(event.target.value)} placeholder="Research assistant" /></label>
          <label className="management-field"><span>Description</span><input className="management-input" value={description} onChange={event => setDescription(event.target.value)} placeholder="Short summary shown in agent lists" /></label>
          <label className="management-field settings-field-wide"><span>Workspace path</span><input className="management-input mono-text" value={workspacePath} onChange={event => setWorkspacePath(event.target.value)} placeholder="Use the managed default workspace" /><small>Leave blank to provision the standard isolated workspace.</small></label>
          {mode !== 'template' && <label className="management-field settings-field-wide"><span>System prompt</span><textarea className="management-input settings-prompt" value={sysPrompt} onChange={event => setSysPrompt(event.target.value)} placeholder="High-level behavior and operating boundaries" /></label>}
        </div>
        <footer className="agent-create-actions">
          <button className="primary-button" onClick={handleSubmit} disabled={!canSubmit}><Bot size={16} />{submitting ? 'Creating...' : 'Create agent'}</button>
          <button className="secondary-button" onClick={() => navigate('/agents')}>Cancel</button>
          {err && <span className="agent-create-error">{err}</span>}
        </footer>
      </section>
    </main>
  );
}
