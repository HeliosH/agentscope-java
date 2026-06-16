import React, { useState, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { runChat } from './aguiStream.js';

const styles = {
  page: { fontFamily: 'system-ui, sans-serif', maxWidth: 760, margin: '0 auto', padding: 24 },
  card: { border: '1px solid #e2e2e2', borderRadius: 12, padding: 20, marginBottom: 16 },
  input: { width: '100%', padding: 10, borderRadius: 8, border: '1px solid #ccc', boxSizing: 'border-box', marginBottom: 8 },
  button: { padding: '10px 16px', borderRadius: 8, border: 'none', background: '#2563eb', color: '#fff', cursor: 'pointer' },
  msgUser: { background: '#eff6ff', padding: '10px 14px', borderRadius: 10, margin: '8px 0', whiteSpace: 'pre-wrap' },
  msgAssistant: { background: '#f3f4f6', padding: '10px 14px', borderRadius: 10, margin: '8px 0', whiteSpace: 'pre-wrap' },
  tool: { background: '#fef9c3', padding: '6px 10px', borderRadius: 8, margin: '4px 0', fontSize: 13, fontFamily: 'monospace' },
  meta: { color: '#6b7280', fontSize: 13 },
  error: { color: '#b91c1c', marginTop: 8 },
};

function Login({ onLogin }) {
  const [email, setEmail] = useState('alice@demo.local');
  const [password, setPassword] = useState('password');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `login failed (${res.status})`);
      }
      const data = await res.json();
      onLogin(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={styles.page}>
      <h1>AgentScope Enterprise Assistant</h1>
      <form style={styles.card} onSubmit={submit}>
        <h2>Sign in</h2>
        <input style={styles.input} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="email" />
        <input style={styles.input} type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="password" />
        <button style={styles.button} disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        {error && <div style={styles.error}>{error}</div>}
        <div style={styles.meta}>Demo: alice@demo.local / password</div>
      </form>
    </div>
  );
}

function Chat({ session, onLogout }) {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const sessionIdRef = useRef(crypto.randomUUID());

  async function send(e) {
    e.preventDefault();
    if (!input.trim() || busy) return;
    const userText = input.trim();
    setInput('');
    setError(null);
    setMessages((m) => [...m, { role: 'user', text: userText }]);
    setBusy(true);

    // Track the assistant message + active tool calls by AG-UI ids.
    let assistantIndex = -1;
    const textByMessageId = {};

    try {
      for await (const event of runChat({
        token: session.token,
        agentId: 'default',
        sessionId: sessionIdRef.current,
        message: userText,
      })) {
        switch (event.type) {
          case 'TEXT_MESSAGE_START': {
            setMessages((m) => {
              const next = [...m, { role: 'assistant', text: '', tools: [] }];
              assistantIndex = next.length - 1;
              return next;
            });
            textByMessageId[event.messageId] = '';
            break;
          }
          case 'TEXT_MESSAGE_CONTENT': {
            textByMessageId[event.messageId] = (textByMessageId[event.messageId] || '') + (event.delta || '');
            const full = textByMessageId[event.messageId];
            setMessages((m) => {
              const next = [...m];
              if (assistantIndex >= 0 && next[assistantIndex]) {
                next[assistantIndex] = { ...next[assistantIndex], text: full };
              }
              return next;
            });
            break;
          }
          case 'TOOL_CALL_START': {
            setMessages((m) => {
              const next = [...m];
              if (assistantIndex >= 0 && next[assistantIndex]) {
                const tools = [...(next[assistantIndex].tools || []), { id: event.toolCallId, name: event.toolCallName, result: null }];
                next[assistantIndex] = { ...next[assistantIndex], tools };
              }
              return next;
            });
            break;
          }
          case 'TOOL_CALL_RESULT': {
            setMessages((m) => {
              const next = [...m];
              if (assistantIndex >= 0 && next[assistantIndex]) {
                const tools = (next[assistantIndex].tools || []).map((t) =>
                  t.id === event.toolCallId ? { ...t, result: event.content } : t
                );
                next[assistantIndex] = { ...next[assistantIndex], tools };
              }
              return next;
            });
            break;
          }
          case 'CUSTOM': {
            if (event.name === 'error') {
              setError((event.value && event.value.message) || 'stream error');
            }
            break;
          }
          default:
            break;
        }
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={styles.page}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>AgentScope Enterprise Assistant</h1>
        <button style={{ ...styles.button, background: '#6b7280' }} onClick={onLogout}>Sign out</button>
      </div>
      <div style={styles.meta}>
        org {session.orgId} · user {session.email} · role {session.role} · tier {session.tier}
      </div>

      <div style={styles.card}>
        {messages.length === 0 && <div style={styles.meta}>Say hello to start a conversation.</div>}
        {messages.map((m, i) => (
          <div key={i}>
            <div style={m.role === 'user' ? styles.msgUser : styles.msgAssistant}>
              <strong>{m.role === 'user' ? 'You' : 'Assistant'}:</strong> {m.text}
            </div>
            {(m.tools || []).map((t) => (
              <div key={t.id} style={styles.tool}>
                🔧 {t.name}{t.result ? ` → ${t.result}` : ' …'}
              </div>
            ))}
          </div>
        ))}
      </div>

      <form style={styles.card} onSubmit={send}>
        <input
          style={styles.input}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Type a message…"
          disabled={busy}
        />
        <button style={styles.button} disabled={busy}>{busy ? 'Streaming…' : 'Send'}</button>
        {error && <div style={styles.error}>{error}</div>}
      </form>
    </div>
  );
}

function App() {
  const [session, setSession] = useState(null);
  if (!session) return <Login onLogin={setSession} />;
  return <Chat session={session} onLogout={() => setSession(null)} />;
}

createRoot(document.getElementById('root')).render(<App />);
