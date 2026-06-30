import { useState } from 'react';
import { useLocation, useNavigate, useOutletContext, Outlet } from 'react-router-dom';
import AgentRail from './AgentRail';
import { logout, type MeResponse } from '../auth';

interface ShellContext {
  me: MeResponse | null;
}

export default function AppShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const { me } = useOutletContext<ShellContext>();
  const [menuOpen, setMenuOpen] = useState(false);
  const admin = me?.role === 'admin';
  const adminActive = location.pathname.startsWith('/admin/');

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  const initial = (me?.email ?? '?').charAt(0).toUpperCase();

  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' }}>
      <AgentRail />

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{
          height: 64, background: '#ffffff', borderBottom: '1px solid #e2e8f0',
          display: 'flex', alignItems: 'center', padding: '0 28px', flexShrink: 0,
          justifyContent: 'space-between',
        }}>
          <span
            onClick={() => navigate('/agents')}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 10,
              fontWeight: 700, color: '#0f172a', fontSize: '1.05rem',
              letterSpacing: '-0.01em', cursor: 'pointer',
            }}
          >
            <span style={{
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              width: 30, height: 30, borderRadius: 8,
              background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
              color: '#ffffff', fontSize: '1rem',
              boxShadow: '0 2px 6px rgba(99,102,241,0.35)',
            }}>⚙</span>
            AgentScope Claw
          </span>

          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            {admin && (
              <button
                type="button"
                onClick={() => navigate('/admin/sandboxes')}
                style={{
                  background: adminActive ? '#0f172a' : '#ffffff',
                  color: adminActive ? '#ffffff' : '#334155',
                  border: `1px solid ${adminActive ? '#0f172a' : '#e2e8f0'}`,
                  borderRadius: 9,
                  padding: '8px 12px',
                  fontSize: '0.85rem',
                  fontWeight: 600,
                  cursor: 'pointer',
                }}
              >
                Sandboxes
              </button>
            )}
            <div style={{ position: 'relative' }}>
              <button
                type="button"
                onClick={() => setMenuOpen(o => !o)}
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 8, cursor: 'pointer',
                  background: '#f1f5f9', border: '1px solid #e2e8f0', borderRadius: 999,
                  padding: '4px 12px 4px 4px', color: '#334155', fontSize: '0.85rem', fontWeight: 500,
                }}
              >
                <span style={{
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  width: 26, height: 26, borderRadius: '50%',
                  background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
                  color: '#ffffff', fontWeight: 600, fontSize: '0.8rem',
                }}>{initial}</span>
                {me?.email ?? 'user'}
              </button>
              {menuOpen && (
                <div style={{
                  position: 'absolute', right: 0, top: 'calc(100% + 6px)', minWidth: 200,
                  background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 10,
                  boxShadow: '0 8px 24px rgba(15,23,42,0.12)', padding: 6, zIndex: 50,
                }}>
                  <div style={{ padding: '8px 12px', borderBottom: '1px solid #f1f5f9', marginBottom: 4 }}>
                    <div style={{ fontSize: '0.85rem', fontWeight: 600, color: '#0f172a' }}>{me?.email ?? 'user'}</div>
                    <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>
                      {me?.role ?? 'member'} · {me?.tier ?? 'standard'}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={handleLogout}
                    style={{
                      width: '100%', textAlign: 'left', cursor: 'pointer',
                      background: 'transparent', border: 'none', borderRadius: 6,
                      padding: '8px 12px', color: '#dc2626', fontSize: '0.85rem', fontWeight: 500,
                    }}
                  >
                    Sign out
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        <div style={{ flex: 1, overflow: 'auto' }}>
          <Outlet context={{ me }} />
        </div>
      </div>
    </div>
  );
}
