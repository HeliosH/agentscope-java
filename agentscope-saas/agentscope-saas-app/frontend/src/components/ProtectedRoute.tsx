import { useEffect, useState } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { fetchMe, getToken, logout, type MeResponse } from '../auth';

/**
 * Route guard for the authenticated app shell. On mount, if a token is present it is verified via
 * `/api/auth/me`; if missing or rejected (401, handled in `api/http.ts`) the user is redirected to
 * `/login`. While verifying, a lightweight loading state is shown. Renders `<Outlet/>` so the
 * nested `<AppShell/>` and its child routes mount underneath.
 */
export default function ProtectedRoute() {
  const [state, setState] = useState<'checking' | 'ok' | 'denied'>('checking');
  const [me, setMe] = useState<MeResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    const token = getToken();
    if (!token) {
      setState('denied');
      return;
    }
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
