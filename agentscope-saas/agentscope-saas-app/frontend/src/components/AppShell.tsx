import { useEffect, useRef, useState } from 'react';
import {
  ChevronDown,
  Database,
  FileClock,
  Gauge,
  LogOut,
  ShieldCheck,
  Users,
} from 'lucide-react';
import { useLocation, useNavigate, useOutletContext, Outlet } from 'react-router-dom';
import { logout, type MeResponse } from '../auth';
import BrandLogo from './BrandLogo';

interface ShellContext {
  me: MeResponse | null;
}

const ADMIN_LINKS = [
  { path: '/admin/users', label: 'Users', icon: Users },
  { path: '/admin/usage', label: 'Usage', icon: Gauge },
  { path: '/admin/sandboxes', label: 'Sandboxes', icon: Database },
  { path: '/admin/memory-events', label: 'Memory', icon: FileClock },
  { path: '/admin/audit', label: 'Audit', icon: ShieldCheck },
];

export default function AppShell() {
  const navigate = useNavigate();
  const location = useLocation();
  const { me } = useOutletContext<ShellContext>();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const admin = me?.role === 'admin' || me?.role === 'platform_admin';
  const workspace = /^\/agents\/[^/]+\/(chat|tasks|workspace|skills|subagents|tools|sessions|settings)/.test(location.pathname);
  const initial = (me?.email ?? '?').charAt(0).toUpperCase();

  useEffect(() => {
    function close(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) setMenuOpen(false);
    }
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className={`app-shell${workspace ? ' app-shell--workspace' : ''}`}>
      {!workspace && (
        <header className="app-shell__header">
          <button className="app-shell__brand" type="button" onClick={() => navigate('/')}>
            <BrandLogo />
            <span>刍狗</span>
          </button>

          <nav className="app-shell__nav" aria-label="Primary navigation">
            <button
              className={`top-nav-button${location.pathname === '/agents' ? ' is-active' : ''}`}
              type="button"
              onClick={() => navigate('/agents')}
            >
              Assistants
            </button>
            {admin && ADMIN_LINKS.map(link => {
              const Icon = link.icon;
              return (
                <button
                  key={link.path}
                  className={`top-nav-button${location.pathname.startsWith(link.path) ? ' is-active' : ''}`}
                  type="button"
                  onClick={() => navigate(link.path)}
                >
                  <Icon size={14} />
                  {link.label}
                </button>
              );
            })}
          </nav>

          <div className="app-shell__spacer" />
          <div className="account-menu" ref={menuRef}>
            <button
              className="account-trigger"
              type="button"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen(open => !open)}
            >
              <span className="account-avatar">{initial}</span>
              <span className="account-email">{me?.email ?? 'user'}</span>
              <ChevronDown size={14} />
            </button>
            {menuOpen && (
              <div className="account-popover">
                <div className="account-popover__meta">
                  <div className="account-popover__email">{me?.email ?? 'user'}</div>
                  <div className="account-popover__role">{me?.role ?? 'member'} · {me?.tier ?? 'standard'}</div>
                </div>
                <button className="account-popover__action" type="button" onClick={handleLogout}>
                  <LogOut size={15} />
                  Sign out
                </button>
              </div>
            )}
          </div>
        </header>
      )}
      <main className="app-shell__content">
        <Outlet context={{ me }} />
      </main>
    </div>
  );
}
