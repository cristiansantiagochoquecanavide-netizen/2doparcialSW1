import { useCallback, useEffect, useRef, useState } from 'react'
import { applyProposal, proposalRequest, type Proposal, type ProposalConfig } from './umlProposalClient.mjs'
type CanvasView = { active: Proposal | null; pending: Proposal[] }

export function useCanvasProposal(config: ProposalConfig, role: string, onApplied: () => Promise<void>) {
  const [state, setState] = useState<CanvasView>({ active: null, pending: [] })
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const sequence = useRef(0), applicationKey = useRef<{ id: string; key: string } | null>(null)
  const running = useRef(false)
  const activeRef = useRef<Proposal | null>(null), reloadBase = useRef(onApplied)
  const privateSelection = useRef('')
  reloadBase.current = onApplied
  const { apiBase, projectId, diagramId, token } = config
  const refresh = useCallback(async () => {
    if (!diagramId) return
    const seq = ++sequence.current
    const authorized = { apiBase, projectId, diagramId, token }
    const result = await proposalRequest<CanvasView>(authorized, 'canvas')
    if (privateSelection.current) {
      const privateProposal = await proposalRequest<Proposal>(authorized, privateSelection.current)
      if (privateProposal.scope === 'PRIVATE' && ['PENDIENTE', 'CONFLICTO'].includes(privateProposal.status)) {
        result.active = privateProposal; result.pending = [privateProposal, ...result.pending]
      } else privateSelection.current = ''
    }
    const previous = activeRef.current
    if (previous && previous.id !== result.active?.id && seq === sequence.current) {
      const decision = await proposalRequest<Proposal>(authorized, previous.id)
      // Keep the overlay until the committed base is loaded, including on other collaborators.
      if (decision.status === 'APLICADA') await reloadBase.current()
    }
    if (seq === sequence.current) { activeRef.current = result.active; setState(result) }
  }, [apiBase, projectId, diagramId, token])
  useEffect(() => {
    setState({ active: null, pending: [] }); activeRef.current = null; privateSelection.current = ''; setError(''); applicationKey.current = null
    const reload = () => void refresh().catch(() => setError('No se pudo sincronizar la propuesta del lienzo.'))
    const receive = (event: Event) => { if ((event as CustomEvent).detail?.diagramId === diagramId) reload() }
    const receivePrivate = (event: Event) => {
      const detail = (event as CustomEvent).detail
      if (detail?.projectId === projectId && detail.diagramId === diagramId && typeof detail.proposalId === 'string') {
        privateSelection.current = detail.proposalId; reload() // GET rechecks JWT, ownership and project before rendering.
      }
    }
    reload(); window.addEventListener('case:canvas-proposal', receive)
    window.addEventListener('case:canvas-private-proposal', receivePrivate)
    window.addEventListener('online', reload)
    return () => { ++sequence.current; window.removeEventListener('case:canvas-proposal', receive); window.removeEventListener('case:canvas-private-proposal', receivePrivate); window.removeEventListener('online', reload) }
  }, [refresh, diagramId])
  async function action(run: () => Promise<unknown>) {
    if (running.current) return
    running.current = true
    setBusy(true); setError('')
    try { await run(); await refresh() }
    catch (failure) {
      setError((failure as { status?: number }).status === 409 ? 'Conflicto HTTP 409: revisa o regenera la propuesta; no se aplicaron cambios.' : failure instanceof Error ? failure.message : 'No se pudo completar la operación.')
      await refresh().catch(() => undefined)
    } finally { running.current = false; setBusy(false) }
  }
  const select = (id: string) => action(async () => {
    if (id === privateSelection.current) return
    privateSelection.current = ''; await proposalRequest(config, `${id}/canvas`, {})
  })
  const reject = () => state.active && action(() => proposalRequest(config, `${state.active!.id}/reject`, {}))
  const approve = (confirmed: boolean) => {
    if (!state.active) return
    if (applicationKey.current?.id !== state.active.id) applicationKey.current = { id: state.active.id, key: crypto.randomUUID() }
    return action(() => applyProposal(config, state.active!, role, confirmed, applicationKey.current!.key))
  }
  const regenerate = () => state.active && action(async () => {
    const result = await proposalRequest<{ proposal: Proposal | null; errorCode?: string }>(config, `${state.active!.id}/regenerate`, { clientRequestId: crypto.randomUUID() })
    if (!result.proposal || result.proposal.status !== 'PENDIENTE') throw new Error('No se pudo regenerar una propuesta aplicable. Revisa la solicitud en el chat.')
    if (result.proposal.scope === 'PRIVATE') privateSelection.current = result.proposal.id
    else { privateSelection.current = ''; await proposalRequest(config, `${result.proposal.id}/canvas`, {}) }
  })
  return { ...state, busy, error, select, reject, approve, regenerate }
}
