import { useCallback, useEffect, useRef, useState } from 'react'
import { applyProposal, canApplyProposal, proposalRequest, type Proposal, type ProposalConfig } from './umlProposalClient.mjs'
import { UmlProposalChanges } from './UmlProposalChanges'
import { canEditSharedContext } from './agentAnalysisClient.mjs'

type Props = { projectId: string; diagramId: string; conversationId: string; token: string; role: string; active: boolean; refreshKey: string }
type Generation = { requestId: string; status: string; proposal: Proposal | null; errorCode: string | null }
const states: Record<string,string> = { PENDIENTE: 'Pendiente de aprobación', ACLARACION: 'Necesita aclaración', APLICADA: 'Aplicada', RECHAZADA: 'Rechazada', CONFLICTO: 'Conflicto de revisión', FALLIDA: 'No se aplicó' }

export function UmlProposalsPanel({ projectId, diagramId, conversationId, token, role, active, refreshKey }: Props) {
  const [items, setItems] = useState<Proposal[]>([])
  const [selected, setSelected] = useState<Proposal | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [instruction, setInstruction] = useState('')
  const [requestId, setRequestId] = useState('')
  const identity = `${projectId}:${diagramId}:${conversationId}:${token}`
  const current = useRef(identity); current.current = identity
  const generationKeys = useRef(new Map<string, string>())
  const config: ProposalConfig = { apiBase: import.meta.env.VITE_API_BASE_URL || '', projectId, diagramId, token }
  const editable = canEditSharedContext(role)
  const refresh = useCallback(async () => {
    const key = identity
    const list = await proposalRequest<Proposal[]>(config, conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : '')
    if (key === current.current) setItems(list)
  }, [identity])
  useEffect(() => { setSelected(null); setConfirmed(false); setItems([]); setInstruction(''); setRequestId(''); setError(''); generationKeys.current.clear() }, [identity])
  useEffect(() => {
    if (!active) return
    const update = () => { void refresh().catch(e => setError(e.message)) }
    update(); window.addEventListener('case:ai-message', update)
    return () => window.removeEventListener('case:ai-message', update)
  }, [active, refreshKey, refresh])
  function open(proposal: Proposal) { setSelected(proposal); setConfirmed(false); setError('') }
  async function run(task: () => Promise<Proposal | null>) {
    if (busy) return
    const key = identity
    if (key !== current.current) return
    setBusy(true); setError('')
    try { const result = await task(); if (key !== current.current) return; if (result) open(result); await refresh() }
    catch (e) {
      if (key !== current.current) return
      setError(e instanceof Error ? e.message : 'No se pudo completar la propuesta.')
      if (selected) { try { const fresh = await proposalRequest<Proposal>(config, selected.id); if (key === current.current) setSelected(fresh) } catch { /* Keep the last preview if connectivity was lost. */ } }
    } finally { setBusy(false) }
  }
  async function generated(action: string, body: unknown) {
    const key = identity
    const input = body as Record<string, unknown>
    const signature = JSON.stringify([action, input.instruction, input.conversationId, input.recommendationId])
    const clientRequestId = generationKeys.current.get(signature) || String(input.clientRequestId)
    generationKeys.current.set(signature, clientRequestId)
    let result: Generation
    try { result = await proposalRequest<Generation>(config, action, { ...input, clientRequestId }) }
    catch (e) { if ((e as { status?: number }).status === 422 && key === current.current) generationKeys.current.delete(signature); throw e }
    if (key !== current.current) return null
    if (!result.proposal) { setRequestId(result.requestId); throw new Error('La preparación sigue en curso. Consulta su estado sin generar otra solicitud.') }
    generationKeys.current.delete(signature)
    setRequestId(''); return result.proposal
  }
  useEffect(() => {
    function prepare(event: Event) {
      const detail = (event as CustomEvent<{ projectId: string; diagramId: string; recommendationId: string }>).detail
      if (!detail || detail.projectId !== projectId || detail.diagramId !== diagramId || !editable || busy) return
      void run(() => generated('', { clientRequestId: crypto.randomUUID(), recommendationId: detail.recommendationId }))
    }
    window.addEventListener('case:prepare-proposal', prepare)
    return () => window.removeEventListener('case:prepare-proposal', prepare)
  }, [identity, busy, editable])
  return <section className="ai-proposals" aria-label="Propuestas UML">
    <header><strong>Propuestas UML</strong><button type="button" disabled={busy} onClick={() => void refresh().catch(e => setError(e.message))}>Actualizar</button></header>
    <small>Preparar una propuesta no cambia el diagrama. Solo se aplican los cambios aprobados en su vista previa.</small>
    <form onSubmit={e => { e.preventDefault(); if (instruction.trim() && editable && conversationId) void run(() => generated('', { clientRequestId: crypto.randomUUID(), instruction, conversationId })) }}>
      <textarea aria-label="Cambio UML solicitado" placeholder={conversationId ? 'Ej. Agrega una clase Pago relacionada con Pedido…' : 'Selecciona o crea una conversación para preparar cambios.'} value={instruction} maxLength={8000} disabled={!editable || busy || !conversationId} onChange={e => setInstruction(e.target.value)} rows={2} />
      <button disabled={!editable || busy || !conversationId || !instruction.trim()}>{busy ? 'Procesando…' : 'Preparar propuesta'}</button>
    </form>
    {requestId && <button disabled={busy} onClick={() => void run(async () => { const result = await proposalRequest<Generation>(config, `requests/${requestId}`); if (result.status === 'ERROR') throw new Error(result.errorCode || 'Error de generación'); if (result.proposal) setRequestId(''); return result.proposal })}>Consultar preparación</button>}
    {error && <p role="alert" className="ai-error">{error}</p>}
    <div className="ai-proposal-list">{items.map(item => <button key={item.id} disabled={busy} onClick={() => void run(() => proposalRequest<Proposal>(config, item.id))}><strong>{item.description}</strong><small>{states[item.status]} · {item.requesterName} · {item.scope === 'PRIVATE' ? 'Privada' : 'Equipo'} · {new Date(item.createdAt).toLocaleString('es-BO')}</small></button>)}</div>
    {selected && <div className="ai-proposal-backdrop"><section role="dialog" aria-modal="true" aria-label="Vista previa UML" className="ai-proposal-preview">
      <header><h3>{selected.description}</h3><button type="button" disabled={busy} onClick={() => setSelected(null)}>Cerrar</button></header>
      <p>{states[selected.status]} · Solicitante: {selected.requesterName} · {selected.scope === 'PRIVATE' ? 'Propuesta privada' : 'Propuesta del equipo'}</p>
      <p>Diagrama: {selected.diagramId}</p>
      <p>Revisión base: {selected.baseRevision} · Revisión actual: {selected.currentRevision}{selected.appliedRevision != null && ` · Aplicada en: ${selected.appliedRevision}`}{selected.deciderName && ` · Decisión: ${selected.deciderName}`}</p>
      {selected.preview.clarification && <p>{selected.preview.clarification} Corrige la solicitud y prepara una nueva propuesta.</p>}
      <UmlProposalChanges changes={selected.preview.changes} />
      {selected.preview.warnings.map((issue, i) => <p key={i}>{issue.severity}: {issue.mensaje}</p>)}
      {selected.status === 'PENDIENTE' && selected.preview.destructive && <label><input type="checkbox" checked={confirmed} disabled={busy || !editable} onChange={e => setConfirmed(e.target.checked)} />Confirmo las eliminaciones mostradas, incluidos atributos, métodos y relaciones afectados.</label>}
      {error && <p role="alert" className="ai-error">{error}</p>}
      <footer>
        <button disabled={busy || !canApplyProposal(selected, role, confirmed)} onClick={() => void run(() => applyProposal(config, selected, role, confirmed, crypto.randomUUID()))}>Aplicar cambios aprobados</button>
        <button disabled={busy || !editable || ['APLICADA','RECHAZADA'].includes(selected.status)} onClick={() => void run(() => proposalRequest<Proposal>(config, `${selected.id}/reject`, {}))}>Rechazar propuesta</button>
        <button disabled={busy || !editable || selected.status === 'APLICADA'} onClick={() => void run(() => generated(`${selected.id}/regenerate`, { clientRequestId: crypto.randomUUID() }))}>Regenerar con el modelo actual</button>
      </footer>
      <small>Si la revisión cambió, regenera y revisa nuevamente. Regenerar utiliza IA; aplicar y rechazar no consumen tokens.</small>
    </section></div>}
  </section>
}
