import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AgentDefinition, listAgents } from '../api/agents';
import { inbox, InboxEntry } from '../api/sessions';

const S: Record<string, React.CSSProperties> = {
  root: {
    width: 264, background: '#ffffff', borderRight: '1px solid #e2e8f0',
    display: 'flex', flexDirection: 'column', flexShrink: 0, overflowY: 'auto',
  },
  brand: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '16px 16px 12px',
    borderBottom: '1px solid #f1f5f9', cursor: 'pointer',
  },
  brandIcon: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 30, height: 30, borderRadius: 8,
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', fontSize: '1rem',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35)',
  },
  brandName: { fontWeight: 700, color: '#0f172a', fontSize: '0.98rem', letterSpacing: '-0.01em' },
  newChatBtn: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    width: 'calc(100% - 32px)', margin: '12px 16px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none', borderRadius: 10, padding: '10px 14px',
    fontSize: '0.9rem', fontWeight: 600, cursor: 'pointer',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35)',
  },
  sectionLabel: {
    fontSize: '0.68rem', fontWeight: 700, letterSpacing: '0.1em',
    color: '#94a3b8', textTransform: 'uppercase', padding: '14px 16px 6px',
  },
  row: {
    display: 'flex', alignItems: 'center', gap: 10,
    background: 'transparent', border: '1px solid transparent', borderRadius: 9,
    padding: '8px 12px', cursor: 'pointer', fontSize: '0.86rem', color: '#475569',
    textAlign: 'left', fontWeight: 500, margin: '0 8px 2px', width: 'calc(100% - 16px)',
  },
  rowActive: { background: '#eef2ff', borderColor: '#c7d2fe', color: '#3730a3', fontWeight: 600 },
  rowText: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 },
  time: { fontSize: '0.7rem', color: '#94a3b8', flexShrink: 0 },
  select: {
    width: 'calc(100% - 32px)', margin: '4px 16px',
    padding: '8px 10px', borderRadius: 9, border: '1px solid #e2e8f0',
    background: '#ffffff', color: '#0f172a', fontSize: '0.86rem', fontWeight: 500,
  },
  manageBtn: {
    display: 'flex', alignItems: 'center', gap: 8, width: 'calc(100% - 32px)',
    margin: '8px 16px', background: '#f8fafc', color: '#334155',
    border: '1px solid #e2e8f0', borderRadius: 10, padding: '9px 12px',
    fontSize: '0.86rem', fontWeight: 500, cursor: 'pointer',
  },
  manageMenu: { padding: '0 8px 12px' },
  empty: { padding: '6px 16px', fontSize: '0.8rem', color: '#94a3b8' },
  footer: { marginTop: 'auto', padding: '12px 16px', borderTop: '1px solid #f1f5f9' },
};

const MANAGE: { key: string; label: string; icon: string }[] = [
  { key: 'tasks', label: 'Tasks & Runs', icon: '📋' },
  { key: 'skills', label: 'Skills', icon: '🛠' },
  { key: 'tools', label: 'MCP / Tools', icon: '🧰' },
  { key: 'workspace', label: 'Workspace', icon: '📁' },
  { key: 'subagents', label: 'Subagents', icon: '🧩' },
  { key: 'settings', label: 'Settings', icon: '⚙️' },
];

function fmtTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

export default function ChatSidebar() {
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
      .catch(() => { /* ignore */ });
    return () => { cancelled = true; };
  }, []);

  // Refresh the conversation list whenever the route or the selected session changes
  // (a new chat creates a session, so the sidebar should reflect it).
  useEffect(() => {
    if (!agentId) { setSessions([]); return; }
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
    navigate(sessionKey ? `${base}?session=${encodeURIComponent(sessionKey)}` : base);
  };

  return (
    <div style={S.root}>
      <div style={S.brand} onClick={() => navigate('/agents')} title="Agents hub">
        <span style={S.brandIcon}>⚙</span>
        <span style={S.brandName}>AgentScope Claw</span>
      </div>

      <button type="button" style={S.newChatBtn} onClick={() => goChat()}>
        <span>✏️</span> New chat
      </button>

      {agents.length > 0 && agentId && (
        <select
          style={S.select}
          value={agentId}
          onChange={e => navigate(`/agents/${encodeURIComponent(e.target.value)}/chat`)}
          title="Switch agent"
        >
          {agents.map(a => (
            <option key={a.id} value={a.id}>{a.name}</option>
          ))}
        </select>
      )}

      <div style={S.sectionLabel}>Conversations</div>
      {loadingSessions && <div style={S.empty}>Loading…</div>}
      {!loadingSessions && sessions.length === 0 && (
        <div style={S.empty}>No conversations yet — start a new chat.</div>
      )}
      {sessions.map(s => {
        const active = s.sessionKey === activeSession;
        return (
          <button
            key={s.sessionKey}
            type="button"
            style={{ ...S.row, ...(active ? S.rowActive : {}) }}
            onClick={() => goChat(s.sessionKey)}
            title={s.lastMessage ?? s.label ?? s.sessionId}
          >
            <span style={{ fontSize: '0.9rem', flexShrink: 0 }}>💬</span>
            <span style={S.rowText}>{s.label ?? s.lastMessage ?? 'Conversation'}</span>
            <span style={S.time}>{fmtTime(s.lastActivityMs)}</span>
          </button>
        );
      })}

      <div style={{ flex: 1 }} />

      <div style={S.sectionLabel}>Manage</div>
      <div style={S.manageMenu}>
        {MANAGE.map(t => (
          <button
            key={t.key}
            type="button"
            style={{ ...S.row, ...(location.pathname.startsWith(`/agents/${agentId}/${t.key}`) ? S.rowActive : {}) }}
            onClick={() => navigate(`/agents/${encodeURIComponent(agentId ?? '')}/${t.key}`)}
          >
            <span style={{ fontSize: '0.9rem', flexShrink: 0 }}>{t.icon}</span>
            <span style={S.rowText}>{t.label}</span>
          </button>
        ))}
      </div>

      <div style={S.footer}>
        <button type="button" style={S.row} onClick={() => navigate('/agents')}>
          <span style={{ fontSize: '0.9rem', flexShrink: 0 }}>⊞</span>
          <span style={S.rowText}>Agents hub</span>
        </button>
      </div>
    </div>
  );
}