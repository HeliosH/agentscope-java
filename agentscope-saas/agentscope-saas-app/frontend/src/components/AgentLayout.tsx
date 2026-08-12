import { useEffect, useState } from 'react';
import { ArrowLeft, Menu } from 'lucide-react';
import { Outlet, useLocation, useNavigate, useParams, useOutletContext } from 'react-router-dom';
import { AgentDefinition, getAgent } from '../api/agents';
import ChatSidebar from './ChatSidebar';
import type { MeResponse } from '../auth';

const SUB_TITLES: Record<string, string> = {
  tasks: 'Tasks & Runs',
  workspace: 'Workspace',
  skills: 'Skills',
  subagents: 'Subagents',
  tools: 'MCP / Tools',
  sessions: 'Sessions',
  settings: 'Settings',
};

export default function AgentLayout() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    getAgent(id)
      .then(value => { if (!cancelled) setAgent(value); })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [id]);

  useEffect(() => setSidebarOpen(false), [location.pathname, location.search]);

  if (!id) return <div style={{ padding: 32 }}>Missing agent id.</div>;

  const sub = Object.keys(SUB_TITLES).find(value =>
    location.pathname.startsWith(`/agents/${id}/${value}`)) ?? 'chat';
  const chat = sub === 'chat';

  return (
    <div className="agent-layout">
      <ChatSidebar
        open={sidebarOpen}
        me={me}
        onClose={() => setSidebarOpen(false)}
      />
      <button
        className={`sidebar-backdrop${sidebarOpen ? ' is-open' : ''}`}
        type="button"
        aria-label="Dismiss navigation"
        onClick={() => setSidebarOpen(false)}
      />
      <section className="agent-layout__main">
        {!chat && (
          <header className="workspace-header">
            <button
              className="icon-button mobile-menu-button"
              type="button"
              title="Open navigation"
              aria-label="Open navigation"
              onClick={() => setSidebarOpen(true)}
            >
              <Menu size={17} />
            </button>
            <button
              className="quiet-button"
              type="button"
              onClick={() => navigate(`/agents/${encodeURIComponent(id)}/chat`)}
            >
              <ArrowLeft size={15} />
              Chat
            </button>
            <span className="workspace-header__title">{SUB_TITLES[sub] ?? sub}</span>
            <span className="workspace-header__spacer" />
            {agent && <span className="workspace-header__agent">{agent.name}</span>}
          </header>
        )}
        <div className={`agent-layout__content${chat ? ' agent-layout__content--chat' : ''}`}>
          <Outlet context={{
            agentId: id,
            agent,
            me,
            openSidebar: () => setSidebarOpen(true),
          }} />
        </div>
      </section>
    </div>
  );
}
