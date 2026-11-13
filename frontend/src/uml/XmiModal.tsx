import { useState } from 'react'
import { DiagramFullView } from './types'
import { ExchangeIcon, LockIcon, CheckIcon, AlertIcon } from './icons'
import { AiModalShell } from './AiModalShell'
import { exportXmiFile } from './xmiExport.mjs'
import './xmi-modal.css'

const apiBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

type Props = {
  projectId: string
  diagramId: string
  diagramName: string
  token: string
  revision?: number
  isReadOnly: boolean
  isLocked?: boolean
  lockUser?: string | null
  onClose: () => void
  onDiagramUpdated: (fullView: DiagramFullView) => void
  onError: (err: unknown) => void
}

export function XmiModal({
  projectId,
  diagramId,
  diagramName,
  token,
  revision,
  isReadOnly,
  isLocked = false,
  lockUser = null,
  onClose,
  onDiagramUpdated,
  onError
}: Props) {
  const [activeFormat, setActiveFormat] = useState<'xmi' | 'json'>('xmi')
  const [importText, setImportText] = useState('')
  const [loading, setLoading] = useState(false)
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null)
  const [selectedFileSize, setSelectedFileSize] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)

  // Descargar archivo XMI / XML con cardinalidades y bloqueo colaborativo
  async function handleExportXmi(ext: 'xmi' | 'xml' = 'xmi') {
    try {
      setLoading(true)
      setStatusMessage({ type: 'success', text: 'Iniciando exportación EA XMI y creando bloqueo colaborativo en backend...' })
      await exportXmiFile({ apiBase, projectId, diagramId, token, diagramName, ext })
      setStatusMessage({ type: 'success', text: '✅ Exportación completada exitosamente y bloqueo liberado.' })
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Error al exportar XMI'
      setStatusMessage({ type: 'error', text: msg })
      onError(err)
    } finally {
      setLoading(false)
    }
  }

  // Descargar backup JSON completo
  async function handleExportJson() {
    try {
      setLoading(true)
      setStatusMessage(null)
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/xmi/export-json`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      if (!res.ok) throw new Error('Error al exportar archivo JSON.')
      const blob = await res.blob()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${diagramName.toLowerCase().replace(/\s+/g, '_')}_backup.json`
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(url)
      document.body.removeChild(a)
    } catch (err) {
      onError(err)
    } finally {
      setLoading(false)
    }
  }

  // Importar archivo
  async function handleImportSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (isReadOnly || isLocked) return

    if (!importText.trim()) {
      setStatusMessage({
        type: 'error',
        text: activeFormat === 'xmi'
          ? 'Por favor selecciona un archivo .xmi / .xml de Enterprise Architect o pega su contenido XML antes de importar.'
          : 'Por favor selecciona un archivo .json o pega el JSON del diagrama antes de importar.'
      })
      return
    }

    try {
      setLoading(true)
      setStatusMessage({ type: 'success', text: 'Procesando importación XMI en backend con bloqueo de concurrencia activo...' })
      const endpoint =
        activeFormat === 'xmi'
          ? `/api/projects/${projectId}/diagrams/${diagramId}/xmi/import`
          : `/api/projects/${projectId}/diagrams/${diagramId}/xmi/import-json`

      const contentType = activeFormat === 'xmi' ? 'application/xml' : 'application/json'

      const res = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': contentType,
          Authorization: `Bearer ${token}`,
          'X-Diagram-Revision': String(revision ?? 1)
        },
        body: importText
      })

      if (!res.ok) {
        const b = await res.json().catch(() => ({}))
        throw new Error(b.message || 'Error al importar diagrama.')
      }

      const updated: DiagramFullView = await res.json()
      const elCount = updated.elements?.length ?? 0
      const relCount = updated.relations?.length ?? 0

      setStatusMessage({
        type: 'success',
        text: `¡Importación exitosa! Se cargaron ${elCount} clases/tablas y ${relCount} relaciones al diagrama. Bloqueo de seguridad liberado.`
      })

      onDiagramUpdated(updated)
      setTimeout(() => {
        onClose()
      }, 1500)
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Error desconocido al importar'
      setStatusMessage({ type: 'error', text: msg })
      onError(err)
    } finally {
      setLoading(false)
    }
  }

  function handleFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    setSelectedFileName(file.name)
    setSelectedFileSize((file.size / 1024).toFixed(1) + ' KB')
    setStatusMessage(null)

    const reader = new FileReader()
    reader.onload = (event) => {
      setImportText(String(event.target?.result || ''))
    }
    reader.readAsText(file)
  }

  return (
    <AiModalShell
      title="Enterprise Architect XMI"
      subtitle="Importación y exportación de modelos"
      icon={<ExchangeIcon size={20} color="#1d4ed8" />}
      onClose={onClose}
    >
      <div className="xmi-modal-content">
        <div className="xmi-legacy-header" style={{ display: 'none' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
            <ExchangeIcon size={20} color="#1d4ed8" />
            <div>
              <h3 style={{ margin: 0, fontSize: '1.1rem', color: '#0f172a', fontWeight: 'bold' }}>Enterprise Architect XMI & Interoperabilidad</h3>
              <span style={{ fontSize: '0.82rem', color: '#475569' }}>
                Importación/exportación con bloqueo colaborativo en tiempo real
              </span>
            </div>
          </div>
          <button type="button" className="close-btn" onClick={onClose}>
            ✕
          </button>
        </div>

        {isLocked && (
          <div style={{ marginTop: '0.75rem', padding: '0.75rem 1rem', background: '#fef3c7', border: '1px solid #f59e0b', borderRadius: '6px', color: '#92400e', fontSize: '0.85rem', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <LockIcon size={16} /> El diagrama se encuentra bloqueado por <strong>{lockUser}</strong> durante una operación activa.
          </div>
        )}

        {/* Notificación de Estado */}
        {statusMessage && (
          <div
            style={{
              marginTop: '0.75rem',
              padding: '0.75rem 1rem',
              borderRadius: '6px',
              fontSize: '0.85rem',
              fontWeight: 600,
              background: statusMessage.type === 'success' ? '#dcfce7' : '#fee2e2',
              color: statusMessage.type === 'success' ? '#14532d' : '#991b1b',
              border: `1px solid ${statusMessage.type === 'success' ? '#86efac' : '#fca5a5'}`,
              display: 'flex',
              alignItems: 'center',
              gap: '6px'
            }}
          >
            {statusMessage.type === 'success' ? <CheckIcon size={16} /> : <AlertIcon size={16} />}
            {statusMessage.text}
          </div>
        )}

        {/* Selector de Formato: XMI / XML prioritario vs JSON nativo */}
        <div className="xmi-format-switcher" style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem', borderBottom: '1px solid #e2e8f0', paddingBottom: '0.5rem' }}>
          <button
            type="button"
            className={`tab-btn ${activeFormat === 'xmi' ? 'active' : ''}`}
            onClick={() => { setActiveFormat('xmi'); setImportText(''); setSelectedFileName(null); setStatusMessage(null) }}
            style={{
              padding: '0.5rem 1rem',
              borderRadius: '6px',
              fontWeight: activeFormat === 'xmi' ? 700 : 500,
              background: activeFormat === 'xmi' ? '#2563eb' : '#f1f5f9',
              color: activeFormat === 'xmi' ? 'white' : '#475569',
              border: 'none',
              cursor: 'pointer'
            }}
          >
            🏢 Enterprise Architect XMI / XML (.xmi, .xml)
          </button>
          <button
            type="button"
            className={`tab-btn ${activeFormat === 'json' ? 'active' : ''}`}
            onClick={() => { setActiveFormat('json'); setImportText(''); setSelectedFileName(null); setStatusMessage(null) }}
            style={{
              padding: '0.5rem 1rem',
              borderRadius: '6px',
              fontWeight: activeFormat === 'json' ? 700 : 500,
              background: activeFormat === 'json' ? '#2563eb' : '#f1f5f9',
              color: activeFormat === 'json' ? 'white' : '#475569',
              border: 'none',
              cursor: 'pointer'
            }}
          >
            📋 Backup JSON Completo (.json)
          </button>
        </div>

        <div className="xmi-sections" style={{ marginTop: '1rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {/* Sección de Exportación */}
          <div className="xmi-section xmi-export-section" style={{ background: '#f8fafc', padding: '1.25rem', borderRadius: '8px', border: '1px solid #e2e8f0' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.4rem' }}>
              <h4 style={{ margin: 0 }}>Exportar Diagrama Actual</h4>
              <span style={{ fontSize: '0.75rem', background: '#dbeafe', color: '#1d4ed8', padding: '2px 8px', borderRadius: '4px', fontWeight: 600 }}>
                {activeFormat === 'xmi' ? 'Formato OMG XMI 2.1 / Enterprise Architect' : 'Formato JSON'}
              </span>
            </div>
            <p style={{ fontSize: '0.85rem', color: '#64748b', marginBottom: '1rem' }}>
              {activeFormat === 'xmi'
                ? 'Genera un documento UML 2.1 / XMI 2.1 con clases, interfaces, enumeraciones, atributos, métodos, multiplicidades y geometría compatible con Sparx Enterprise Architect.'
                : 'Exporta la estructura completa con coordenadas en lienzo, estilos, descripciones y multiplicidades en formato JSON.'}
            </p>
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              {activeFormat === 'xmi' ? (
                <>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={loading || isLocked}
                    onClick={() => handleExportXmi('xmi')}
                    style={{ background: '#2563eb', color: 'white', padding: '0.6rem 1.2rem', borderRadius: '6px', border: 'none', cursor: 'pointer', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '6px' }}
                  >
                    📥 Descargar como XMI (.xmi)
                  </button>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    disabled={loading || isLocked}
                    onClick={() => handleExportXmi('xml')}
                    style={{ background: '#475569', color: 'white', padding: '0.6rem 1.2rem', borderRadius: '6px', border: 'none', cursor: 'pointer', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '6px' }}
                  >
                    📥 Descargar como XML (.xml)
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={loading || isLocked}
                  onClick={handleExportJson}
                  style={{ background: '#2563eb', color: 'white', padding: '0.6rem 1.2rem', borderRadius: '6px', border: 'none', cursor: 'pointer', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '6px' }}
                >
                  📥 Descargar Backup JSON (.json)
                </button>
              )}
            </div>
            {activeFormat === 'xmi' && (
              <div className="xmi-ea-import-hint" role="note">
                <strong>Para que EA cree el diagrama:</strong> en Enterprise Architect usa{' '}
                <strong>Publish → Model Exchange → Import → XMI File</strong> y marca{' '}
                <strong>Import Diagrams</strong> antes de importar. El XMI incluye el diagrama y la posición de sus elementos.
              </div>
            )}
          </div>

          {/* Sección de Importación */}
          {!isReadOnly && (
            <div className="xmi-section xmi-import-section" style={{ background: '#f8fafc', padding: '1.25rem', borderRadius: '8px', border: '1px solid #e2e8f0' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.4rem' }}>
                <h4 style={{ margin: 0 }}>
                  Importar {activeFormat === 'xmi' ? 'Archivo XMI / XML (.xmi / .xml)' : 'Archivo JSON (.json)'}
                </h4>
                {activeFormat === 'xmi' && (
                  <span style={{ fontSize: '0.7rem', background: '#ecfdf5', color: '#047857', padding: '2px 8px', borderRadius: '4px', fontWeight: 600 }}>
                    Enterprise Architect 1.1 / 2.1 & Tablas
                  </span>
                )}
              </div>
              <p style={{ fontSize: '0.85rem', color: '#64748b', marginBottom: '0.75rem' }}>
                {activeFormat === 'xmi'
                  ? 'Compatible con archivos exportados de Sparx Enterprise Architect (XMI 1.1, 2.1, Native XML, tablas relacionales con columnas), StarUML y Visual Paradigm.'
                  : 'Restaura fielmente el diagrama completo con clases, relaciones y posiciones exactas desde el archivo JSON:'}
              </p>

              <form onSubmit={handleImportSubmit}>
                <div style={{ marginBottom: '0.75rem' }}>
                  <input
                    className="xmi-file-input"
                    type="file"
                    accept={activeFormat === 'xmi' ? '.xmi,.xml,.uml' : '.json'}
                    onChange={handleFileSelected}
                    disabled={isLocked}
                    style={{ fontSize: '0.85rem', display: 'block' }}
                  />
                  {selectedFileName && (
                    <div style={{ marginTop: '0.4rem', fontSize: '0.8rem', color: '#16a34a', display: 'flex', alignItems: 'center', gap: '6px', fontWeight: 600 }}>
                      <span>📄 Archivo cargado: {selectedFileName} ({selectedFileSize})</span>
                    </div>
                  )}
                </div>

                <textarea
                  className="xmi-import-textarea"
                  rows={6}
                  value={importText}
                  onChange={(e) => setImportText(e.target.value)}
                  disabled={isLocked}
                  placeholder={
                    activeFormat === 'xmi'
                      ? 'O pega aquí directamente el contenido XML / XMI del archivo...'
                      : 'O pega aquí el contenido JSON del diagrama...'
                  }
                  style={{ width: '100%', padding: '0.5rem', borderRadius: '6px', border: '1px solid #cbd5e1', fontFamily: 'monospace', fontSize: '0.8rem', resize: 'vertical' }}
                />

                <div style={{ marginTop: '0.75rem', display: 'flex', justifyContent: 'flex-end' }}>
                  <button
                    type="submit"
                    disabled={loading || isLocked || !importText.trim()}
                    className="btn btn-primary"
                    style={{ background: '#16a34a', color: 'white', padding: '0.5rem 1rem', borderRadius: '6px', border: 'none', cursor: 'pointer', fontWeight: 600 }}
                  >
                    {loading ? 'Importando modelo...' : activeFormat === 'xmi' ? '📤 Importar XMI/XML al Diagrama' : '📤 Importar JSON al Diagrama'}
                  </button>
                </div>
              </form>
            </div>
          )}
        </div>

        <div className="uml-modal-actions xmi-modal-actions" style={{ marginTop: '1.25rem', display: 'flex', justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-secondary" onClick={onClose} style={{ padding: '0.4rem 1rem', borderRadius: '6px' }}>
            Cerrar
          </button>
        </div>
      </div>
    </AiModalShell>
  )
}
