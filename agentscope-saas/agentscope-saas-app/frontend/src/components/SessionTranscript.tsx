import { useEffect, useState } from 'react';
import { ArrowLeft, MessageSquareText, Play, RotateCcw, Trash2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { deleteSession, markRead, resetSession, TurnEntry, turnsWindow } from '../api/sessions';
import ToolCallBlock from './ToolCallBlock';

const PAGE_SIZE = 100;

export default function SessionTranscript({ agentId, sessionKey }: { agentId: string; sessionKey: string }) {
  const [entries, setEntries] = useState<TurnEntry[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [nextBeforeSeq, setNextBeforeSeq] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const navigate = useNavigate();
  const sessionListPath = `/agents/${encodeURIComponent(agentId)}/sessions`;

  async function reload() {
    setErr(null);
    try {
      const page = await turnsWindow(agentId, sessionKey, null, PAGE_SIZE);
      setEntries(page.items);
      setNextBeforeSeq(page.nextBeforeSeq);
      setHasMore(page.hasMore);
    } catch (error: unknown) {
      setErr(error instanceof Error ? error.message : 'Failed to load transcript');
    }
  }

  async function loadMore() {
    if (!hasMore || loadingMore) return;
    setLoadingMore(true);
    setErr(null);
    try {
      const page = await turnsWindow(agentId, sessionKey, nextBeforeSeq, PAGE_SIZE);
      setEntries(previous => [...page.items, ...previous]);
      setNextBeforeSeq(page.nextBeforeSeq);
      setHasMore(page.hasMore);
    } catch (error: unknown) {
      setErr(error instanceof Error ? error.message : 'Failed to load earlier turns');
    } finally {
      setLoadingMore(false);
    }
  }

  useEffect(() => {
    reload();
    markRead(agentId, sessionKey).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, sessionKey]);

  async function handleReset() {
    if (!confirm('Reset this session? History will be cleared.')) return;
    await resetSession(agentId, sessionKey).catch(error => setErr(String(error)));
    reload();
  }

  async function handleDelete() {
    if (!confirm('Delete this session entirely?')) return;
    try {
      await deleteSession(agentId, sessionKey);
      navigate(sessionListPath, { replace: true });
    } catch (error: unknown) {
      setErr(error instanceof Error ? error.message : 'Delete failed');
    }
  }

  return (
    <div className="transcript-page">
      <header className="transcript-header">
        <button className="secondary-button" onClick={() => navigate(sessionListPath)}><ArrowLeft size={15} />Back</button>
        <div className="transcript-title"><MessageSquareText size={18} /><div><h1>Session transcript</h1><code>{sessionKey}</code></div></div>
        <div className="transcript-actions">
          <button className="primary-button" onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/chat?session=${encodeURIComponent(sessionKey)}`)}><Play size={15} />Continue</button>
          <button className="secondary-button" onClick={handleReset}><RotateCcw size={15} />Reset</button>
          <button className="danger-button" onClick={handleDelete}><Trash2 size={15} />Delete</button>
        </div>
      </header>
      {err && <div className="workspace-error">{err}</div>}
      <div className="transcript-thread">
        {hasMore && <button className="secondary-button transcript-load-more" onClick={loadMore} disabled={loadingMore}>{loadingMore ? 'Loading...' : 'Load earlier turns'}</button>}
        {!err && entries.length === 0 && <div className="management-empty">No turns recorded in this session.</div>}
        {entries.map(turn => {
          const role = String(turn.role).toUpperCase();
          if (role === 'TOOL') {
            return <div key={turn.id} className="transcript-tool"><ToolCallBlock toolName={turn.toolName ?? 'tool'} toolCallId={turn.id} result={turn.toolResult ?? turn.toolInput ?? ''} /></div>;
          }
          return (
            <article key={turn.id} className={`transcript-message transcript-message--${role === 'USER' ? 'user' : 'assistant'}`}>
              <div className="transcript-role">{role}</div>
              <div>{turn.content ?? ''}</div>
            </article>
          );
        })}
      </div>
    </div>
  );
}
