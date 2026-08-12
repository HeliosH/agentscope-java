import { useEffect, useRef, useState } from 'react';
import { Copy, Upload } from 'lucide-react';
import { useOutletContext } from 'react-router-dom';
import { summary as fetchSummary, uploadFile, WorkspaceSummary } from '../api/workspace';
import WorkspaceEditor from '../components/WorkspaceEditor';
import WorkspaceFileTree from '../components/WorkspaceFileTree';

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  const amount = value / 1024 ** index;
  return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
}

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
    fetchSummary(agentId).then(value => { if (!cancelled) setSummary(value); }).catch(() => { if (!cancelled) setSummary(null); });
    return () => { cancelled = true; };
  }, [agentId]);

  async function copyPath() {
    if (!summary?.workspacePath) return;
    try { await navigator.clipboard.writeText(summary.workspacePath); } catch { /* Clipboard may be unavailable. */ }
  }

  async function handleFilePicked(file: File | undefined) {
    if (!file) return;
    setUploading(true);
    setUploadErr(null);
    try {
      const uploaded = await uploadFile(agentId, file, uploadPath || undefined);
      setSelected(uploaded.path);
      setRefreshKey(key => key + 1);
      setSummary(await fetchSummary(agentId));
    } catch (error: unknown) {
      setUploadErr(error instanceof Error ? error.message : 'Upload failed');
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  return (
    <div className="workspace-page">
      {summary?.workspacePath && (
        <div className="workspace-path-bar" title={summary.workspacePath}>
          <span>Workspace</span><code>{summary.workspacePath}</code>
          <button className="icon-button" onClick={copyPath} title="Copy workspace path"><Copy size={15} /></button>
        </div>
      )}
      <div className="workspace-upload-bar">
        <input className="management-input" value={uploadPath} onChange={event => setUploadPath(event.target.value)} placeholder="Optional destination path, e.g. reports/result.csv" />
        <input ref={fileInput} type="file" hidden onChange={event => handleFilePicked(event.target.files?.[0])} />
        <button className="secondary-button" type="button" onClick={() => fileInput.current?.click()} disabled={uploading}><Upload size={15} />{uploading ? 'Uploading...' : 'Upload'}</button>
        {summary && <span className="workspace-usage" title={`Organization: ${formatBytes(summary.orgFileBytes)} / ${formatBytes(summary.orgFileLimitBytes)}; max file: ${formatBytes(summary.maxFileBytes)}`}>{formatBytes(summary.userFileBytes)} / {formatBytes(summary.userFileLimitBytes)}</span>}
      </div>
      {uploadErr && <div className="workspace-error">{uploadErr}</div>}
      <div className="workspace-browser">
        <WorkspaceFileTree agentId={agentId} selectedPath={selected} onSelect={path => setSelected(path || null)} refreshKey={refreshKey} onRefresh={() => setRefreshKey(key => key + 1)} />
        <WorkspaceEditor agentId={agentId} path={selected} refreshKey={refreshKey} onChanged={() => setRefreshKey(key => key + 1)} />
      </div>
    </div>
  );
}
