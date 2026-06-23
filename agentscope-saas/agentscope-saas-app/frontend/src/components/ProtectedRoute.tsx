import { useEffect, useState } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { fetchMe, logout, type MeResponse } from '../auth';

/**
 * Route guard for the authenticated app shell. On mount it verifies `/api/auth/me`; if rejected
 * (401, handled in `api/http.ts`) the user is redirected to `/login`. Dev auth bypass profiles can
 * return a user without a stored token, which keeps local CubeSandbox verification browser-driven.
 */
export default function ProtectedRoute() {
  const [state, setState] = useState<'checking' | 'ok' | 'denied'>('checking');
  const [me, setMe] = useState<MeResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchMe()
      .then(m => { if (!cancelled) { setMe(m); setState('ok'); } })
      .catch(() => { if (!cancelled) setState('denied'); });
    return () => { cancelled = true; };
  }, []);

  if (state === 'checking') {
    return (
      <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#64748b' }}>
        Loading…
      </div>
    );
  }
  if (state === 'denied') {
    logout();
    return <Navigate to="/login" replace />;
  }
  return <Outlet context={{ me }} />;
}
