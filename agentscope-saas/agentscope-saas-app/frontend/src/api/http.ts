/**
 * Global fetch override installed by importing this module once at app boot (see `main.tsx`).
 * Injects `Authorization: Bearer <token>` on `/api/**` requests using the JWT from `auth.ts`, and
 * on a 401 from a protected endpoint clears the token and redirects to `/login`.
 *
 * The original `Response` is returned unchanged (no body buffering) so SSE streaming in
 * `chat.ts` — which reads `res.body.getReader()` incrementally — keeps working.
 */
import { getToken, clearToken } from '../auth';

const ORIGINAL_FETCH = window.fetch.bind(window);

// Endpoints that perform their own credential handling; a 401 here is a normal "wrong password"
// / "expired token" signal for the caller to render, not a session-expiry redirect.
const PUBLIC_AUTH_PATHS = ['/api/auth/login', '/api/auth/register', '/api/auth/sso/'];

function isPublicAuthPath(url: string): boolean {
  try {
    const u = new URL(url, window.location.origin);
    return PUBLIC_AUTH_PATHS.some(p => u.pathname === p || u.pathname.startsWith(p));
  } catch {
    return false;
  }
}

window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
  const isApi = url.startsWith('/api/') || url.includes('/api/');

  let finalInit = init;
  if (isApi && !isPublicAuthPath(url)) {
    const token = getToken();
    if (token) {
      const headers = new Headers(init?.headers);
      if (!headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`);
      finalInit = { ...init, headers };
    }
  }

  const res = await ORIGINAL_FETCH(input as RequestInfo | URL, finalInit);

  if (res.status === 401 && isApi && !isPublicAuthPath(url)) {
    clearToken();
    // Avoid redirect loops when already on /login.
    if (window.location.pathname !== '/login') {
      window.location.assign('/login');
    }
  }
  return res;
};
