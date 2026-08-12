import { useState } from 'react';
import { CheckCircle2, ChevronDown, ChevronRight, LoaderCircle, TerminalSquare } from 'lucide-react';

interface Props {
  toolName: string;
  toolCallId: string;
  result?: string;
}

export default function ToolCallBlock({ toolName, toolCallId, result }: Props) {
  const [open, setOpen] = useState(false);
  const finished = result !== undefined;

  return (
    <div className="tool-call">
      <button
        className="tool-call__header"
        type="button"
        aria-expanded={open}
        aria-controls={`tool-${toolCallId}`}
        onClick={() => setOpen(value => !value)}
      >
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <TerminalSquare size={14} />
        <span className="tool-call__name">{toolName}</span>
        <span className="tool-call__status">
          {finished ? (
            <><CheckCircle2 size={12} /> Completed</>
          ) : (
            <><LoaderCircle size={12} /> Running</>
          )}
        </span>
      </button>
      {open && (
        <div className="tool-call__body" id={`tool-${toolCallId}`}>
          {result || 'Waiting for tool output...'}
        </div>
      )}
    </div>
  );
}
