import { useEffect, useState } from 'react';
import {
  Bot,
  Boxes,
  ChevronRight,
  Files,
  History,
  ListTodo,
  LogOut,
  MessageSquare,
  PanelLeftClose,
  Plus,
  Settings,
  Store,
  Wrench,
  type LucideIcon,
} from 'lucide-react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AgentDefinition, listAgents } from '../api/agents';
import { inbox, InboxEntry } from '../api/sessions';
import { logout, type MeResponse } from '../auth';
import BrandLogo from './BrandLogo';

const MANAGE: { key: string; label: string; icon: LucideIcon }[] = [
  { key: 'tasks', label: 'Tasks & Runs', icon: ListTodo },
  { key: 'workspace', label: 'Workspace', icon: Files },
  { key: 'skills', label: 'Skills', icon: Store },
  { key: 'tools', label: 'MCP / Tools', icon: Wrench },
  { key: 'subagents', label: 'Subagents', icon: Bot },
  { key: 'sessions', label: 'Session history', icon: History },
  { key: 'settings', label: 'Settings', icon: Settings },
];

function fmtTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)}d`;
  return new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

interface ChatSidebarProps {
  open: boolean;
  me: MeResponse | null;
  onClose: () => void;
}

export default function ChatSidebar({ open, me, onClose }: ChatSidebarProps) {
  const { id: agentId } = useParams<{ id?: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const activeSession = searchParams.get('session');
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [sessions, setSessions] = useState<InboxEntry[]>([]);
  const [loadingSessions, setLoadingSessions] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listAgents()
      .then(list => { if (!cancelled) setAgents(list); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!agentId) {
      setSessions([]);
      return;
    }
    let cancelled = false;
    setLoadingSessions(true);
    inbox(agentId, { limit: 50 })
      .then(list => { if (!cancelled) setSessions(list); })
      .catch(() => { if (!cancelled) setSessions([]); })
      .finally(() => { if (!cancelled) setLoadingSessions(false); });
    return () => { cancelled = true; };
  }, [agentId, activeSession, location.pathname]);

  const goChat = (sessionKey?: string | null) => {
    const base = `/agents/${encodeURIComponent(agentId ?? '')}/chat`;
    navigate(sessionKey
      ? `${base}?session=${encodeURIComponent(sessionKey)}`
      : `${base}?new=${Date.now()}`);
  };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const initial = (me?.email ?? '?').charAt(0).toUpperCase();

  return (
    <aside className={`agent-sidebar${open ? ' is-open' : ''}`} aria-label="Agent navigation">
      <div className="agent-sidebar__brand-row">
        <button className="sidebar-brand" type="button" onClick={() => navigate('/')} title="刍狗">
          <BrandLogo />
          <span className="sidebar-brand__label">刍狗</span>
        </button>
        <button
          className="icon-button sidebar-close-button"
          type="button"
          title="Close navigation"
          aria-label="Close navigation"
          onClick={onClose}
        >
          <PanelLeftClose size={16} />
        </button>
      </div>

      <button className="sidebar-new-task" type="button" onClick={() => goChat()}>
        <Plus size={16} />
        New task
      </button>

      {agents.length > 0 && agentId && (
        <div className="sidebar-agent-picker">
          <label htmlFor="agent-switcher">Assistant</label>
          <select
            id="agent-switcher"
            value={agentId}
            onChange={event => navigate(`/agents/${encodeURIComponent(event.target.value)}/chat`)}
          >
            {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name}</option>)}
          </select>
        </div>
      )}

      <div className="sidebar-scroll">
        <div className="sidebar-section-label">Recent</div>
        {loadingSessions && <div className="sidebar-empty">Loading conversations...</div>}
        {!loadingSessions && sessions.length === 0 && (
          <div className="sidebar-empty">No conversations yet.</div>
        )}
        {sessions.map(session => (
          <button
            key={session.sessionKey}
            className={`sidebar-row${session.sessionKey === activeSession ? ' is-active' : ''}`}
            type="button"
            onClick={() => goChat(session.sessionKey)}
            title={session.lastMessage ?? session.label ?? session.sessionId}
          >
            <MessageSquare size={14} />
            <span className="sidebar-row__text">{session.label ?? session.lastMessage ?? 'Conversation'}</span>
            <span className="sidebar-row__time">{fmtTime(session.lastActivityMs)}</span>
          </button>
        ))}

        <div className="sidebar-section-label">Configure</div>
        {MANAGE.map(item => {
          const Icon = item.icon;
          const active = location.pathname.startsWith(`/agents/${agentId}/${item.key}`);
          return (
            <button
              key={item.key}
              className={`sidebar-row${active ? ' is-active' : ''}`}
              type="button"
              onClick={() => navigate(`/agents/${encodeURIComponent(agentId ?? '')}/${item.key}`)}
            >
              <Icon size={14} />
              <span className="sidebar-row__text">{item.label}</span>
            </button>
          );
        })}
        <button className="sidebar-row" type="button" onClick={() => navigate('/agents')}>
          <Boxes size={14} />
          <span className="sidebar-row__text">All assistants</span>
          <ChevronRight size={13} />
        </button>
      </div>

      <footer className="sidebar-footer">
        <div className="sidebar-account">
          <span className="account-avatar">{initial}</span>
          <span className="sidebar-account__copy">
            <span className="sidebar-account__name">{me?.email ?? 'user'}</span>
            <span className="sidebar-account__role">{me?.role ?? 'member'} · {me?.tier ?? 'standard'}</span>
          </span>
          <button
            className="icon-button"
            type="button"
            title="Sign out"
            aria-label="Sign out"
            onClick={handleLogout}
          >
            <LogOut size={15} />
          </button>
        </div>
      </footer>
    </aside>
  );
}
