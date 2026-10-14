import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowRight, Check, ClipboardCheck, Eye, RotateCcw } from 'lucide-react';
import { Navigate, useOutletContext } from 'react-router-dom';
import type { MeResponse } from '../auth';
import {
  listAdminOperations, listEvidence, reconcileOperation, OperationError, outcomeLabel,
  type ToolOperationView, type EvidenceView, type EvidenceRequest, type OutcomeDecision,
} from '../api/toolOperations';
import {
  DataPanel, EmptyState, Field, FilterBar, ManagementDialog, ManagementHeader,
  ManagementPage, Notice, RefreshButton, StatusBadge,
} from '../components/ManagementUI';

const shortId = (id: string) => `${id.slice(0, 8)}...${id.slice(-4)}`;

export default function AdminOperationsPage() {
  const { me } = useOutletContext<{ me: MeResponse | null }>();
  const administrator = me?.role === 'admin' || me?.role === 'platform_admin';
  const [state, setState] = useState('OUTCOME_UNKNOWN');
  const [after, setAfter] = useState<string | null>(null);
  const [rows, setRows] = useState<ToolOperationView[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [selected, setSelected] = useState<ToolOperationView | null>(null);
  const [history, setHistory] = useState<EvidenceView[]>([]);
  const [historyAfter, setHistoryAfter] = useState(0);
  const [historyNext, setHistoryNext] = useState<number | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [decision, setDecision] = useState<OutcomeDecision>('UNRESOLVED');
  const [reason, setReason] = useState('');
  const [evidenceRef, setEvidenceRef] = useState('');
  const [resultText, setResultText] = useState('');
  const [approveReplay, setApproveReplay] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dialogError, setDialogError] = useState('');
  const [pending, setPending] = useState<{ key: string; input: EvidenceRequest } | null>(null);
  const generation = useRef(0);

  const refresh = useCallback(async () => {
    if (!administrator) return;
    const request = ++generation.current;
    setLoading(true);
    setError('');
    try {
      const page = await listAdminOperations(state, after);
      if (request !== generation.current) return;
      setRows(page.items);
      setNext(page.nextCursor);
      setSelected(previous => previous ? page.items.find(row => row.id === previous.id) ?? previous : null);
    } catch (cause) {
      if (request === generation.current) setError(cause instanceof Error ? cause.message : 'Unable to load operations.');
    } finally {
      if (request === generation.current) setLoading(false);
    }
  }, [administrator, state, after]);

  useEffect(() => {
    setRows([]);
    setNext(null);
    void refresh();
    return () => { generation.current++; };
  }, [refresh]);

  useEffect(() => {
    if (!selected) return;
    let disposed = false;
    setHistoryLoading(true);
    setHistory([]);
    setHistoryNext(null);
    void listEvidence(selected, historyAfter).then(items => {
      if (disposed) return;
      setHistory(items);
      setHistoryNext(items.length === 25 ? items[items.length - 1].sequence : null);
    }).catch(cause => {
      if (!disposed) setDialogError(cause instanceof Error ? cause.message : 'Unable to load evidence.');
    }).finally(() => { if (!disposed) setHistoryLoading(false); });
    return () => { disposed = true; };
  }, [selected?.id, selected?.resolutionVersion, historyAfter]);

  function open(row: ToolOperationView) {
    setSelected(row);
    setHistoryAfter(0);
    setDecision('UNRESOLVED');
    setReason('');
    setEvidenceRef('');
    setResultText('');
    setApproveReplay(false);
    setDialogError('');
    setPending(null);
  }

  function close() {
    if (saving || pending) return;
    setSelected(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!selected || saving) return;
    const command = pending ?? {
      key: crypto.randomUUID(),
      input: {
        expectedRunVersion: selected.runVersion,
        expectedOperationVersion: selected.version,
        expectedResolutionVersion: selected.resolutionVersion,
        decision, reason: reason.trim(), evidenceRef: evidenceRef.trim(),
        resultText: decision === 'CONFIRMED_SUCCESS' ? resultText : null,
        approveReplay: decision === 'CONFIRMED_NOT_PERFORMED' && approveReplay,
      },
    };
    setPending(command);
    setSaving(true);
    setDialogError('');
    try {
      const outcome = await reconcileOperation(selected, command.key, command.input);
      setPending(null);
      setSelected(null);
      setNotice(outcome.reused ? 'The evidence was already recorded.' : 'External evidence recorded.');
      await refresh();
    } catch (cause) {
      if (cause instanceof OperationError && [400, 403, 404, 409].includes(cause.status)) setPending(null);
      setDialogError(cause instanceof Error ? cause.message : 'Unable to submit evidence.');
      if (cause instanceof OperationError && cause.status === 409) await refresh();
    } finally {
      setSaving(false);
    }
  }

  if (!administrator) return <Navigate to="/agents" replace />;
  const editable = selected?.status === 'OUTCOME_UNKNOWN' && selected.effectiveStatus !== 'CONFIRMED_SUCCESS';

  return (
    <ManagementPage admin>
      <ManagementHeader icon={ClipboardCheck} title="Tool operations" description="External execution outcomes"
        actions={<RefreshButton loading={loading} onClick={() => void refresh()} />} />
      <FilterBar>
        <Field label="Original state">
          <select aria-label="Original state" className="management-select" value={state} disabled={loading || saving} onChange={event => { setState(event.target.value); setAfter(null); }}>
            {['OUTCOME_UNKNOWN', 'ALL', 'SUCCEEDED', 'FAILED', 'RUNNING', 'CANCELLED'].map(value => <option key={value} value={value}>{outcomeLabel(value)}</option>)}
          </select>
        </Field>
      </FilterBar>
      {error && <Notice tone="error">{error}</Notice>}
      {notice && <Notice tone="success">{notice}</Notice>}
      <DataPanel title="Execution records">
        <div className="data-table-wrap">
          <table className="data-table">
            <thead><tr><th>Tool</th><th>Run</th><th>User</th><th>Original state</th><th>Effective outcome</th><th>Prepared</th><th aria-label="Actions" /></tr></thead>
            <tbody>{rows.map(row => (
              <tr key={row.id}>
                <td><strong>{row.toolName}</strong></td>
                <td className="mono-text" title={row.runId}>{shortId(row.runId)}</td>
                <td className="mono-text" title={row.userId}>{shortId(row.userId)}</td>
                <td><StatusBadge status={outcomeLabel(row.status)} tone={row.status === 'OUTCOME_UNKNOWN' ? 'warning' : 'neutral'} /></td>
                <td><StatusBadge status={outcomeLabel(row.effectiveStatus)} tone={row.effectiveStatus === 'CONFIRMED_SUCCESS' ? 'success' : 'neutral'} /></td>
                <td>{new Date(row.preparedAt).toLocaleString()}</td>
                <td><button className="icon-button" title="Review external outcome" aria-label={`Review ${row.toolName} ${row.id}`} type="button" onClick={() => open(row)}><Eye size={16} /></button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
        {!rows.length && <EmptyState>{loading ? 'Loading operations...' : 'No matching operations.'}</EmptyState>}
        <div className="operation-pagination">
          {after && <button className="quiet-button" type="button" disabled={loading} onClick={() => setAfter(null)}><RotateCcw size={14} />First page</button>}
          {next && <button className="quiet-button" type="button" disabled={loading} onClick={() => setAfter(next)}><ArrowRight size={14} />Next page</button>}
        </div>
      </DataPanel>
      {selected && <ManagementDialog title={selected.toolName} description={selected.id} bodyClassName="operation-dialog-body" closeDisabled={saving || !!pending} onClose={close} onSubmit={event => void submit(event)} footer={(
        <>
          <button className="quiet-button" type="button" disabled={saving || !!pending} onClick={close}>Close</button>
          {(editable || pending) && <button className="primary-button" type="submit" disabled={saving || historyLoading}><Check size={14} />{saving ? 'Submitting' : pending ? 'Retry submission' : 'Record evidence'}</button>}
        </>
      )}>
        <div className="operation-facts">
          <span>Original: {outcomeLabel(selected.status)}</span>
          <span>Effective: {outcomeLabel(selected.effectiveStatus)}</span>
          <code title={selected.inputHash}>Input: {selected.inputHash}</code>
          <span>Evidence version: {selected.resolutionVersion}</span>
          <span>Contract: {selected.toolContractRecoverable ? 'verified' : 'unverified'}</span>
        </div>
        {dialogError && <Notice tone="error">{dialogError}</Notice>}
        {pending && !saving && <Notice tone="info">Submission is unconfirmed. Retry the same evidence before leaving.</Notice>}
        {editable && <fieldset className="operation-evidence-form" disabled={saving || !!pending}>
          <Field label="External outcome">
            <select aria-label="External outcome" className="management-select" value={decision} onChange={event => { setDecision(event.target.value as OutcomeDecision); setApproveReplay(false); }}>
              <option value="UNRESOLVED">Still unresolved</option>
              <option value="CONFIRMED_SUCCESS">Confirmed successful</option>
              <option value="CONFIRMED_NOT_PERFORMED">Confirmed not performed</option>
            </select>
          </Field>
          <Field label="Reason"><textarea aria-label="Reason" className="management-input" value={reason} required maxLength={2000} rows={3} onChange={event => setReason(event.target.value)} /></Field>
          <Field label="External evidence reference"><input aria-label="External evidence reference" className="management-input" value={evidenceRef} required maxLength={2048} onChange={event => setEvidenceRef(event.target.value)} /></Field>
          {decision === 'CONFIRMED_SUCCESS' && <Field label="Confirmed external result"><textarea aria-label="Confirmed external result" className="management-input" value={resultText} required maxLength={16000} rows={4} onChange={event => setResultText(event.target.value)} /></Field>}
          {decision === 'CONFIRMED_NOT_PERFORMED' && <label className="management-check"><input type="checkbox" checked={approveReplay} disabled={!selected.toolContractRecoverable || !selected.logicalOriginId} onChange={event => setApproveReplay(event.target.checked)} />Approve one replay within 10 minutes</label>}
          {decision === 'CONFIRMED_NOT_PERFORMED' && !selected.toolContractRecoverable && <Notice tone="info">Replay unavailable: implementation identity is not verified.</Notice>}
        </fieldset>}
        <section className="operation-evidence-history" aria-label="Evidence history">
          <h3>Evidence history</h3>
          {historyLoading ? <p>Loading evidence...</p> : !history.length ? <p>No evidence recorded.</p> : history.map(item => <div className="operation-evidence-entry" key={item.id}>
            <strong>#{item.sequence} {outcomeLabel(item.decision)}</strong>
            <span>{new Date(item.createdAt).toLocaleString()}{item.replayApproved ? ' · Replay approved' : ''}</span>
            <p>{item.reason}</p><code>{item.evidenceRef}</code>
          </div>)}
          <div className="operation-pagination">
            {historyAfter > 0 && <button className="quiet-button" type="button" disabled={historyLoading} onClick={() => setHistoryAfter(0)}><RotateCcw size={14} />First evidence</button>}
            {historyNext && <button className="quiet-button" type="button" disabled={historyLoading} onClick={() => setHistoryAfter(historyNext)}><ArrowRight size={14} />Next evidence</button>}
          </div>
        </section>
      </ManagementDialog>}
    </ManagementPage>
  );
}
