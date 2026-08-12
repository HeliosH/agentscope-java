import { Info } from 'lucide-react';
import { useOutletContext } from 'react-router-dom';
import SubagentPanel from '../components/SubagentPanel';

export default function AgentSubagentsPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  return (
    <div className="config-tool-page">
      <div className="config-context-bar"><Info size={15} /><span>Delegate specialized work to managed subagents stored in this agent workspace.</span></div>
      <div className="config-tool-content"><SubagentPanel agentId={agentId} /></div>
    </div>
  );
}
