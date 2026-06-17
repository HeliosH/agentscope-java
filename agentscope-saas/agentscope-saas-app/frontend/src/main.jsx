import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { runChat } from './aguiStream.js';
import {
  clearSession,
  fetchMessages,
  fetchSessions,
  loadSession,
  login,
  register,
  saveSession,
} from './api.js';

const styles = {
  page: { fontFamily: 'system-ui, sans-serif', height: '100vh', margin: 0, display: 'flex', flexDirection: 'column' },
  topbar: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 16px', borderBottom: '1px solid #e2e2e2', background: '#fff' },
  body: { display: 'flex', flex: 1, minHeight: 0 },
  sidebar: { width: 260, borderRight: '1px solid #e2e2e2', overflowY: 'auto', background: '#fafafa' },
  main: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 },
  card: { border: '1px solid #e2e2e2', borderRadius: 12, padding: 20, marginBottom: 16 },
  input: { width: '100%', padding: 10, borderRadius: 8, border: '1px solid #ccc', boxSizing: 'border-box', marginBottom: 8 },
  button: { padding: '10px 16px', borderRadius: 8, border: 'none', background: '#2563eb', color: '#fff', cursor: 'pointer' },
  ghostBtn: { padding: '6px 10px', borderRadius: 8, border: '1px solid #ccc', background: '#fff', cursor: 'pointer', fontSize: 13 },
  msgUser: { background: '#eff6ff', padding: '10px 14px', borderRadius: 10, margin: '8px 0', whiteSpace: 'pre-wrap' },
  msgAssistant: { background: '#f3f4f6', padding: '10px 14px', borderRadius: 10, margin: '8px 0', whiteSpace: 'pre-wrap' },
  tool: { background: '#fef9c3', padding: '6px 10px', borderRadius: 8, margin: '4px 0', fontSize: 13, fontFamily: 'monospace' },
  meta: { color: '#6b7280', fontSize: 13 },
  error: { color: '#b91c1c', marginTop: 8 },
  sessionItem: { padding: '10px 12px', cursor: 'pointer', borderBottom: '1px solid #eee' },
  sessionItemActive: { padding: '10px 12px', cursor: 'pointer', background: '#e0ecff', borderBottom: '1px solid #eee' },
};

function AuthPage({ onAuthed }) {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('alice@demo.local');
  const [password, setPassword] = useState('password');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const data = mode === 'login'
        ? await login(email, password)
        : await register(email, password, displayName);
      onAuthed(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ ...styles.card, maxWidth: 380, margin: '80px auto' }}>
      <h2 style={{ marginTop: 0 }}>{mode === 'login' ? 'Sign in' : 'Create account'}</h2>
      <form onSubmit={submit}>
        <input style={styles.input} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="email" />
        {mode === 'register' && (
          <input style={styles.input} value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="display name (optional)" />
        )}
        <input style={styles.input} type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
        <button style={styles.button} disabled={busy}>{busy ? 'Working…' : (mode === 'login' ? 'Sign in' : 'Register')}</button>
        {error && <div style={styles.error}>{error}</div>}
      </form>
      <div style={{ ...styles.meta, marginTop: 12 }}>
        {mode === 'login' ? 'No account? ' : 'Already registered? '}
        <a href="#" onClick={(e) => { e.preventDefault(); setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}>
          {mode === 'login' ? 'Register' : 'Sign in'}
        </a>
      </div>
      {mode === 'login' && <div style={styles.meta}>Demo: alice@demo.local / password</div>}
    </div>
  );
}

function Sidebar({ sessions, activeId, onSelect, onNew }) {
  return (
    <div style={styles.sidebar}>
      <div style={{ padding: 10 }}>
        <button style={{ ...styles.button, width: '100%' }} onClick={onNew}>+ New chat</button>
      </div>
      {sessions.length === 0 && <div style={{ ...styles.meta, padding: 12 }}>No conversations yet.</div>}
      {sessions.map((s) => (
        <div
          key={s.id}
          style={s.id === activeId ? styles.sessionItemActive : styles.sessionItem}
          onClick={() => onSelect(s)}
        >
          <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {s.title || 'New conversation'}
          </div>
          <div style={styles.meta}>{s.messageCount || 0} messages</div>
        </div>
      ))}
    </div>
  );
}

function ChatPanel({ token, activeSessionId, onSessionAdopted, onSessionChanged }) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  // Load history when the active session changes.
  useEffect(() => {
    if (!activeSessionId) { setMessages([]); return; }
    let cancelled = false;
    fetchMessages(token, activeSessionId)
      .then((msgs) => { if (!cancelled) setMessages(msgs.map((m) => ({ role: m.role, text: m.content, tools: [] }))); })
      .catch((err) => { if (!cancelled) setError(err.message); });
    return () => { cancelled = true; };
  }, [token, activeSessionId]);

  async function send(e) {
    e.preventDefault();
    if (!input.trim() || busy) return;
    const userText = input.trim();
    setInput('');
    setError(null);
    setMessages((m) => [...m, { role: 'user', text: userText, tools: [] }]);
    setBusy(true);

    let assistantIndex = -1;
    const textByMessageId = {};
    let adoptedSessionId = activeSessionId;

    try {
      for await (const event of runChat({
        token,
        agentId: 'default',
        sessionId: activeSessionId || undefined,
        message: userText,
      })) {
        // Adopt the backend-resolved session id from the run threadId so subsequent turns and
        // history replay target the persisted session.
        if (event.threadId && event.threadId !== adoptedSessionId) {
          adoptedSessionId = event.threadId;
          onSessionAdopted(event.threadId);
        }
        switch (event.type) {
          case 'TEXT_MESSAGE_START':
            setMessages((m) => {
              const next = [...m, { role: 'assistant', text: '', tools: [] }];
              assistantIndex = next.length - 1;
              return next;
            });
            textByMessageId[event.messageId] = '';
            break;
          case 'TEXT_MESSAGE_CONTENT': {
            textByMessageId[event.messageId] = (textByMessageId[event.messageId] || '') + (event.delta || '');
            const full = textByMessageId[event.messageId];
            setMessages((m) => {
              const next = [...m];
              if (assistantIndex >= 0 && next[assistantIndex]) next[assistantIndex] = { ...next[assistantIndex], text: full };
              return next;
            });
            break;
          }
          case 'TOOL_CALL_START':
            setMessages((m) => {
              const next = [...m];
              if (assistantIndex >= 0 && next[assistantIndex]) {
                const tools = [...(next[assistantIndex].tools || []), { id: event.toolCallId, name: event.toolCallName, result: null }];
                next[assistantIndex] = { ...next[assistantIndex], tools };
              }
              return next;
            });
            break;
          case 'TOOL_CALL_RESULT':
            setMessages((m) => {
              const next = [...m];
              if (assistantIndex >= 0 && next[assistantIndex]) {
                const tools = (next[assistantIndex].tools || []).map((t) => t.id === event.toolCallId ? { ...t, result: event.content } : t);
                next[assistantIndex] = { ...next[assistantIndex], tools };
              }
              return next;
            });
            break;
          case 'CUSTOM':
            if (event.name === 'error') setError((event.value && event.value.message) || 'stream error');
            break;
          default:
            break;
        }
      }
      onSessionChanged();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={styles.main}>
      <div style={{ flex: 1, overflowY: 'auto', padding: 16, maxWidth: 820, width: '100%', margin: '0 auto', boxSizing: 'border-box' }}>
        {messages.length === 0 && <div style={styles.meta}>Say hello to start a conversation.</div>}
        {messages.map((m, i) => (
          <div key={i}>
            <div style={m.role === 'user' ? styles.msgUser : styles.msgAssistant}>
              <strong>{m.role === 'user' ? 'You' : 'Assistant'}:</strong> {m.text}
            </div>
            {(m.tools || []).map((t) => (
              <div key={t.id} style={styles.tool}>🔧 {t.name}{t.result ? ` → ${t.result}` : ' …'}</div>
            ))}
          </div>
        ))}
      </div>
      <form style={{ padding: 16, borderTop: '1px solid #e2e2e2', maxWidth: 820, width: '100%', margin: '0 auto', boxSizing: 'border-box', display: 'flex', gap: 8 }} onSubmit={send}>
        <input style={{ ...styles.input, marginBottom: 0, flex: 1 }} value={input} onChange={(e) => setInput(e.target.value)} placeholder="Type a message…" disabled={busy} />
        <button style={{ ...styles.button, marginBottom: 0 }} disabled={busy}>{busy ? 'Streaming…' : 'Send'}</button>
      </form>
      {error && <div style={{ ...styles.error, padding: '0 16px 8px', maxWidth: 820, margin: '0 auto' }}>{error}</div>}
    </div>
  );
}

function App() {
  const [session, setSession] = useState(() => loadSession());
  const [sessions, setSessions] = useState([]);
  const [activeSessionId, setActiveSessionId] = useState(null);

  function refreshSessions() {
    if (!session) return;
    fetchSessions(session.token).then(setSessions).catch(() => {});
  }

  useEffect(() => {
    if (session) refreshSessions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  function handleAuthed(data) {
    const full = { ...data };
    saveSession(full);
    setSession(full);
  }

  function handleLogout() {
    clearSession();
    setSession(null);
    setSessions([]);
    setActiveSessionId(null);
  }

  function newChat() {
    setActiveSessionId(null);
  }

  if (!session) {
    return (
      <div style={styles.page}>
        <h1 style={{ textAlign: 'center', marginTop: 40 }}>AgentScope Enterprise Assistant</h1>
        <AuthPage onAuthed={handleAuthed} />
      </div>
    );
  }

  return (
    <div style={styles.page}>
      <div style={styles.topbar}>
        <strong>AgentScope Enterprise Assistant</strong>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={styles.meta}>{session.email} · {session.role} · {session.tier}</span>
          <button style={styles.ghostBtn} onClick={handleLogout}>Sign out</button>
        </div>
      </div>
      <div style={styles.body}>
        <Sidebar sessions={sessions} activeId={activeSessionId} onSelect={(s) => setActiveSessionId(s.id)} onNew={newChat} />
        <ChatPanel
          token={session.token}
          activeSessionId={activeSessionId}
          onSessionAdopted={(id) => setActiveSessionId(id)}
          onSessionChanged={refreshSessions}
        />
      </div>
    </div>
  );
}

createRoot(document.getElementById('root')).render(<App />);
