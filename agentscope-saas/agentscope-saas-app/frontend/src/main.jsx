import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { runChat } from './aguiStream.js';
import {
  clearSession,
  deleteFile,
  fetchAgents,
  fetchFileTree,
  fetchMessages,
  fetchSessions,
  fetchSkills,
  loadSession,
  login,
  readFile,
  register,
  saveSession,
  upsertSkill,
  writeFile,
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
  tab: { padding: '4px 12px', borderRadius: 8, border: '1px solid #ccc', background: '#fff', cursor: 'pointer', fontSize: 13 },
  tabActive: { padding: '4px 12px', borderRadius: 8, border: '1px solid #2563eb', background: '#2563eb', color: '#fff', cursor: 'pointer', fontSize: 13 },
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
  const [pendingConfirm, setPendingConfirm] = useState(null);

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
            if (event.name === 'require_user_confirm' && event.value) {
              setPendingConfirm({ replyId: event.value.replyId, toolCalls: event.value.toolCalls || [] });
            }
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

  // Resume a paused HITL run: approve or deny each pending tool call by posting confirmResults
  // on the same sessionId (the agent's per-session permission engine remembers the pause).
  async function respondConfirm(toolCall, confirmed) {
    if (!pendingConfirm || busy) return;
    const confirmResults = [{ confirmed, toolCallId: toolCall.id, toolName: toolCall.name, input: toolCall.input }];
    setBusy(true);
    setError(null);
    setPendingConfirm(null);
    try {
      for await (const event of runChat({
        token,
        agentId: 'default',
        sessionId: activeSessionId || undefined,
        confirmResults,
      })) {
        switch (event.type) {
          case 'CUSTOM':
            if (event.name === 'error') setError((event.value && event.value.message) || 'stream error');
            if (event.name === 'require_user_confirm' && event.value) {
              setPendingConfirm({ replyId: event.value.replyId, toolCalls: event.value.toolCalls || [] });
            }
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
        {pendingConfirm && (
          <div style={{ ...styles.card, margin: '12px 0', borderColor: '#f59e0b' }}>
            <div style={{ fontWeight: 600, marginBottom: 6 }}>⏸ Approval required</div>
            {(pendingConfirm.toolCalls || []).map((tc) => (
              <div key={tc.id} style={{ borderTop: '1px solid #eee', paddingTop: 8, marginTop: 8 }}>
                <div style={{ fontFamily: 'monospace', fontSize: 13 }}>{tc.name}</div>
                {tc.input && <pre style={{ margin: '4px 0', fontSize: 12, maxHeight: 120, overflow: 'auto' }}>{JSON.stringify(tc.input, null, 2)}</pre>}
                <span style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                  <button style={styles.button} onClick={() => respondConfirm(tc, true)} disabled={busy}>Approve</button>
                  <button style={{ ...styles.ghostBtn, color: '#b91c1c' }} onClick={() => respondConfirm(tc, false)} disabled={busy}>Deny</button>
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
      <form style={{ padding: 16, borderTop: '1px solid #e2e2e2', maxWidth: 820, width: '100%', margin: '0 auto', boxSizing: 'border-box', display: 'flex', gap: 8 }} onSubmit={send}>
        <input style={{ ...styles.input, marginBottom: 0, flex: 1 }} value={input} onChange={(e) => setInput(e.target.value)} placeholder="Type a message…" disabled={busy} />
        <button style={{ ...styles.button, marginBottom: 0 }} disabled={busy}>{busy ? 'Streaming…' : 'Send'}</button>
      </form>
      {error && <div style={{ ...styles.error, padding: '0 16px 8px', maxWidth: 820, margin: '0 auto' }}>{error}</div>}
    </div>
  );
}

function FileTreeItem({ node, depth, selectedPath, onSelect }) {
  const pad = { padding: '4px 10px', paddingLeft: 10 + depth * 14, cursor: 'pointer', whiteSpace: 'nowrap' };
  const isSel = node.path === selectedPath;
  const icon = node.type === 'dir' ? '📁' : '📄';
  return (
    <>
      <div
        style={{ ...pad, background: isSel ? '#e0ecff' : 'transparent' }}
        onClick={() => node.type === 'file' && onSelect(node.path)}
      >
        <span style={{ marginRight: 6 }}>{icon}</span>
        <span style={{ fontWeight: node.type === 'dir' ? 600 : 400 }}>{node.name}</span>
      </div>
      {node.type === 'dir' && (node.children || []).map((c) => (
        <FileTreeItem key={c.path} node={c} depth={depth + 1} selectedPath={selectedPath} onSelect={onSelect} />
      ))}
    </>
  );
}

function WorkspacePanel({ token, agentId }) {
  const [tree, setTree] = useState([]);
  const [skills, setSkills] = useState([]);
  const [selected, setSelected] = useState(null);
  const [draft, setDraft] = useState('');
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  function refresh() {
    if (!agentId) return;
    Promise.all([fetchFileTree(token, agentId), fetchSkills(token, agentId)])
      .then(([files, sks]) => { setTree(files || []); setSkills(sks || []); })
      .catch((err) => setError(err.message));
  }

  useEffect(() => { refresh(); setSelected(null); setDraft(''); setDirty(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, agentId]);

  function openFile(path) {
    setBusy(true); setError(null);
    readFile(token, agentId, path)
      .then((text) => { setSelected(path); setDraft(text); setDirty(false); })
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false));
  }

  function save() {
    if (!selected) return;
    setBusy(true); setError(null);
    writeFile(token, agentId, selected, draft)
      .then(() => { setDirty(false); refresh(); })
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false));
  }

  function newFile() {
    const path = window.prompt('New file path (e.g. notes.md):');
    if (!path) return;
    setBusy(true); setError(null);
    writeFile(token, agentId, path, '')
      .then(() => { refresh(); openFile(path); })
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false));
  }

  function remove() {
    if (!selected || !window.confirm(`Delete ${selected}?`)) return;
    setBusy(true); setError(null);
    deleteFile(token, agentId, selected)
      .then(() => { setSelected(null); setDraft(''); setDirty(false); refresh(); })
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false));
  }

  function newSkill() {
    const name = window.prompt('New skill name (e.g. greet):');
    if (!name) return;
    const markdown = `---\nname: ${name}\ndescription: \n---\n# ${name}\n\nDescribe what this skill does.\n`;
    setBusy(true); setError(null);
    upsertSkill(token, agentId, name, markdown)
      .then(() => { refresh(); openFile(`skills/${name}/SKILL.md`); })
      .catch((err) => setError(err.message))
      .finally(() => setBusy(false));
  }

  if (!agentId) {
    return (
      <div style={styles.main}>
        <div style={{ padding: 24, ...styles.meta }}>
          No agent yet. Start a chat to initialize your workspace, then switch back here.
        </div>
      </div>
    );
  }

  return (
    <div style={styles.main}>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <div style={{ width: 280, borderRight: '1px solid #e2e2e2', overflowY: 'auto', background: '#fafafa' }}>
          <div style={{ padding: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong style={{ fontSize: 13 }}>Files</strong>
            <button style={styles.ghostBtn} onClick={newFile} disabled={busy}>+ New</button>
          </div>
          {(tree || []).map((n) => (
            <FileTreeItem key={n.path} node={n} depth={0} selectedPath={selected} onSelect={openFile} />
          ))}
          <div style={{ padding: '12px 10px 6px', borderTop: '1px solid #eee', marginTop: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <strong style={{ fontSize: 13 }}>Skills</strong>
            <button style={styles.ghostBtn} onClick={newSkill} disabled={busy}>+ New</button>
          </div>
          {skills.length === 0 && <div style={{ ...styles.meta, padding: '0 10px' }}>No skills yet.</div>}
          {skills.map((s) => (
            <div
              key={s.dirName}
              style={{ padding: '4px 10px', fontSize: 13, cursor: 'pointer', background: selected === `skills/${s.dirName}/SKILL.md` ? '#e0ecff' : 'transparent' }}
              onClick={() => openFile(`skills/${s.dirName}/SKILL.md`)}
            >
              <strong>{s.name}</strong>
              {s.description && <div style={styles.meta}>{s.description}</div>}
            </div>
          ))}
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          {selected ? (
            <>
              <div style={{ padding: '8px 12px', borderBottom: '1px solid #eee', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontFamily: 'monospace', fontSize: 13 }}>{selected}{dirty ? ' •' : ''}</span>
                <span style={{ display: 'flex', gap: 8 }}>
                  <button style={styles.ghostBtn} onClick={save} disabled={busy || !dirty}>{busy ? 'Saving…' : 'Save'}</button>
                  <button style={{ ...styles.ghostBtn, color: '#b91c1c' }} onClick={remove} disabled={busy}>Delete</button>
                </span>
              </div>
              <textarea
                style={{ flex: 1, width: '100%', border: 'none', outline: 'none', padding: 12, fontFamily: 'monospace', fontSize: 13, resize: 'none', boxSizing: 'border-box' }}
                value={draft}
                onChange={(e) => { setDraft(e.target.value); setDirty(true); }}
                disabled={busy}
              />
            </>
          ) : (
            <div style={{ padding: 24, ...styles.meta }}>Select a file to view or edit it.</div>
          )}
        </div>
      </div>
      {error && <div style={{ ...styles.error, padding: '0 16px 8px' }}>{error}</div>}
    </div>
  );
}

function App() {
  const [session, setSession] = useState(() => loadSession());
  const [sessions, setSessions] = useState([]);
  const [activeSessionId, setActiveSessionId] = useState(null);
  const [view, setView] = useState('chat');
  const [agentId, setAgentId] = useState(null);

  function refreshSessions() {
    if (!session) return;
    fetchSessions(session.token).then(setSessions).catch(() => {});
  }

  function refreshAgent() {
    if (!session) return;
    fetchAgents(session.token)
      .then((agents) => { setAgentId(agents && agents.length ? agents[0].id : null); })
      .catch(() => setAgentId(null));
  }

  useEffect(() => {
    if (session) {
      refreshSessions();
      refreshAgent();
    }
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
    setView('chat');
    setAgentId(null);
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
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <strong>AgentScope Enterprise Assistant</strong>
          <span style={{ display: 'inline-flex', gap: 4, marginLeft: 8 }}>
            <button
              style={view === 'chat' ? styles.tabActive : styles.tab}
              onClick={() => setView('chat')}
            >Chat</button>
            <button
              style={view === 'workspace' ? styles.tabActive : styles.tab}
              onClick={() => setView('workspace')}
            >Workspace</button>
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={styles.meta}>{session.email} · {session.role} · {session.tier}</span>
          <button style={styles.ghostBtn} onClick={handleLogout}>Sign out</button>
        </div>
      </div>
      <div style={styles.body}>
        {view === 'chat' ? (
          <>
            <Sidebar sessions={sessions} activeId={activeSessionId} onSelect={(s) => setActiveSessionId(s.id)} onNew={newChat} />
            <ChatPanel
              token={session.token}
              activeSessionId={activeSessionId}
              onSessionAdopted={(id) => setActiveSessionId(id)}
              onSessionChanged={refreshSessions}
            />
          </>
        ) : (
          <WorkspacePanel token={session.token} agentId={agentId} />
        )}
      </div>
    </div>
  );
}

createRoot(document.getElementById('root')).render(<App />);
