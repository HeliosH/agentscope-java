import { useEffect, useMemo, useState } from 'react';
import { ArrowRight, MessageSquare, Search } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';
import { type InboxEntry, inbox } from '../api/sessions';
import { DataPanel, EmptyState, MetricStrip, Notice } from './ManagementUI';

function relativeTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)}d ago`;
  return new Date(ms).toLocaleDateString();
}

export default function SessionInboxList({ agentId }: { agentId: string }) {
  const [entries, setEntries] = useState<InboxEntry[]>([]);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setErr(null);
    inbox(agentId, { limit: 100, unreadOnly })
      .then(list => { if (!cancelled) setEntries(list); })
      .catch(cause => { if (!cancelled) setErr(cause instanceof Error ? cause.message : 'Failed to load sessions'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId, unreadOnly]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return entries;
    return entries.filter(entry => [entry.label, entry.lastMessage, entry.sessionId]
      .some(value => value?.toLowerCase().includes(needle)));
  }, [entries, query]);
  const unread = entries.filter(entry => entry.unread).length;

  return (
    <div className="agent-management-page">
      <MetricStrip items={[
        { label: 'Conversations', value: entries.length },
        { label: 'Unread', value: unread, tone: unread ? 'warning' : 'default' },
        { label: 'Visible', value: visible.length },
      ]} />

      <div className="agent-management-toolbar">
        <label className="compact-search">
          <Search size={14} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search conversations" />
        </label>
        <label className="management-check">
          <input type="checkbox" checked={unreadOnly} onChange={event => setUnreadOnly(event.target.checked)} />
          Unread only
        </label>
      </div>

      {err && <Notice tone="error">{err}</Notice>}

      <DataPanel title={`Session history · ${visible.length}`}>
        <div className="session-list">
          {visible.map(entry => {
            const aid = id ?? agentId;
            return (
              <div className={`session-row${entry.unread ? ' is-unread' : ''}`} key={entry.sessionKey}>
                <span className="session-row__icon"><MessageSquare size={15} /></span>
                <button
                  className="session-row__main"
                  type="button"
                  onClick={() => navigate(`/agents/${encodeURIComponent(aid)}/chat?session=${encodeURIComponent(entry.sessionKey)}`)}
                >
                  <span className="session-row__top">
                    <strong>{entry.label ?? entry.sessionId}</strong>
                    <span>{relativeTime(entry.lastActivityMs)}</span>
                  </span>
                  <span className="session-row__message">{entry.lastMessage || 'No message preview'}</span>
                  <span className="mono-text">{entry.sessionKey}</span>
                </button>
                <button
                  className="icon-button"
                  type="button"
                  title="View transcript"
                  aria-label={`View transcript for ${entry.label ?? entry.sessionId}`}
                  onClick={() => navigate(`/agents/${encodeURIComponent(aid)}/sessions/${encodeURIComponent(entry.sessionKey)}`)}
                >
                  <ArrowRight size={15} />
                </button>
              </div>
            );
          })}
          {loading && <EmptyState>Loading sessions...</EmptyState>}
          {!loading && !err && visible.length === 0 && <EmptyState>No sessions match the current filters.</EmptyState>}
        </div>
      </DataPanel>
    </div>
  );
}
