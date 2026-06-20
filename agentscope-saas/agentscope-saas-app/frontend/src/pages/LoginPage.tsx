import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, register } from '../auth';

type Mode = 'login' | 'register';

export default function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (mode === 'login') {
        await login(email.trim(), password);
      } else {
        await register(email.trim(), password, displayName.trim());
      }
      navigate('/agents', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Authentication failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'linear-gradient(135deg,#eef2ff 0%,#f8fafc 100%)',
    }}>
      <div style={{
        width: 380, background: '#ffffff', borderRadius: 16,
        border: '1px solid #e2e8f0', boxShadow: '0 10px 30px rgba(15,23,42,0.08)',
        padding: '34px 32px',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 22 }}>
          <span style={{
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            width: 36, height: 36, borderRadius: 10,
            background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
            color: '#ffffff', fontSize: '1.2rem', boxShadow: '0 2px 6px rgba(99,102,241,0.35)',
          }}>⚙</span>
          <span style={{ fontWeight: 700, fontSize: '1.15rem', color: '#0f172a' }}>AgentScope</span>
        </div>

        <div style={{ display: 'flex', gap: 4, marginBottom: 22, borderBottom: '1px solid #e2e8f0' }}>
          {(['login', 'register'] as Mode[]).map(m => (
            <button
              key={m}
              type="button"
              onClick={() => { setMode(m); setError(null); }}
              style={{
                flex: 1, padding: '10px 0', cursor: 'pointer',
                background: 'transparent', border: 'none',
                borderBottom: `2px solid ${mode === m ? '#6366f1' : 'transparent'}`,
                color: mode === m ? '#0f172a' : '#64748b',
                fontWeight: mode === m ? 600 : 500, fontSize: '0.92rem',
              }}
            >
              {m === 'login' ? 'Sign in' : 'Create account'}
            </button>
          ))}
        </div>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {mode === 'register' && (
            <input
              type="text"
              placeholder="Display name"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              style={inputStyle}
            />
          )}
          <input
            type="email"
            placeholder="Email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            required
            style={inputStyle}
          />
          <input
            type="password"
            placeholder="Password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            required
            style={inputStyle}
          />
          {error && (
            <div style={{ color: '#dc2626', fontSize: '0.85rem' }}>{error}</div>
          )}
          <button
            type="submit"
            disabled={busy}
            style={{
              padding: '11px 0', borderRadius: 10, border: 'none', cursor: busy ? 'not-allowed' : 'pointer',
              background: busy ? '#c7d2fe' : 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
              color: '#ffffff', fontWeight: 600, fontSize: '0.95rem',
              boxShadow: '0 2px 6px rgba(99,102,241,0.35)',
            }}
          >
            {busy ? '…' : mode === 'login' ? 'Sign in' : 'Create account'}
          </button>
        </form>
      </div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  padding: '11px 14px', background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 10,
  color: '#0f172a', fontSize: '0.92rem',
};
