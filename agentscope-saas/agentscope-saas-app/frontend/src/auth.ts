/**
 * JWT auth store for the SaaS frontend. The token is held in localStorage under `saas_jwt` and
 * injected on every `/api/**` request by the fetch override in `api/http.ts`. paw had no auth
 * (bare fetch); this layer retrofits multi-tenant login onto the forked UI.
 */

const TOKEN_KEY = 'saas_jwt';

export interface AuthUser {
  token: string;
  userId: string;
  orgId: string;
  email: string;
  role: string;
  tier: string;
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* ignore quota */
  }
}

export function clearToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

async function parseAuthError(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body?.error ?? `Request failed (${res.status})`;
  } catch {
    return `Request failed (${res.status})`;
  }
}

export async function login(email: string, password: string): Promise<AuthUser> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(await parseAuthError(res));
  const user = (await res.json()) as AuthUser;
  setToken(user.token);
  return user;
}

export async function register(email: string, password: string, displayName: string): Promise<AuthUser> {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, displayName }),
  });
  if (!res.ok) throw new Error(await parseAuthError(res));
  const user = (await res.json()) as AuthUser;
  setToken(user.token);
  return user;
}

export interface MeResponse {
  userId: string;
  orgId: string;
  email: string;
  role: string;
  tier: string;
}

/** Verifies the stored token is still valid by hitting /api/auth/me. */
export async function fetchMe(): Promise<MeResponse> {
  const res = await fetch('/api/auth/me');
  if (!res.ok) throw new Error(`unauthenticated (${res.status})`);
  return res.json();
}

export function logout(): void {
  clearToken();
}
