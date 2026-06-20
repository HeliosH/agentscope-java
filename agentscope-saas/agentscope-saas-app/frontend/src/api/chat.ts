export interface ConfirmResultInput {
  confirmed: boolean;
  toolCallId: string;
  toolName: string;
  input?: Record<string, unknown>;
}

export interface ChatRequest {
  message: string;
  sessionId?: string;
  confirmResults?: ConfirmResultInput[];
}

/**
 * Frontend-facing chat event, surfaced to the UI from the AG-UI wire stream. The backend
 * (`SaasChatController`) emits AG-UI protocol events (RUN_STARTED, TEXT_MESSAGE_CONTENT,
 * TOOL_CALL_*, RUN_FINISHED, CUSTOM error/confirm); `stream()` translates those into the
 * simpler {token, tool_call, tool_result, done, error} shape that ChatPanel renders.
 */
export interface ChatEvent {
  type: 'token' | 'tool_call' | 'tool_result' | 'done' | 'error' | string;
  data?: string;
  toolName?: string;
  toolInput?: string;
  toolResult?: string;
  error?: string;
  sessionKey?: string;
}

export interface CurrentSession {
  sessionKey: string | null;
  exists: boolean;
}

export async function currentSession(agentId: string): Promise<CurrentSession> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/session`);
  if (!res.ok) throw new Error(`Failed to resolve current session: ${res.status}`);
  return res.json();
}

/**
 * Streams an agent run as paw-shaped ChatEvents. The backend returns AG-UI Server-Sent Events;
 * here we parse each `data:` frame as JSON and translate the AG-UI event type into the event
 * shape ChatPanel expects. The Response is consumed as a stream (not buffered) so tokens render
 * incrementally. `sessionId` is the session UUID string (paw's `sessionKey`).
 */
export async function* stream(agentId: string, req: ChatRequest): AsyncGenerator<ChatEvent> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      message: req.message,
      sessionId: req.sessionId,
      confirmResults: req.confirmResults,
    }),
  });
  if (!res.ok || !res.body) throw new Error(`Chat stream failed: ${res.status}`);

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  // toolCallId -> { name, args } tracked across TOOL_CALL_START/ARGS so TOOL_CALL_RESULT can
  // resolve the tool name (AG-UI result events carry only the call id, not the name).
  const tools = new Map<string, { name: string; args: string }>();

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const lines = frame.split('\n');
      let data = '';
      for (const ln of lines) if (ln.startsWith('data:')) data += ln.slice(5).trim();
      if (!data) continue;

      let payload: Record<string, unknown>;
      try {
        payload = JSON.parse(data);
      } catch {
        yield { type: 'token', data };
        continue;
      }

      const type = payload.type as string;
      if (type === 'TEXT_MESSAGE_CONTENT') {
        const delta = (payload.delta as string) ?? '';
        if (delta) yield { type: 'token', data: delta };
      } else if (type === 'TOOL_CALL_START') {
        const id = payload.toolCallId as string;
        const name = (payload.toolCallName as string) ?? 'tool';
        if (id) tools.set(id, { name, args: '' });
        yield { type: 'tool_call', toolName: name };
      } else if (type === 'TOOL_CALL_ARGS') {
        const id = payload.toolCallId as string;
        const entry = id ? tools.get(id) : undefined;
        if (entry) entry.args += (payload.delta as string) ?? '';
      } else if (type === 'TOOL_CALL_END') {
        const id = payload.toolCallId as string;
        const entry = id ? tools.get(id) : undefined;
        if (entry && entry.args) {
          yield { type: 'tool_call', toolName: entry.name, toolInput: entry.args };
        }
      } else if (type === 'TOOL_CALL_RESULT') {
        const id = payload.toolCallId as string;
        const entry = id ? tools.get(id) : undefined;
        yield {
          type: 'tool_result',
          toolName: entry?.name ?? id ?? 'tool',
          toolResult: (payload.content as string) ?? '',
        };
      } else if (type === 'RUN_FINISHED') {
        yield { type: 'done', sessionKey: (payload.threadId as string) ?? req.sessionId };
      } else if (type === 'CUSTOM' && payload.name === 'error') {
        const value = payload.value as { message?: string } | undefined;
        yield { type: 'error', error: value?.message ?? 'unknown error' };
      }
      // RUN_STARTED, TEXT_MESSAGE_START/END, STATE_*, RAW, REASONING_*, and the
      // require_user_confirm Custom event are not rendered by paw's ChatPanel.
    }
  }
}
