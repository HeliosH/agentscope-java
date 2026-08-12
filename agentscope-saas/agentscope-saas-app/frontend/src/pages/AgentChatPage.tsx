import React from 'react';
import { useOutletContext } from 'react-router-dom';
import ChatPanel from '../components/ChatPanel';
import type { AgentDefinition } from '../api/agents';

export default function AgentChatPage() {
  const { agentId, agent, openSidebar } = useOutletContext<{
    agentId: string;
    agent: AgentDefinition | null;
    openSidebar: () => void;
  }>();
  return <ChatPanel agentId={agentId} agentName={agent?.name} onOpenSidebar={openSidebar} />;
}
