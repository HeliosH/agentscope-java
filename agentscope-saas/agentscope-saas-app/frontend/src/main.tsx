import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import AgentsHubPage from './pages/AgentsHubPage';
import AgentCreatePage from './pages/AgentCreatePage';
import AgentChatPage from './pages/AgentChatPage';
import AgentWorkspacePage from './pages/AgentWorkspacePage';
import AgentSkillsPage from './pages/AgentSkillsPage';
import AgentSubagentsPage from './pages/AgentSubagentsPage';
import AgentToolsPage from './pages/AgentToolsPage';
import AgentSessionsPage from './pages/AgentSessionsPage';
import AgentSessionDetailPage from './pages/AgentSessionDetailPage';
import AgentSettingsPage from './pages/AgentSettingsPage';
import AppShell from './components/AppShell';
import AgentLayout from './components/AgentLayout';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import './api/http';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<AppShell />}>
            <Route path="/" element={<Navigate to="/agents" replace />} />
            <Route path="/agents" element={<AgentsHubPage />} />
            <Route path="/agents/new" element={<AgentCreatePage />} />

            <Route path="/agents/:id" element={<AgentLayout />}>
              <Route index element={<Navigate to="chat" replace />} />
              <Route path="chat" element={<AgentChatPage />} />
              <Route path="workspace" element={<AgentWorkspacePage />} />
              <Route path="skills" element={<AgentSkillsPage />} />
              <Route path="subagents" element={<AgentSubagentsPage />} />
              <Route path="tools" element={<AgentToolsPage />} />
              <Route path="sessions" element={<AgentSessionsPage />} />
              <Route path="sessions/:key" element={<AgentSessionDetailPage />} />
              <Route path="settings" element={<AgentSettingsPage />} />
            </Route>

            <Route path="*" element={<Navigate to="/agents" replace />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
