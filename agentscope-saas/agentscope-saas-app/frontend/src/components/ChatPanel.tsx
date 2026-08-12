import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowUp,
  Bot,
  Check,
  FilePlus2,
  ListTodo,
  Menu,
  Paperclip,
  ShieldAlert,
  Sparkles,
  X,
} from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import { ConfirmToolCall, currentSession, stream, type ChatEvent, type ChatRequest, type ConfirmResultInput } from '../api/chat';
import { TurnEntry, turnsWindow } from '../api/sessions';
import ToolCallBlock from './ToolCallBlock';
import { uploadFile } from '../api/workspace';
import RunInspector from './RunInspector';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

interface Message {
  id: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  confirmTools?: ConfirmToolCall[];
  pending?: boolean;
}

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;

const STORAGE_PREFIX = 'claw_chat_session:';
const storageKey = (agentId: string) => `${STORAGE_PREFIX}${agentId}`;

function turnsToMessages(turns: TurnEntry[]): Message[] {
  const out: Message[] = [];
  for (const t of turns) {
    const role = String(t.role).toUpperCase();
    if (role === 'USER') {
      out.push({ id: t.id, role: 'user', text: t.content ?? '', tools: [] });
    } else if (role === 'ASSISTANT') {
      out.push({ id: t.id, role: 'assistant', text: t.content ?? '', tools: [] });
    } else if (role === 'TOOL') {
      const last = out.length > 0 ? out[out.length - 1] : null;
      const tool: ToolEntry = {
        id: t.id,
        name: t.toolName ?? 'tool',
        input: t.toolInput ?? undefined,
        result: t.toolResult ?? undefined,
      };
      if (last && last.role === 'assistant') {
        last.tools = [...last.tools, tool];
      } else {
        out.push({ id: `${t.id}-host`, role: 'assistant', text: '', tools: [tool] });
      }
    }
  }
  return out;
}

interface ChatPanelProps {
  agentId: string;
  agentName?: string;
  onOpenSidebar?: () => void;
}

const SUGGESTIONS = [
  { icon: FilePlus2, text: 'Summarize the latest files in my workspace' },
  { icon: ListTodo, text: 'Create a plan and execute the task step by step' },
];

export default function ChatPanel({ agentId, agentName, onOpenSidebar }: ChatPanelProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedSession = searchParams.get('session');
  const newTaskNonce = searchParams.get('new');
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [loadingEarlier, setLoadingEarlier] = useState(false);
  const [hasEarlier, setHasEarlier] = useState(false);
  const [nextBeforeSeq, setNextBeforeSeq] = useState<number | null>(null);
  const [sessionKey, setSessionKey] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const preserveScrollRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const skipSessionRestoreRef = useRef<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [inspectorOpen, setInspectorOpen] = useState(false);

  const persistSession = useCallback((key: string | null) => {
    if (key) {
      try { localStorage.setItem(storageKey(agentId), key); } catch { /* ignore quota */ }
    } else {
      try { localStorage.removeItem(storageKey(agentId)); } catch { /* ignore */ }
    }
  }, [agentId]);

  // On agent change: pick a session (URL > localStorage > backend default) and rehydrate.
  useEffect(() => {
    if (requestedSession && skipSessionRestoreRef.current === requestedSession) {
      skipSessionRestoreRef.current = null;
      return;
    }
    let cancelled = false;
    setMessages([]);
    setInput('');
    setRestoring(true);
    setHasEarlier(false);
    setNextBeforeSeq(null);
    setActiveRunId(null);
    setInspectorOpen(false);

    const urlKey = requestedSession;
    const explicitlyNew = newTaskNonce !== null;
    const stored = (() => { try { return localStorage.getItem(storageKey(agentId)); } catch { return null; } })();
    const initialKey = explicitlyNew ? null : urlKey || stored || null;

    async function run() {
      let key: string | null = initialKey;
      try {
        if (!key && !explicitlyNew) {
          const cur = await currentSession(agentId);
          key = cur.sessionKey;
          if (cur.latestRunId) setActiveRunId(cur.latestRunId);
        } else if (key) {
          const cur = await currentSession(agentId);
          if (cur.sessionKey === key && cur.latestRunId) setActiveRunId(cur.latestRunId);
        }
      } catch {
        // A missing current-session pointer does not prevent loading an explicit session.
      }
      if (cancelled) return;
      setSessionKey(key);
      if (key) {
        try {
          const page = await turnsWindow(agentId, key);
          if (cancelled) return;
          setMessages(turnsToMessages(page.items));
          setNextBeforeSeq(page.nextBeforeSeq);
          setHasEarlier(page.hasMore);
        } catch {
          // tolerate failure: empty thread
        }
      }
      if (cancelled) return;
      setRestoring(false);
      // Reflect the resolved key in the URL (replace so we don't pollute history).
      if (key && key !== urlKey) {
        const next = new URLSearchParams(searchParams);
        next.delete('new');
        next.set('session', key);
        skipSessionRestoreRef.current = key;
        setSearchParams(next, { replace: true });
      }
    }
    run();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, requestedSession, newTaskNonce]);

  async function loadEarlier() {
    if (!sessionKey || !hasEarlier || loadingEarlier) return;
    const scroller = threadRef.current;
    const previousHeight = scroller?.scrollHeight ?? 0;
    const previousTop = scroller?.scrollTop ?? 0;
    setLoadingEarlier(true);
    try {
      const page = await turnsWindow(agentId, sessionKey, nextBeforeSeq);
      preserveScrollRef.current = true;
      setMessages(prev => [...turnsToMessages(page.items), ...prev]);
      setNextBeforeSeq(page.nextBeforeSeq);
      setHasEarlier(page.hasMore);
      requestAnimationFrame(() => {
        if (scroller) scroller.scrollTop = previousTop + scroller.scrollHeight - previousHeight;
        preserveScrollRef.current = false;
      });
    } catch {
      preserveScrollRef.current = false;
    } finally {
      setLoadingEarlier(false);
    }
  }

  useEffect(() => {
    if (!preserveScrollRef.current) {
      threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
    }
  }, [messages]);

  const canSend = useMemo(() => !busy && !restoring && input.trim().length > 0, [busy, restoring, input]);

  function applyChatEvent(evt: ChatEvent, replyId: string) {
    if (evt.type === 'run_started') {
      if (evt.runId) {
        setActiveRunId(evt.runId);
        setInspectorOpen(true);
      }
    } else if (evt.type === 'token') {
      const chunk = evt.data ?? '';
      setMessages(prev => prev.map(m => m.id === replyId ? { ...m, text: m.text + chunk } : m));
    } else if (evt.type === 'tool_call') {
      const entry: ToolEntry = {
        id: `${evt.toolName ?? 'tool'}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        name: evt.toolName ?? 'tool',
        input: evt.toolInput,
      };
      setMessages(prev => prev.map(m => m.id === replyId ? { ...m, tools: [...m.tools, entry] } : m));
    } else if (evt.type === 'tool_result') {
      setMessages(prev => prev.map(m => {
        if (m.id !== replyId) return m;
        const tools = [...m.tools];
        for (let i = tools.length - 1; i >= 0; i--) {
          if (tools[i].name === evt.toolName && !tools[i].result) {
            tools[i] = { ...tools[i], result: evt.toolResult };
            return { ...m, tools };
          }
        }
        tools.push({
          id: `${evt.toolName ?? 'tool'}-${Date.now()}`,
          name: evt.toolName ?? 'tool',
          result: evt.toolResult,
        });
        return { ...m, tools };
      }));
    } else if (evt.type === 'confirm_required') {
      setMessages(prev => prev.map(m => m.id === replyId
        ? { ...m, pending: false, confirmTools: evt.confirmTools ?? [] }
        : m));
    } else if (evt.type === 'done') {
      if (evt.sessionKey) {
        setSessionKey(evt.sessionKey);
        persistSession(evt.sessionKey);
        const next = new URLSearchParams(searchParams);
        if (next.get('session') !== evt.sessionKey) {
          next.delete('new');
          next.set('session', evt.sessionKey);
          skipSessionRestoreRef.current = evt.sessionKey;
          setSearchParams(next, { replace: true });
        }
      }
      setMessages(prev => prev.map(m => m.id === replyId ? { ...m, pending: false } : m));
    } else if (evt.type === 'error') {
      setMessages(prev => prev.map(m => m.id === replyId
        ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${evt.error ?? 'unknown'}` }
        : m));
    }
  }

  async function runChatStream(req: ChatRequest, replyId: string) {
    for await (const evt of stream(agentId, req)) {
      applyChatEvent(evt, replyId);
    }
  }

  function appendError(replyId: string, message: string) {
    setMessages(prev => prev.map(m => m.id === replyId
      ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${message}` }
      : m));
  }

  async function handleUpload(file: File) {
    if (busy || !file) return;
    setUploading(true);
    try {
      const uploaded = await uploadFile(agentId, file);
      const marker = uploaded.path
        ? `[上传文件: ${uploaded.path}，请读取] `
        : `[上传文件: ${file.name}，请读取] `;
      setInput(prev => marker + (prev ?? ''));
    } catch (e: unknown) {
      alert(e instanceof Error ? e.message : '文件上传失败');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    if (inputRef.current) inputRef.current.style.height = 'auto';
    setBusy(true);
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], pending: true };
    setMessages(prev => [...prev, userMsg, replyMsg]);

    try {
      await runChatStream({ message: text, sessionId: sessionKey ?? undefined }, replyMsg.id);
    } catch (e: unknown) {
      appendError(replyMsg.id, e instanceof Error ? e.message : 'stream failed');
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  async function handleConfirm(source: Message, confirmed: boolean) {
    if (busy || !source.confirmTools?.length) return;
    const confirmResults: ConfirmResultInput[] = source.confirmTools.map(tool => ({
      confirmed,
      toolCallId: tool.id,
      toolName: tool.name,
      input: tool.input,
    }));
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], pending: true };
    setBusy(true);
    setMessages(prev => [
      ...prev.map(m => m.id === source.id ? { ...m, confirmTools: undefined } : m),
      replyMsg,
    ]);
    try {
      await runChatStream({
        message: '',
        sessionId: sessionKey ?? undefined,
        confirmResults,
      }, replyMsg.id);
    } catch (e: unknown) {
      appendError(replyMsg.id, e instanceof Error ? e.message : 'stream failed');
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  function resizeComposer(element: HTMLTextAreaElement) {
    element.style.height = 'auto';
    element.style.height = `${Math.min(element.scrollHeight, 180)}px`;
  }

  function chooseSuggestion(text: string) {
    setInput(text);
    requestAnimationFrame(() => inputRef.current?.focus());
  }

  return (
    <div className="chat-root">
      <header className="chat-header">
        <button
          className="icon-button mobile-menu-button"
          type="button"
          title="Open navigation"
          aria-label="Open navigation"
          onClick={onOpenSidebar}
        >
          <Menu size={17} />
        </button>
        <span className="chat-header__title">{agentName || 'Assistant'}</span>
        <span className="chat-header__status">
          <span className="chat-header__status-dot" />
          Ready
        </span>
        {sessionKey && <span className="chat-header__meta" title={sessionKey}>Session {sessionKey.slice(0, 8)}</span>}
        <span className="chat-header__spacer" />
        {activeRunId && (
          <button
            className={`quiet-button${inspectorOpen ? ' is-active' : ''}`}
            type="button"
            aria-expanded={inspectorOpen}
            onClick={() => setInspectorOpen(open => !open)}
          >
            <ListTodo size={15} />
            Run details
          </button>
        )}
      </header>

      <div className="chat-workspace">
        <main className="chat-main">
          <div className="chat-thread" ref={threadRef}>
            <div className="chat-thread__inner">
              {hasEarlier && (
                <button
                  type="button"
                  className="quiet-button chat-load-earlier"
                  onClick={() => void loadEarlier()}
                  disabled={loadingEarlier}
                >
                  {loadingEarlier ? 'Loading earlier messages...' : 'Load earlier messages'}
                </button>
              )}

              {restoring && messages.length === 0 && (
                <div className="chat-empty">
                  <div className="message-pending" aria-label="Loading conversation">
                    <span /><span /><span />
                  </div>
                </div>
              )}

              {!restoring && messages.length === 0 && (
                <div className="chat-empty">
                  <span className="chat-empty__mark"><Sparkles size={19} /></span>
                  <h1>What should we work on?</h1>
                  <p>Ask a question or assign a task. The assistant can plan, use internal tools, and return workspace artifacts.</p>
                  <div className="chat-suggestions">
                    {SUGGESTIONS.map(suggestion => {
                      const Icon = suggestion.icon;
                      return (
                        <button
                          key={suggestion.text}
                          className="chat-suggestion"
                          type="button"
                          onClick={() => chooseSuggestion(suggestion.text)}
                        >
                          <Icon size={15} />
                          {suggestion.text}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {messages.map(message => (
                <article
                  key={message.id}
                  className={`message-row message-row--${message.role}`}
                >
                  {message.role === 'assistant' && (
                    <span className="message-avatar" aria-hidden="true"><Bot size={15} /></span>
                  )}
                  <div className="message-content">
                    {message.tools.length > 0 && (
                      <div>
                        {message.tools.map(tool => (
                          <ToolCallBlock
                            key={tool.id}
                            toolName={tool.name}
                            toolCallId={tool.id}
                            result={tool.result}
                          />
                        ))}
                      </div>
                    )}
                    {message.text}
                    {!message.text && message.pending && (
                      <span className="message-pending" aria-label="Assistant is working">
                        <span /><span /><span />
                      </span>
                    )}
                    {message.confirmTools && message.confirmTools.length > 0 && (
                      <div className="approval-box">
                        <div className="approval-box__title">
                          <ShieldAlert size={15} />
                          Approval required
                        </div>
                        {message.confirmTools.map((tool, index) => (
                          <div key={tool.id || `${tool.name}-${index}`} className="approval-tool">
                            <strong>{tool.name}</strong>
                            {tool.input && <pre>{JSON.stringify(tool.input, null, 2)}</pre>}
                          </div>
                        ))}
                        <div className="approval-box__actions">
                          <button
                            type="button"
                            className="quiet-button"
                            disabled={busy}
                            onClick={() => void handleConfirm(message, false)}
                          >
                            <X size={14} />
                            Deny
                          </button>
                          <button
                            type="button"
                            className="primary-button"
                            disabled={busy}
                            onClick={() => void handleConfirm(message, true)}
                          >
                            <Check size={14} />
                            Approve
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                </article>
              ))}
            </div>
          </div>

          <div className="chat-composer">
            <div className="chat-composer__box">
              <input
                ref={fileInputRef}
                type="file"
                hidden
                onChange={event => {
                  const file = event.target.files?.[0];
                  if (file) void handleUpload(file);
                }}
                disabled={busy || uploading}
              />
              <button
                className="icon-button"
                type="button"
                onClick={() => fileInputRef.current?.click()}
                disabled={busy || uploading}
                title="Upload a workspace file"
                aria-label="Upload a workspace file"
              >
                {uploading ? <Paperclip size={16} className="is-spinning" /> : <Paperclip size={16} />}
              </button>
              <textarea
                ref={inputRef}
                value={input}
                onChange={event => {
                  setInput(event.target.value);
                  resizeComposer(event.target);
                }}
                onKeyDown={handleKeyDown}
                placeholder={restoring ? 'Loading...' : `Message ${agentName || agentId}`}
                rows={1}
                autoFocus
                disabled={restoring}
              />
              <button
                className="chat-send-button"
                type="button"
                onClick={handleSend}
                disabled={!canSend}
                title="Send message"
                aria-label="Send message"
              >
                <ArrowUp size={17} strokeWidth={2.4} />
              </button>
            </div>
            <div className="chat-composer__hint">Enter to send · Shift + Enter for a new line</div>
          </div>
        </main>

        {activeRunId && inspectorOpen && (
          <RunInspector
            agentId={agentId}
            runId={activeRunId}
            onClose={() => setInspectorOpen(false)}
          />
        )}
      </div>
    </div>
  );
}
