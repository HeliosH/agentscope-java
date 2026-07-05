import React, { useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';
import { summary as fetchSummary, uploadFile, WorkspaceSummary } from '../api/workspace';

const pathBar: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8,
  padding: '6px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#f8fafc', flexShrink: 0,
  fontSize: '0.78rem', color: '#64748b',
};
const pathLabel: React.CSSProperties = {
  fontWeight: 600, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.08em',
};
const pathValue: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  color: '#334155', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  flex: 1, minWidth: 0,
};
const hint: React.CSSProperties = {
  padding: '5px 16px', borderBottom: '1px solid #f1f5f9',
  background: '#fffbeb', color: '#92400e',
  fontSize: '0.74rem', flexShrink: 0,
};
const uploadBar: React.CSSProperties = {
  padding: '8px 16px', borderBottom: '1px solid #e2e8f0',
  background: '#ffffff', display: 'flex', alignItems: 'center', gap: 8,
  flexShrink: 0,
};
const pathInput: React.CSSProperties = {
  flex: 1, minWidth: 0, border: '1px solid #cbd5e1', borderRadius: 6,
  padding: '6px 10px', fontSize: '0.82rem',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};
const button: React.CSSProperties = {
  background: '#ffffff', border: '1px solid #cbd5e1', color: '#475569',
  borderRadius: 6, padding: '6px 11px', cursor: 'pointer',
  fontSize: '0.82rem', fontWeight: 600,
};

export default function AgentWorkspacePage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [selected, setSelected] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [summary, setSummary] = useState<WorkspaceSummary | null>(null);
  const [uploadPath, setUploadPath] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadErr, setUploadErr] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchSummary(agentId)
      .then(s => { if (!cancelled) setSummary(s); })
      .catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [agentId]);

  async function copyPath() {
    if (!summary?.workspacePath) return;
    try {
      await navigator.clipboard.writeText(summary.workspacePath);
    } catch {
      // ignore — clipboard unavailable
    }
  }

  async function handleFilePicked(file: File | undefined) {
    if (!file) return;
    setUploading(true);
    setUploadErr(null);
    try {
      const uploaded = await uploadFile(agentId, file, uploadPath || undefined);
      setSelected(uploaded.path);
      setRefreshKey(k => k + 1);
    } catch (e: unknown) {
      setUploadErr(e instanceof Error ? e.message : 'Upload failed');
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {summary?.workspacePath && (
        <div style={pathBar} title={summary.workspacePath}>
          <span style={pathLabel}>Path</span>
          <span style={pathValue}>{summary.workspacePath}</span>
          <button
            onClick={copyPath}
            style={{
              background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
              borderRadius: 6, padding: '3px 10px', cursor: 'pointer',
              fontSize: '0.75rem', fontWeight: 500,
            }}
            title="Copy path"
          >
            Copy
          </button>
        </div>
      )}
      <div style={uploadBar}>
        <input
          style={pathInput}
          value={uploadPath}
          onChange={e => setUploadPath(e.target.value)}
          placeholder="Upload target path, e.g. reports/result.csv or reports/"
        />
        <input
          ref={fileInput}
          type="file"
          style={{ display: 'none' }}
          onChange={e => handleFilePicked(e.target.files?.[0])}
        />
        <button
          type="button"
          style={button}
          onClick={() => fileInput.current?.click()}
          disabled={uploading}
          title="Upload file"
        >
          {uploading ? 'Uploading…' : 'Upload'}
        </button>
      </div>
      {uploadErr && (
        <div style={hint}>
          {uploadErr}
        </div>
      )}
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <WorkspaceFileTree
          agentId={agentId}
          selectedPath={selected}
          onSelect={p => setSelected(p || null)}
          refreshKey={refreshKey}
          onRefresh={() => setRefreshKey(k => k + 1)}
        />
        <WorkspaceEditor
          agentId={agentId}
          path={selected}
          refreshKey={refreshKey}
          onChanged={() => setRefreshKey(k => k + 1)}
        />
      </div>
    </div>
  );
}
