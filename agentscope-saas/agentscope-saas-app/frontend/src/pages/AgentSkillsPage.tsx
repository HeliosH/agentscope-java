import { useState } from 'react';
import { Info, Store, X } from 'lucide-react';
import { useOutletContext } from 'react-router-dom';
import SkillsMarketplacesPanel from '../components/SkillsMarketplacesPanel';
import SkillsWorkspacePanel from '../components/SkillsWorkspacePanel';
import type { MeResponse } from '../auth';

export default function AgentSkillsPage() {
  const { agentId, me } = useOutletContext<{ agentId: string; me: MeResponse | null }>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [browseOpen, setBrowseOpen] = useState(false);
  const bumpRefresh = () => setRefreshKey(key => key + 1);

  return (
    <div className="config-tool-page">
      <div className="config-context-bar"><Info size={15} /><span>Installed skills are scoped to this agent workspace and take effect on the next task.</span></div>
      <div className="config-tool-content">
        <SkillsWorkspacePanel agentId={agentId} refreshKey={refreshKey} onChange={bumpRefresh} onRequestBrowse={() => setBrowseOpen(true)} />
      </div>
      {browseOpen && (
        <div className="management-modal-overlay" onClick={() => setBrowseOpen(false)}>
          <section className="management-modal management-modal-wide" onClick={event => event.stopPropagation()} aria-modal="true" role="dialog" aria-labelledby="skill-market-title">
            <header className="management-modal-header">
              <Store size={18} />
              <div><h2 id="skill-market-title">Skill marketplace</h2><p>Install enterprise-approved capabilities into this agent.</p></div>
              <button className="icon-button" onClick={() => setBrowseOpen(false)} title="Close"><X size={18} /></button>
            </header>
            <div className="management-modal-body"><SkillsMarketplacesPanel agentId={agentId} role={me?.role ?? 'member'} onInstalled={bumpRefresh} /></div>
          </section>
        </div>
      )}
    </div>
  );
}
