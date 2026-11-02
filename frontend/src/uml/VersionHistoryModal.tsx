import { useEffect, useRef, useState } from 'react'
import type { DiagramFullView } from './types'

export type VersionSummary = {
  id: string; diagramaId: string; numeroVersion: number; descripcion: string
  autorNombre: string; autorEmail: string; fecha: string; sourceRevision: number
  operationType: string; restoredFromVersionId: string | null; working: boolean
}
type VersionPage = { content: VersionSummary[]; number: number; totalPages: number; last: boolean }
type Props = {
  projectId: string; diagramId: string; token: string; revision?: number; isReadOnly: boolean
  onClose: () => void
  onView: (snapshot: DiagramFullView, version: VersionSummary) => void
  onCompare: (before: DiagramFullView, beforeVersion: VersionSummary, after: DiagramFullView, afterVersion: VersionSummary) => void
  onRestored: (fullView: DiagramFullView) => void; onError: (err: unknown) => void
}

export function VersionHistoryModal({ projectId, diagramId, token, revision, isReadOnly, onClose, onView, onCompare, onRestored, onError }: Props) {
  const [versions, setVersions] = useState<VersionSummary[]>([])
  const [page, setPage] = useState(0)
  const [last, setLast] = useState(false)
  const [loading, setLoading] = useState(false)
  const [description, setDescription] = useState('')
  const [creating, setCreating] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const createKey = useRef(crypto.randomUUID())
  const restoreKeys = useRef(new Map<string, string>())

  async function loadPage(index: number, replace = false) {
    if (loading || (!replace && last)) return
    try {
      setLoading(true)
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/versions?page=${index}&size=20`, { headers: { Authorization: `Bearer ${token}` } })
      if (!res.ok) throw new Error('No se pudo cargar el historial.')
      const data: VersionPage = await res.json()
      setVersions(old => replace ? data.content : [...old, ...data.content])
      setPage(index); setLast(data.last)
    } catch (err) { onError(err) } finally { setLoading(false) }
  }
  useEffect(() => { void loadPage(0, true) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function snapshot(version: VersionSummary) {
    const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/versions/${version.id}`, { headers: { Authorization: `Bearer ${token}` } })
    if (!res.ok) throw new Error('No se pudo cargar el snapshot de la versión.')
    const detail = await res.json()
    return JSON.parse(detail.snapshotJson) as DiagramFullView
  }

  async function view(version: VersionSummary) {
    try { onView(await snapshot(version), version) } catch (err) { onError(err) }
  }
  async function compare() {
    if (selected.length !== 2) return
    const pair = selected.map(id => versions.find(version => version.id === id)!).sort((a, b) => a.numeroVersion - b.numeroVersion)
    try { const [before, after] = await Promise.all(pair.map(snapshot)); onCompare(before, pair[0], after, pair[1]) }
    catch (err) { onError(err) }
  }
  async function create(event: React.FormEvent) {
    event.preventDefault(); if (isReadOnly) return
    try {
      setCreating(true)
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/versions`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': createKey.current },
        body: JSON.stringify({ descripcion: description || undefined })
      })
      if (!res.ok) { const body = await res.json().catch(() => ({})); throw new Error(body.message || 'No se pudo guardar la versión.') }
      createKey.current = crypto.randomUUID(); setDescription(''); setLast(false); await loadPage(0, true)
    } catch (err) { onError(err) } finally { setCreating(false) }
  }
  async function restore(version: VersionSummary) {
    if (isReadOnly || !confirm(`¿Restaurar v${version.numeroVersion}? Se conservará el historial y se creará una nueva versión.`)) return
    try {
      setLoading(true)
      const key = restoreKeys.current.get(version.id) || crypto.randomUUID()
      restoreKeys.current.set(version.id, key)
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/versions/${version.id}/restore`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'X-Diagram-Revision': String(revision ?? 1), 'Idempotency-Key': key }
      })
      if (!res.ok) { const body = await res.json().catch(() => ({})); throw new Error(body.message || 'No se pudo restaurar la versión.') }
      onRestored(await res.json()); restoreKeys.current.delete(version.id); onClose()
    } catch (err) { onError(err) } finally { setLoading(false) }
  }

  return <div className="uml-modal-backdrop" role="presentation">
    <section className="uml-modal versions-modal" role="dialog" aria-modal="true" aria-labelledby="version-history-title" style={{ maxWidth: 820, width: 'min(95vw, 820px)' }}>
      <header className="uml-modal-header"><h3 id="version-history-title">Historial de versiones</h3><button type="button" className="close-btn" onClick={onClose} aria-label="Cerrar historial">×</button></header>
      {!isReadOnly && <form onSubmit={create} style={{ display: 'flex', gap: 8, margin: '14px 0' }}>
        <input value={description} maxLength={255} onChange={event => setDescription(event.target.value)} placeholder="Descripción opcional del cambio" aria-label="Descripción de la versión" style={{ flex: 1, minWidth: 0 }} />
        <button type="submit" disabled={creating}>{creating ? 'Guardando…' : 'Guardar nueva versión'}</button>
      </form>}
      <div onScroll={event => { const node = event.currentTarget; if (node.scrollHeight - node.scrollTop - node.clientHeight < 60 && !loading && !last) void loadPage(page + 1) }} style={{ maxHeight: 'min(55vh, 440px)', overflow: 'auto' }}>
        {versions.length === 0 && !loading ? <p>No hay versiones guardadas todavía.</p> : <table className="data-table" style={{ width: '100%' }}>
          <thead><tr><th>Versión</th><th>Descripción</th><th>Autor / origen</th><th>Fecha</th><th>Acciones</th></tr></thead>
          <tbody>{versions.map(version => <tr key={version.id}>
            <td><label><input type="checkbox" aria-label={`Seleccionar versión ${version.numeroVersion} para comparar`} checked={selected.includes(version.id)} onChange={event => setSelected(old => event.target.checked ? [...old.slice(-1), version.id] : old.filter(id => id !== version.id))} /> v{version.numeroVersion}</label><small style={{ display: 'block', color: version.working ? '#15803d' : '#64748b' }}>{version.working ? 'ACTIVA · autoguardado' : 'Cerrada · inmutable'}</small></td>
            <td>{version.descripcion || 'Sin descripción'}<small style={{ display: 'block', color: '#64748b' }}>Revisión {version.sourceRevision}</small></td>
            <td>{version.autorNombre}<small style={{ display: 'block', color: '#64748b' }}>{version.operationType}</small></td>
            <td>{new Date(version.fecha).toLocaleString('es-BO')}</td>
            <td style={{ whiteSpace: 'nowrap' }}><button type="button" onClick={() => void view(version)}>Ver</button>{!isReadOnly && <button type="button" onClick={() => void restore(version)} disabled={loading}>Restaurar</button>}</td>
          </tr>)}</tbody>
        </table>}
        {loading && <p role="status">Cargando…</p>}
      </div>
      <footer className="uml-modal-actions" style={{ display: 'flex', justifyContent: 'space-between', gap: 8, marginTop: 14 }}>
        <button type="button" disabled={selected.length !== 2 || loading} onClick={() => void compare()}>Comparar seleccionadas</button>
        <button type="button" onClick={onClose}>Cerrar</button>
      </footer>
    </section>
  </div>
}
