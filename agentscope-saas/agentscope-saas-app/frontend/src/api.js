// Minimal API helpers for the SaaS console. All calls are same-origin (the SPA is served by the
// backend); the bearer token is attached for authenticated routes.

const TOKEN_KEY = 'saas.token';
const SESSION_KEY = 'saas.session';

export function loadSession() {
  try {
    const raw = localStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function saveSession(session) {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(session));
  localStorage.setItem(SESSION_KEY, session ? JSON.stringify(session) : '');
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(SESSION_KEY);
}

function authHeaders(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

export async function login(email, password) {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `login failed (${res.status})`);
  }
  return res.json();
}

export async function register(email, password, displayName) {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, displayName }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `registration failed (${res.status})`);
  }
  return res.json();
}

export async function fetchSessions(token) {
  const res = await fetch('/api/sessions', { headers: authHeaders(token) });
  if (!res.ok) throw new Error(`failed to load sessions (${res.status})`);
  return res.json();
}

export async function fetchMessages(token, sessionId) {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/messages`, {
    headers: authHeaders(token),
  });
  if (!res.ok) throw new Error(`failed to load history (${res.status})`);
  return res.json();
}

// --- Workspace + skills (Phase B5′). The agentId is a real UUID from GET /api/agents; it acts
// only as an org-scoped guard — the workspace itself is isolated per user server-side. ---

export async function fetchAgents(token) {
  const res = await fetch('/api/agents', { headers: authHeaders(token) });
  if (!res.ok) throw new Error(`failed to load agents (${res.status})`);
  return res.json();
}

export async function fetchFileTree(token, agentId, recursive = true) {
  const res = await fetch(
    `/api/agents/${agentId}/workspace/files?recursive=${recursive}`,
    { headers: authHeaders(token) },
  );
  if (!res.ok) throw new Error(`failed to load files (${res.status})`);
  return res.json();
}

export async function readFile(token, agentId, path) {
  const res = await fetch(
    `/api/agents/${agentId}/workspace/file?path=${encodeURIComponent(path)}`,
    { headers: authHeaders(token) },
  );
  if (!res.ok) throw new Error(`failed to read file (${res.status})`);
  return res.text();
}

export async function writeFile(token, agentId, path, content) {
  const res = await fetch(
    `/api/agents/${agentId}/workspace/file?path=${encodeURIComponent(path)}`,
    { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ content }) },
  );
  if (!res.ok) throw new Error(`failed to write file (${res.status})`);
  return res.json();
}

export async function deleteFile(token, agentId, path) {
  const res = await fetch(
    `/api/agents/${agentId}/workspace/file?path=${encodeURIComponent(path)}`,
    { method: 'DELETE', headers: authHeaders(token) },
  );
  if (!res.ok) throw new Error(`failed to delete file (${res.status})`);
}

export async function fetchSkills(token, agentId) {
  const res = await fetch(`/api/agents/${agentId}/skills/workspace`, {
    headers: authHeaders(token),
  });
  if (!res.ok) throw new Error(`failed to load skills (${res.status})`);
  return res.json();
}
