import React, { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Ban, Plus, RefreshCw } from 'lucide-react';
import {
  ActiveTool,
  ActiveToolsResponse,
  ToolsConfig,
  fetchActive,
  fetchConfig,
  saveConfig,
} from '../api/tools';

interface Props {
  agentId: string;
  refreshKey: number;
  onChange: () => void;
  onRequestBrowse: () => void;
}

const S: Record<string, React.CSSProperties> = {
  root: { padding: '20px 24px', display: 'flex', flexDirection: 'column', gap: 16, height: '100%', minHeight: 0 },
  headerRow: { display: 'flex', alignItems: 'center', gap: 12 },
  title: { fontSize: '1.05rem', fontWeight: 600, color: '#0f172a' },
  sub: { fontSize: '0.82rem', color: '#64748b' },
  primaryBtn: {
    padding: '8px 16px',
    background: '#176b49',
    color: '#ffffff', border: 'none', borderRadius: 6, cursor: 'pointer',
    fontSize: '0.86rem', fontWeight: 600,
    display: 'inline-flex', alignItems: 'center', gap: 6,
  },
  refreshBtn: {
    background: '#f8fafc', border: '1px solid #e2e8f0', color: '#475569',
    borderRadius: 7, padding: '6px 12px', cursor: 'pointer',
    fontSize: '0.78rem', fontWeight: 500, display: 'inline-flex', alignItems: 'center', gap: 5,
  },
  warnings: {
    background: '#fffbeb', border: '1px solid #fde68a', color: '#92400e',
    borderRadius: 6, padding: '10px 14px', fontSize: '0.82rem', lineHeight: 1.5,
  },
  groupHeader: {
    fontSize: '0.74rem', fontWeight: 700, color: '#94a3b8',
    textTransform: 'uppercase', letterSpacing: '0.1em',
    marginTop: 12, marginBottom: 6,
  },
  list: {
    display: 'flex', flexDirection: 'column', gap: 8,
    overflow: 'auto', flex: 1, minHeight: 0,
  },
  card: {
    border: '1px solid #e2e8f0', borderRadius: 6, padding: '12px 14px',
    background: '#ffffff', display: 'flex', alignItems: 'flex-start', gap: 12,
  },
  cardName: { fontWeight: 600, color: '#0f172a', fontSize: '0.92rem' },
  cardDesc: { color: '#64748b', fontSize: '0.82rem', marginTop: 3, lineHeight: 1.45 },
  badge: {
    fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 5,
    background: '#e6f1eb', color: '#176b49', border: '1px solid #b9d2c5',
    textTransform: 'uppercase', letterSpacing: '0.04em', flexShrink: 0,
  },
  mcpBadge: {
    fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 5,
    background: '#ecfeff', color: '#0e7490', border: '1px solid #a5f3fc',
    textTransform: 'uppercase', letterSpacing: '0.04em', flexShrink: 0,
  },
  disableBtn: {
    background: '#fef2f2', border: '1px solid #fecaca', color: '#b91c1c',
    borderRadius: 6, padding: '4px 10px', cursor: 'pointer',
    fontSize: '0.74rem', fontWeight: 500, marginLeft: 'auto', flexShrink: 0,
    display: 'inline-flex', alignItems: 'center', gap: 4,
  },
  empty: { padding: 32, textAlign: 'center', color: '#94a3b8', fontSize: '0.88rem' },
  err: { color: '#dc2626', fontSize: '0.85rem' },
};

export default function ToolsActivePanel({ agentId, refreshKey, onChange, onRequestBrowse }: Props) {
  const [data, setData] = useState<ActiveToolsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setErr(null);
    fetchActive(agentId)
      .then(d => { if (!cancelled) setData(d); })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId, refreshKey]);

  const grouped = useMemo(() => {
    const out = new Map<string, ActiveTool[]>();
    for (const t of data?.tools ?? []) {
      const key = t.source || 'unknown';
      if (!out.has(key)) out.set(key, []);
      out.get(key)!.push(t);
    }
    return out;
  }, [data]);

  async function disableTool(t: ActiveTool) {
    setActionErr(null);
    try {
      const cfg: ToolsConfig = await fetchConfig(agentId);
      if (t.source === 'built-in') {
        const deny = new Set(cfg.deny ?? []);
        deny.add(t.name);
        cfg.deny = Array.from(deny);
      } else {
        const map = cfg.mcpServers ?? {};
        delete map[t.name];
        cfg.mcpServers = map;
      }
      await saveConfig(agentId, cfg);
      onChange();
    } catch (e: unknown) {
      setActionErr(e instanceof Error ? e.message : 'Failed to update config');
    }
  }

  return (
    <div style={S.root}>
      <div style={S.headerRow}>
        <div style={{ flex: 1 }}>
          <div style={S.title}>Active tools</div>
          <div style={S.sub}>
            Live view, resolved by introspecting a transient agent against this workspace.
          </div>
        </div>
        <button style={S.refreshBtn} onClick={() => onChange()} disabled={loading}>
          <RefreshCw size={13} className={loading ? 'is-spinning' : ''} /> Refresh
        </button>
        <button style={S.primaryBtn} onClick={onRequestBrowse}>
          <Plus size={14} /> Add / configure
        </button>
      </div>

      {data?.warnings && data.warnings.length > 0 && (
        <div style={S.warnings}>
          {data.warnings.map((warning, index) => <div key={index} style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}><AlertTriangle size={14} />{warning}</div>)}
        </div>
      )}
      {actionErr && <div style={S.err}>{actionErr}</div>}
      {err && <div style={S.err}>{err}</div>}

      <div style={S.list}>
        {!err && !loading && (data?.tools ?? []).length === 0 && (
          <div style={S.empty}>No tools available. Click <b>Add / configure</b> to enable some.</div>
        )}
        {Array.from(grouped.entries()).map(([source, tools]) => (
          <div key={source}>
            <div style={S.groupHeader}>{source === 'built-in' ? 'Built-in' : `MCP — ${source}`}</div>
            {tools.map(t => (
              <div key={`${source}:${t.name}`} style={S.card}>
                <span style={t.source === 'built-in' ? S.badge : S.mcpBadge}>{t.source}</span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={S.cardName}>{t.name}</div>
                  {t.description && <div style={S.cardDesc}>{t.description}</div>}
                </div>
                <button
                  style={S.disableBtn}
                  onClick={() => disableTool(t)}
                  title={t.source === 'built-in' ? 'Add to deny list' : 'Remove this MCP server'}
                >
                  <Ban size={12} /> Disable
                </button>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
