import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createAgent, listAgents } from '../api/agents';

/**
 * Chat-first landing page. Post-login entry point: ensures a default assistant
 * agent exists (creating one if the org has none) and drops the user straight
 * into the AG-UI chat dialog. The agents hub remains reachable via the top-bar
 * logo and the agent rail.
 */
export default function ChatHomePage() {
  const navigate = useNavigate();
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await listAgents();
        const sorted = [...list].sort((a, b) => b.updatedAt - a.updatedAt);
        const agent =
          sorted[0] ??
          (await createAgent({ name: 'Assistant', description: 'Default assistant' }));
        if (!cancelled) {
          navigate(`/agents/${encodeURIComponent(agent.id)}/chat`, { replace: true });
        }
      } catch (e: unknown) {
        if (!cancelled) setErr(e instanceof Error ? e.message : String(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  return (
    <div style={{ padding: 40, color: '#64748b', fontSize: '0.95rem' }}>
      {err ? (
        <div style={{ color: '#dc2626' }}>Failed to start chat: {err}</div>
      ) : (
        'Starting chat…'
      )}
    </div>
  );
}
