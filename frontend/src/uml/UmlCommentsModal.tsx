import { useEffect, useState } from 'react'

type CommentItem = { id: string; parentId: string | null; authorName: string; authorEmail: string; elementoId: string | null; relacionId: string | null; elementoNombre: string | null; origenNombre: string | null; destinoNombre: string | null; posicionX: number | null; posicionY: number | null; content: string; status: 'OPEN' | 'RESOLVED'; createdAt: string; resolvedByName: string | null; resolvedAt: string | null }
type Props = { projectId: string; diagramId: string; token: string; canvasPosition: { x: number; y: number } | null; onClose: () => void; onError: (error: unknown) => void; onSaved?: () => void }

export function UmlCommentsModal({ projectId, diagramId, token, canvasPosition, onClose, onError, onSaved }: Props) {
  const [items, setItems] = useState<CommentItem[]>([])
  const [content, setContent] = useState('')
  const [replyTo, setReplyTo] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const selectedLabel = canvasPosition ? `el punto (${Math.round(canvasPosition.x)}, ${Math.round(canvasPosition.y)}) del lienzo` : null

  async function load() {
    const response = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/comments`, { headers: { Authorization: `Bearer ${token}` } })
    if (!response.ok) throw new Error('No se pudieron cargar los comentarios.')
    setItems(await response.json())
  }
  useEffect(() => { load().catch(onError).finally(() => setLoading(false)) }, [projectId, diagramId, token])

  async function create() {
    if (!content.trim() || !canvasPosition) return
    const payload = { content: content.trim(), elementoId: null, relacionId: null, parentId: replyTo, posicionX: canvasPosition?.x ?? null, posicionY: canvasPosition?.y ?? null }
    const response = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/comments`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(payload) })
    if (!response.ok) throw new Error('No se pudo guardar el comentario.')
    setContent(''); setReplyTo(null); await load(); onSaved?.()
  }
  async function resolve(item: CommentItem) {
    const response = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/comments/${item.id}/status`, { method: 'PATCH', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify({ status: item.status === 'RESOLVED' ? 'OPEN' : 'RESOLVED' }) })
    if (!response.ok) throw new Error('No se pudo actualizar el comentario.')
    await load()
  }

  return <div className="uml-modal-backdrop"><section className="uml-modal" style={{ maxWidth: 820, width: 'min(95vw, 820px)' }}>
    <header className="uml-modal-header"><h3>Comentarios en el lienzo (CU21)</h3><button type="button" className="close-btn" onClick={onClose}>×</button></header>
    <p style={{ color: '#64748b' }}>Escribe una observación en cualquier punto del lienzo, responde comentarios y márcalos como resueltos.</p>
    <div style={{ display: 'grid', gap: '0.65rem', margin: '1rem 0' }}>
      <small>Comentario anclado en {selectedLabel || 'el lienzo'}.</small>
      {replyTo && <small>Respondiendo a un comentario. <button type="button" onClick={() => setReplyTo(null)}>Cancelar respuesta</button></small>}
      <textarea value={content} onChange={e => setContent(e.target.value)} placeholder={selectedLabel ? `Comentario en ${selectedLabel}` : 'Escribe una observación…'} maxLength={2000} rows={3} />
      <button type="button" className="btn btn-primary" disabled={!content.trim() || !canvasPosition} onClick={() => void create().catch(onError)}>Agregar comentario</button>
    </div>
    {loading ? <p>Cargando comentarios…</p> : items.length === 0 ? <p>No hay comentarios todavía.</p> : <div style={{ display: 'grid', gap: '0.7rem', maxHeight: 380, overflowY: 'auto' }}>{items.map(item => <article key={item.id} style={{ border: '1px solid #e2e8f0', borderRadius: 8, padding: '0.75rem', opacity: item.status === 'RESOLVED' ? 0.65 : 1, marginLeft: item.parentId ? '1.5rem' : 0 }}><strong>{item.authorName}</strong><small style={{ marginLeft: 8, color: '#64748b' }}>{new Date(item.createdAt).toLocaleString('es-BO')}</small><p style={{ margin: '0.45rem 0' }}>{item.content}</p><small>{item.posicionX != null ? `Lienzo (${Math.round(item.posicionX)}, ${Math.round(item.posicionY ?? 0)})` : 'Lienzo'} · {item.status === 'RESOLVED' ? `Resuelto${item.resolvedByName ? ` por ${item.resolvedByName}` : ''}` : 'Abierto'}</small><div style={{ marginTop: 8, display: 'flex', gap: 8 }}><button type="button" onClick={() => setReplyTo(item.id)}>Responder</button><button type="button" onClick={() => void resolve(item).catch(onError)}>{item.status === 'RESOLVED' ? 'Reabrir' : 'Marcar resuelto'}</button></div></article>)}</div>}
    <footer className="uml-modal-actions"><button type="button" className="btn btn-secondary" onClick={onClose}>Cerrar</button></footer>
  </section></div>
}
