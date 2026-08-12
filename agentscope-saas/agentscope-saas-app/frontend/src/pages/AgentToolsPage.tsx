import { useState } from 'react';
import { Info, PlugZap, X } from 'lucide-react';
import { useOutletContext } from 'react-router-dom';
import ToolsActivePanel from '../components/ToolsActivePanel';
import ToolsCatalogPanel from '../components/ToolsCatalogPanel';

export default function AgentToolsPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [browseOpen, setBrowseOpen] = useState(false);
  const bumpRefresh = () => setRefreshKey(key => key + 1);

  return (
    <div className="config-tool-page">
      <div className="config-context-bar"><Info size={15} /><span>Built-in and MCP tools are configured per agent and apply on the next session boot.</span></div>
      <div className="config-tool-content">
        <ToolsActivePanel agentId={agentId} refreshKey={refreshKey} onChange={bumpRefresh} onRequestBrowse={() => setBrowseOpen(true)} />
      </div>
      {browseOpen && (
        <div className="management-modal-overlay" onClick={() => setBrowseOpen(false)}>
          <section className="management-modal" onClick={event => event.stopPropagation()} aria-modal="true" role="dialog" aria-labelledby="tool-catalog-title">
            <header className="management-modal-header">
              <PlugZap size={18} />
              <div><h2 id="tool-catalog-title">Configure tools</h2><p>Enable built-ins or connect an internal MCP server.</p></div>
              <button className="icon-button" onClick={() => setBrowseOpen(false)} title="Close"><X size={18} /></button>
            </header>
            <div className="management-modal-body"><ToolsCatalogPanel agentId={agentId} onSaved={bumpRefresh} /></div>
          </section>
        </div>
      )}
    </div>
  );
}
