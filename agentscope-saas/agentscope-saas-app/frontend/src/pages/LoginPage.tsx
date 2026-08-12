import { useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { login, register } from '../auth';
import BrandLogo from '../components/BrandLogo';

type Mode = 'login' | 'register';

export default function LoginPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      if (mode === 'login') {
        await login(email.trim(), password);
      } else {
        await register(email.trim(), password, displayName.trim());
      }
      navigate('/', { replace: true });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Authentication failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-brand">
          <BrandLogo />
          <span>刍狗</span>
        </div>
        <div className="login-heading">
          <h1 id="login-title">{mode === 'login' ? 'Sign in to your workspace' : 'Create your workspace account'}</h1>
          <p>Enterprise assistant for planning, tools, and secure task execution.</p>
        </div>

        <div className="login-tabs" role="tablist" aria-label="Authentication mode">
          {(['login', 'register'] as Mode[]).map(value => (
            <button
              key={value}
              className={mode === value ? 'is-active' : ''}
              type="button"
              role="tab"
              aria-selected={mode === value}
              onClick={() => { setMode(value); setError(null); }}
            >
              {value === 'login' ? 'Sign in' : 'Create account'}
            </button>
          ))}
        </div>

        <form className="login-form" onSubmit={handleSubmit}>
          {mode === 'register' && (
            <label>
              Display name
              <input
                type="text"
                value={displayName}
                onChange={event => setDisplayName(event.target.value)}
                autoComplete="name"
              />
            </label>
          )}
          <label>
            Email
            <input
              type="email"
              value={email}
              onChange={event => setEmail(event.target.value)}
              autoComplete="email"
              required
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
            />
          </label>
          {error && <div className="login-error" role="alert">{error}</div>}
          <button className="login-submit" type="submit" disabled={busy}>
            {busy ? 'Working...' : mode === 'login' ? 'Continue' : 'Create account'}
            {!busy && <ArrowRight size={16} />}
          </button>
        </form>
      </section>
    </main>
  );
}
