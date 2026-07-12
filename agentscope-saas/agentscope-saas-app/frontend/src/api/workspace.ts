export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'dir';
  size?: number;
  children?: FileNode[];
}

export interface WorkspaceSummary {
  agentId: string;
  workspacePath?: string;
  exists: boolean;
  agentsMdExists: boolean;
  memoryMdExists: boolean;
  skillCount: number;
  subagentCount: number;
  dailyMemoryCount: number;
  userFileBytes: number;
  userFileLimitBytes: number;
  orgFileBytes: number;
  orgFileLimitBytes: number;
  maxFileBytes: number;
}

export interface UploadedFile {
  fileId: string;
  versionId: string;
  attachmentId?: string;
  path: string;
  sizeBytes: number;
  sha256: string;
}

export interface FileVersion {
  id: string;
  fileId: string;
  logicalPath: string;
  versionNo: number;
  current: boolean;
  sizeBytes: number;
  sha256: string;
  contentType?: string;
  source?: string;
  createdAt?: string;
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/workspace`;
}

async function ensureOk(res: Response, fallback: string): Promise<void> {
  if (res.ok) return;
  let msg = fallback;
  try {
    const text = await res.text();
    if (text) msg = text;
  } catch {
    // ignore
  }
  throw new Error(msg);
}

export async function summary(agentId: string): Promise<WorkspaceSummary> {
  const res = await fetch(base(agentId));
  if (!res.ok) throw new Error('Failed to load workspace summary');
  return res.json();
}

export async function tree(agentId: string, recursive = true): Promise<FileNode[]> {
  const res = await fetch(`${base(agentId)}/files?recursive=${recursive}`);
  if (!res.ok) throw new Error('Failed to load workspace files');
  return res.json();
}

export async function readFile(agentId: string, path: string): Promise<string> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`);
  if (!res.ok) throw new Error('Failed to read file');
  return res.text();
}

export async function uploadFile(agentId: string, file: File, path?: string): Promise<UploadedFile> {
  const params = new URLSearchParams();
  if (path?.trim()) params.set('path', path.trim());
  const form = new FormData();
  form.set('file', file);
  const res = await fetch(`${base(agentId)}/file/upload${params.toString() ? `?${params}` : ''}`, {
    method: 'POST',
    body: form,
  });
  await ensureOk(res, 'Failed to upload file');
  return res.json();
}

export async function fileVersions(agentId: string, path: string): Promise<FileVersion[]> {
  const res = await fetch(`${base(agentId)}/file/versions?path=${encodeURIComponent(path)}`);
  await ensureOk(res, 'Failed to load file versions');
  return res.json();
}

export async function downloadCurrentFile(agentId: string, path: string): Promise<Blob> {
  const res = await fetch(`${base(agentId)}/file/download?path=${encodeURIComponent(path)}`);
  await ensureOk(res, 'Failed to download file');
  return res.blob();
}

export async function downloadFileVersion(agentId: string, versionId: string): Promise<Blob> {
  const res = await fetch(`${base(agentId)}/file/version/${encodeURIComponent(versionId)}/download`);
  await ensureOk(res, 'Failed to download file version');
  return res.blob();
}

export async function restoreFileVersion(
  agentId: string,
  versionId: string,
  path?: string,
): Promise<FileNode> {
  const params = new URLSearchParams();
  if (path?.trim()) params.set('path', path.trim());
  const res = await fetch(
    `${base(agentId)}/file/version/${encodeURIComponent(versionId)}/restore${params.toString() ? `?${params}` : ''}`,
    { method: 'POST' },
  );
  await ensureOk(res, 'Failed to restore file version');
  return res.json();
}
