import { useEffect, useMemo, useState } from 'react';
import { Bot, ChevronRight, Plus, Search } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { AgentDefinition, listAgents } from '../api/agents';

type Filter = 'all' | 'builtin' | 'custom';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'builtin', label: 'Built-in' },
  { key: 'custom', label: 'Custom' },
];

function bucket(agent: AgentDefinition, key: Filter): boolean {
  if (key === 'all') return true;
  return key === 'builtin' ? agent.builtin : !agent.builtin;
}

export default function AgentsHubPage() {
  const navigate = useNavigate();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listAgents()
      .then(list => { if (!cancelled) setAgents(list); })
      .catch(cause => { if (!cancelled) setErr(cause instanceof Error ? cause.message : String(cause)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return agents.filter(agent => bucket(agent, filter) && (
      !needle || agent.name.toLowerCase().includes(needle) || agent.description?.toLowerCase().includes(needle)
    ));
  }, [agents, filter, query]);

  return (
    <div className="hub-page">
      <header className="hub-header">
        <div>
          <h1>Assistants</h1>
          <p>Select an assistant to start working.</p>
        </div>
        <button className="primary-button" type="button" onClick={() => navigate('/agents/new')}>
          <Plus size={15} />
          New assistant
        </button>
      </header>

      <div className="hub-toolbar">
        <div className="hub-filters" role="tablist" aria-label="Assistant type">
          {FILTERS.map(item => (
            <button
              key={item.key}
              className={filter === item.key ? 'is-active' : ''}
              type="button"
              role="tab"
              aria-selected={filter === item.key}
              onClick={() => setFilter(item.key)}
            >
              {item.label}
              <span>{agents.filter(agent => bucket(agent, item.key)).length}</span>
            </button>
          ))}
        </div>
        <label className="hub-search">
          <Search size={15} />
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search assistants" />
        </label>
      </div>

      {err && <div className="hub-error">{err}</div>}
      {loading && <div className="hub-empty">Loading assistants...</div>}

      {!loading && !err && (
        <div className="assistant-grid">
          {visible.map(agent => (
            <button
              className="assistant-card"
              key={agent.id}
              type="button"
              onClick={() => navigate(`/agents/${encodeURIComponent(agent.id)}/chat`)}
            >
              <span className="assistant-card__icon"><Bot size={18} /></span>
              <span className="assistant-card__body">
                <span className="assistant-card__top">
                  <strong>{agent.name}</strong>
                  <span className="assistant-card__badge">{agent.builtin ? 'Built-in' : 'Custom'}</span>
                </span>
                <span className="assistant-card__description">{agent.description || 'No description'}</span>
                <span className="assistant-card__id">{agent.id}</span>
              </span>
              <ChevronRight size={16} />
            </button>
          ))}
          {visible.length === 0 && <div className="hub-empty">No matching assistants.</div>}
        </div>
      )}
    </div>
  );
}
