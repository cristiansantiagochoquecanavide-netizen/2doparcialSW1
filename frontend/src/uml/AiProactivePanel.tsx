import { useCallback, useEffect, useState } from 'react'
import { analysisRequest, canEditSharedContext } from './agentAnalysisClient.mjs'

type Recommendation = { id: string; revision: number; status: string; createdAt: string; conversationId: string; details: { title: string; severity: string; explanation: string; recommendedChange: string; justification: string; confidence: number; elements?: { id: string; name: string }[]; elementIds: string[] } }
type AnalysisStatus = { globallyEnabled: boolean; policy: { automatic_enabled: boolean; max_calls: number; token_budget: number; explicit_reserve: number; period_seconds: number; paused_until: string | number | null }; usage: { calls: number; tokens: number; running: number; estimated: number }; job: { last_status: string; analyzed_revision: number | null } | null; recommendations: Recommendation[]; hypotheses: { id: string; content: string }[]; needsContext: boolean; classCount: number; currentRevision: number }
type Props = { projectId: string; diagramId: string | null; token: string; role: string; active: boolean; onDescribe: () => void; onOpenConversation: (id: string) => void }
const labels: Record<string, string> = { PENDING: 'Esperando cambios agrupados', ANALYZING: 'Analizando el modelo', PUBLISHED: 'Recomendación publicada', NO_RECOMMENDATION: 'Sin una mejora concreta que recomendar', SEMANTICALLY_UNCHANGED: 'Sin cambios semánticos nuevos', INSUFFICIENT_CONTEXT: 'Contexto insuficiente para recomendar', BUDGET_PAUSED: 'Análisis pausado por presupuesto', RETRY_PENDING: 'Reintento pendiente', ERROR: 'No se pudo completar el análisis', LEASE_EXHAUSTED: 'Se agotaron los intentos de recuperación', DISABLED: 'Recomendaciones automáticas desactivadas', STALE_DISCARDED: 'Resultado descartado por cambios posteriores', DUPLICATE_SUPPRESSED: 'Se evitó repetir una recomendación' }

export function AiProactivePanel({ projectId, diagramId, token, role, active, onDescribe, onOpenConversation }: Props) {
  const [status, setStatus] = useState<AnalysisStatus | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [dismissed, setDismissed] = useState(false)
  const [requested, setRequested] = useState(false)
  const editable = canEditSharedContext(role)
  const refresh = useCallback(async () => {
    if (!diagramId) return
    const result = await analysisRequest<AnalysisStatus>({ apiBase: import.meta.env.VITE_API_BASE_URL || '', projectId, diagramId, token }, 'status')
    setStatus(result); setError('')
  }, [projectId, diagramId, token])
  useEffect(() => {
    setStatus(null); setRequested(false)
    try { setDismissed(localStorage.getItem(`case:context-offer:${projectId}`) === 'dismissed') } catch { setDismissed(false) }
  }, [projectId, diagramId])
  useEffect(() => {
    if (!active || !diagramId) return
    let disposed = false
    const update = () => { if (!disposed) void refresh().catch((e) => { if (!disposed) setError(e.message) }) }
    update()
    const interval = window.setInterval(update, 8000)
    window.addEventListener('case:ai-message', update)
    return () => { disposed = true; clearInterval(interval); window.removeEventListener('case:ai-message', update) }
  }, [active, diagramId, refresh])
  async function action(kind: 'policy' | 'review' | 'disposition', body?: unknown) {
    if (!diagramId || !editable || busy) return
    setBusy(true); setError('')
    try { await analysisRequest({ apiBase: import.meta.env.VITE_API_BASE_URL || '', projectId, diagramId, token }, kind, body); setRequested(kind === 'review'); await refresh() }
    catch (e) { setError(e instanceof Error ? e.message : 'No se pudo completar la operación.') }
    finally { setBusy(false) }
  }
  function dismissOffer() { setDismissed(true); try { localStorage.setItem(`case:context-offer:${projectId}`, 'dismissed') } catch { /* Modeling remains available. */ } }
  const latest = status?.recommendations[0]
  if (!diagramId) return <section className="ai-proactive-panel"><p>Abre un diagrama para consultar sus recomendaciones.</p></section>
  return <section className="ai-proactive-panel" aria-label="Análisis del diagrama">
    <header><strong>Revisión del diagrama</strong><button type="button" disabled={!editable || busy || !status?.globallyEnabled} onClick={() => void action('review')}>{busy ? 'Procesando…' : 'Solicitar revisión'}</button></header>
    {status ? <>
      <label className="ai-automatic-toggle"><input type="checkbox" checked={status.policy.automatic_enabled} disabled={!editable || busy || !status.globallyEnabled} onChange={(e) => void action('policy', { enabled: e.target.checked })} />Recomendaciones automáticas</label>
      <p aria-live="polite">{!status.globallyEnabled ? 'El análisis IA está desactivado en el servidor.' : status.policy.paused_until && new Date(status.policy.paused_until).getTime() > Date.now() ? 'Groq solicitó una pausa temporal. Puedes seguir modelando.' : status.job ? labels[status.job.last_status] || 'Estado actualizado' : 'Todavía no se ha analizado este diagrama.'}{requested && ' Revisión solicitada.'}</p>
      <small>Revisión actual: {status.currentRevision} · Evaluada: {status.job?.analyzed_revision ?? '—'} · Consultas: {status.usage.calls}/{status.policy.max_calls} · Tokens automáticos: {status.usage.tokens}/{status.policy.token_budget - status.policy.explicit_reserve}{status.usage.running > 0 && ` · En curso: ${status.usage.running}`}{status.usage.estimated > 0 && ' · Incluye reservas o estimaciones conservadoras'}</small>
      {status.needsContext && !dismissed && editable && <div className="ai-context-offer"><p>¿Qué sistema está diseñando el equipo? Puedes describir su propósito y sus reglas para mejorar las recomendaciones.</p><button type="button" onClick={onDescribe}>Describir por texto o voz</button><button type="button" onClick={dismissOffer}>Continuar modelando</button></div>}
      {status.hypotheses?.map(hypothesis => <div className="ai-context-offer" key={hypothesis.id}><strong>Interpretación pendiente de confirmación</strong><p>{hypothesis.content}</p><button type="button" disabled={!editable} onClick={onDescribe}>Revisar y confirmar o corregir</button></div>)}
      {latest && editable && <button type="button" disabled={busy} onClick={() => window.dispatchEvent(new CustomEvent('case:prepare-proposal', { detail: { projectId, diagramId, recommendationId: latest.id } }))}>Preparar propuesta para la recomendación</button>}
      {latest && <article className="ai-recommendation"><strong>{latest.details.title}</strong><small>Equipo · Revisión {latest.revision} · {new Date(latest.createdAt).toLocaleString('es-BO')} · {latest.status === 'OPEN' ? 'Pendiente' : latest.status === 'RESOLVED' ? 'Atendida' : 'Descartada'}</small><p>{latest.details.explanation}</p><p><strong>Sugerencia:</strong> {latest.details.recommendedChange}</p><details><summary>Ver fundamento y elementos</summary><p>{latest.details.justification}</p><p>Confianza: {Math.round(latest.details.confidence * 100)}%</p><ul>{(latest.details.elements || latest.details.elementIds.map((id) => ({ id, name: id }))).map((element) => <li key={element.id}>{element.name}</li>)}</ul></details><div className="ai-recommendation-actions"><button type="button" onClick={() => onOpenConversation(latest.conversationId)}>Ver conversación grupal</button>{latest.status === 'OPEN' && editable && <><button type="button" disabled={busy} onClick={() => void action('disposition', { id: latest.id, status: 'RESOLVED' })}>Atendida</button><button type="button" disabled={busy} onClick={() => void action('disposition', { id: latest.id, status: 'DISMISSED' })}>Descartar</button></>}</div></article>}
    </> : <p>Cargando estado del agente…</p>}
    {error && <p role="alert" className="ai-error">{error}<button type="button" onClick={() => void refresh().catch((e) => setError(e.message))}>Reintentar</button></p>}
  </section>
}
