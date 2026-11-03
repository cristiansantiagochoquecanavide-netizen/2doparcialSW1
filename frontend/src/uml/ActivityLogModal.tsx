import { useEffect, useState } from 'react'

type ActivityItem = {
  versionId: string
  numeroVersion: number
  autorNombre: string
  autorEmail: string
  fecha: string
  revision: number
  operacion: string
  elementosAfectados: string[]
}

type Props = { projectId: string; diagramId: string; token: string; onClose: () => void; onError: (error: unknown) => void }

export function ActivityLogModal({ projectId, diagramId, token, onClose, onError }: Props) {
  const [items, setItems] = useState<ActivityItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    fetch(`/api/projects/${projectId}/diagrams/${diagramId}/versions/activity`, { headers: { Authorization: `Bearer ${token}` } })
      .then(async response => {
        if (!response.ok) throw new Error('No se pudo cargar la bitácora de actividad.')
        return response.json() as Promise<ActivityItem[]>
      })
      .then(data => { if (active) setItems(data) })
      .catch(error => { if (active) onError(error) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [projectId, diagramId, token, onError])

  return <div className="uml-modal-backdrop">
    <section className="uml-modal versions-modal" role="dialog" aria-modal="true" style={{ maxWidth: 900, width: 'min(95vw, 900px)' }}>
      <header className="uml-modal-header"><h3>Bitácora de actividad del equipo (CU24)</h3><button type="button" className="close-btn" onClick={onClose}>×</button></header>
      {loading ? <p>Cargando actividad…</p> : items.length === 0 ? <p>No hay actividad registrada todavía.</p> : <div style={{ overflowX: 'auto' }}>
        <table className="data-table" style={{ width: '100%' }}>
          <thead><tr><th>Fecha y hora</th><th>Usuario</th><th>Operación</th><th>Elementos afectados</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.versionId}>
            <td>{new Date(item.fecha).toLocaleString('es-BO')}<small style={{ display: 'block', color: '#64748b' }}>v{item.numeroVersion} · revisión {item.revision}</small></td>
            <td><strong>{item.autorNombre}</strong><small style={{ display: 'block', color: '#64748b' }}>{item.autorEmail}</small></td>
            <td>{item.operacion}</td>
            <td>{item.elementosAfectados.length ? <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>{item.elementosAfectados.map((element, index) => <li key={`${item.versionId}-${index}`}>{element}</li>)}</ul> : 'Sin cambios estructurales detectados'}</td>
          </tr>)}</tbody>
        </table>
      </div>}
      <footer className="uml-modal-actions"><button type="button" className="btn btn-secondary" onClick={onClose}>Cerrar</button></footer>
    </section>
  </div>
}
