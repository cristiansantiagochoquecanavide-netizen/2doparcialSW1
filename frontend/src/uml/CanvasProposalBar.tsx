import { useState } from 'react'
import type { Proposal } from './umlProposalClient.mjs'

export function CanvasProposalBar({ active, pending, editable, busy, error, stale, onSelect, onApprove, onReject, onRegenerate, onFocus }: {
  active: Proposal | null; pending: Proposal[]; editable: boolean; busy: boolean; error: string; stale: boolean
  onSelect: (id: string) => void; onApprove: (confirmed: boolean) => void; onReject: () => void; onRegenerate: () => void
  onFocus: () => void
}) {
  const [confirmed, setConfirmed] = useState(false)
  if (!active && !pending.length && !error) return null
  return <section className="acodi-canvas-bar" aria-label="Propuesta ACODI en el lienzo">
    <strong>ACODI · {active?.description || 'Propuestas pendientes'}</strong>
    {active?.scope === 'PRIVATE' && <small>Vista previa privada · solo tú</small>}
    <select aria-label="Propuesta compartida" value={active?.id || ''} disabled={busy || !editable} onChange={e => { setConfirmed(false); onSelect(e.target.value) }}>
      <option value="" disabled>Seleccionar propuesta</option>{pending.map(p => <option key={p.id} value={p.id}>{p.description} · {p.requesterName}</option>)}
    </select>
    {active && <>
      <button type="button" disabled={busy || !editable || stale || (active.preview.destructive && !confirmed)} onClick={() => onApprove(confirmed)}>Aprobar</button>
      <button type="button" disabled={busy || !editable} onClick={onReject}>Rechazar</button>
      <button type="button" onClick={onFocus}>Ver cambio</button>
      {active.preview.destructive && <label><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />Confirmo las eliminaciones indicadas</label>}
      {stale && <span role="alert">El diagrama cambió (HTTP 409). Regenera antes de aprobar. <button type="button" disabled={busy || !editable} onClick={onRegenerate}>Regenerar propuesta</button></span>}
    </>}
    {error && <span role="alert">{error}</span>}
  </section>
}
