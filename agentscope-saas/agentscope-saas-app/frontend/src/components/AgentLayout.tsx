import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate, useParams, useOutletContext } from 'react-router-dom';
import { AgentDefinition, getAgent } from '../api/agents';
import ChatSidebar from '../components/ChatSidebar';
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

/**
 * Chat-first agent layout (OpenClaw/ChatGPT style): a left conversation sidebar
 * (agent switcher + session list + manage menu) and a main area. The chat is the
 * primary full-width view; management sub-pages render a slim header with a
 * "back to chat" affordance.
 */
export default function AgentLayout() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const [agent, setAgent] = useState<AgentDefinition | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    getAgent(id)
      .then(a => { if (!cancelled) setAgent(a); })
      .catch(() => { /* context agent is optional */ });
    return () => { cancelled = true; };
  }, [id]);

  if (!id) return <div style={{ padding: 32 }}>Missing agent id.</div>;

  const sub =
    (Object.keys(SUB_TITLES) as string[]).find(t =>
      location.pathname.startsWith(`/agents/${id}/${t}`)) ?? 'chat';

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      <ChatSidebar />
      <div
        style={{
          flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0,
          overflow: 'hidden',
        }}
      >
        {sub !== 'chat' && (
          <div
            style={{
              display: 'flex', alignItems: 'center', gap: 12,
              padding: '12px 24px', borderBottom: '1px solid #e2e8f0',
              background: '#ffffff', flexShrink: 0,
            }}
          >
            <button
              type="button"
              onClick={() => navigate(`/agents/${encodeURIComponent(id)}/chat`)}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
                borderRadius: 8, padding: '7px 14px', cursor: 'pointer',
                fontSize: '0.85rem', fontWeight: 500,
              }}
            >
              ← Chat
            </button>
            <span style={{ fontSize: '1.05rem', fontWeight: 700, color: '#0f172a' }}>
              {SUB_TITLES[sub] ?? sub}
            </span>
            <span style={{ flex: 1 }} />
            {agent && (
              <span style={{ fontSize: '0.82rem', color: '#94a3b8' }}>
                {agent.name}
              </span>
            )}
          </div>
        )}
        <div style={{ flex: 1, overflow: 'auto', background: '#f8fafc' }}>
          <Outlet context={{ agentId: id, agent, me }} />
        </div>
      </div>
    </div>
  );
}