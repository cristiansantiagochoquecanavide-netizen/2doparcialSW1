import { useRef, useState } from 'react'
import type { DragEvent, FormEvent, ChangeEvent } from 'react'
import { DiagramFullView } from './types'
import { ExchangeIcon, LockIcon, CheckIcon, AlertIcon, UploadIcon, TrashIcon } from './icons'
import { AiModalShell } from './AiModalShell'
import { exportXmiFile } from './xmiExport.mjs'
import './xmi-modal.css'

const apiBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
type Format = 'xmi' | 'json'
type Operation = 'import' | 'export'

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

export function XmiModal({ projectId, diagramId, diagramName, token, revision, isReadOnly, isLocked = false, lockUser = null, onClose, onDiagramUpdated, onError }: Props) {
  const [activeFormat, setActiveFormat] = useState<Format>('xmi')
  const [operation, setOperation] = useState<Operation>(isReadOnly ? 'export' : 'import')
  const [importText, setImportText] = useState('')
  const [loading, setLoading] = useState(false)
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null)
  const [selectedFileSize, setSelectedFileSize] = useState<string | null>(null)
  const [isDragging, setIsDragging] = useState(false)
  const [statusMessage, setStatusMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  function setFormat(format: Format) {
    setActiveFormat(format)
    setImportText('')
    setSelectedFileName(null)
    setSelectedFileSize(null)
    if (fileInput.current) fileInput.current.value = ''
    setStatusMessage(null)
  }

  async function handleExportXmi(ext: 'xmi' | 'xml') {
    try {
      setLoading(true)
      setStatusMessage(null)
      await exportXmiFile({ apiBase, projectId, diagramId, token, diagramName, ext })
      setStatusMessage({ type: 'success', text: `Exportación ${ext.toUpperCase()} completada.` })
    } catch (err) {
      setStatusMessage({ type: 'error', text: err instanceof Error ? err.message : 'Error al exportar XMI.' })
      onError(err)
    } finally { setLoading(false) }
  }

  async function handleExportJson() {
    try {
      setLoading(true)
      setStatusMessage(null)
      const res = await fetch(`${apiBase}/api/projects/${projectId}/diagrams/${diagramId}/xmi/export-json`, { headers: { Authorization: `Bearer ${token}` } })
      if (!res.ok) throw new Error('Error al exportar archivo JSON.')
      const url = window.URL.createObjectURL(await res.blob())
      const a = document.createElement('a')
      a.href = url
      a.download = `${diagramName.toLowerCase().replace(/\s+/g, '_')}_backup.json`
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.setTimeout(() => window.URL.revokeObjectURL(url), 1000)
      setStatusMessage({ type: 'success', text: 'Backup JSON descargado correctamente.' })
    } catch (err) {
      setStatusMessage({ type: 'error', text: err instanceof Error ? err.message : 'Error al exportar JSON.' })
      onError(err)
    } finally { setLoading(false) }
  }

  async function handleImportSubmit(e: FormEvent) {
    e.preventDefault()
    if (isReadOnly || isLocked) return
    if (!importText.trim()) {
      setStatusMessage({ type: 'error', text: activeFormat === 'xmi' ? 'Selecciona un archivo XMI/XML o pega su contenido antes de importar.' : 'Selecciona un archivo JSON o pega su contenido antes de importar.' })
      return
    }
    try {
      setLoading(true)
      setStatusMessage(null)
      const endpoint = `${apiBase}/api/projects/${projectId}/diagrams/${diagramId}/xmi/${activeFormat === 'xmi' ? 'import' : 'import-json'}`
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': activeFormat === 'xmi' ? 'application/xml' : 'application/json', Authorization: `Bearer ${token}`, 'X-Diagram-Revision': String(revision ?? 1) },
        body: importText
      })
      const responseText = await res.text()
      let responseBody: any = null
      if (responseText.trim()) {
        try { responseBody = JSON.parse(responseText) }
        catch {
          if (res.ok) throw new Error('El servidor devolvió una respuesta inválida después de importar. Recarga el diagrama para comprobar si los cambios se aplicaron.')
        }
      }
      if (!res.ok) {
        throw new Error(responseBody?.message || `No se pudo importar el diagrama (HTTP ${res.status}). Revisa el archivo y vuelve a intentarlo.`)
      }
      if (!responseBody || !Array.isArray(responseBody.elements) || !Array.isArray(responseBody.relations)) {
        throw new Error('El servidor aceptó la importación, pero no devolvió el diagrama actualizado. Recarga el diagrama para comprobar los cambios.')
      }
      const updated = responseBody as DiagramFullView
      setStatusMessage({ type: 'success', text: `Importación completada: ${updated.elements?.length ?? 0} elementos y ${updated.relations?.length ?? 0} relaciones.` })
      onDiagramUpdated(updated)
      window.setTimeout(onClose, 1500)
    } catch (err) {
      setStatusMessage({ type: 'error', text: err instanceof Error ? err.message : 'Error desconocido al importar.' })
      onError(err)
    } finally { setLoading(false) }
  }

  function readFile(file?: File) {
    if (!file) return
    const ext = file.name.split('.').pop()?.toLowerCase()
    const allowed = activeFormat === 'xmi' ? ['xmi', 'xml', 'uml'] : ['json']
    if (!ext || !allowed.includes(ext)) {
      setStatusMessage({ type: 'error', text: `Formato no válido. Selecciona ${activeFormat === 'xmi' ? '.xmi, .xml o .uml' : '.json'}.` })
      if (fileInput.current) fileInput.current.value = ''
      return
    }
    setSelectedFileName(file.name)
    setSelectedFileSize(file.size < 1024 * 1024 ? `${(file.size / 1024).toFixed(1)} KB` : `${(file.size / 1024 / 1024).toFixed(1)} MB`)
    setStatusMessage(null)
    const reader = new FileReader()
    reader.onload = event => setImportText(String(event.target?.result || ''))
    reader.onerror = () => setStatusMessage({ type: 'error', text: 'No se pudo leer el archivo. Intenta seleccionarlo de nuevo.' })
    reader.readAsText(file)
  }

  function onFileChange(e: ChangeEvent<HTMLInputElement>) { readFile(e.target.files?.[0]) }
  function onDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault()
    setIsDragging(false)
    if (!isLocked) readFile(e.dataTransfer.files[0])
  }

  const formatLabel = activeFormat === 'xmi' ? 'XMI / XML' : 'Backup JSON'

  return (
    <AiModalShell title="Importar y exportar modelos" subtitle="Intercambia modelos UML y copias completas del diagrama" icon={<ExchangeIcon size={20} color="#1d4ed8" />} onClose={onClose}>
      <div className="xmi-modal-content">
        {isLocked && <div className="xmi-notice xmi-notice-warning"><LockIcon size={16} /> El diagrama está bloqueado por <strong>{lockUser || 'otro usuario'}</strong> durante una operación activa.</div>}
        {statusMessage && <div className={`xmi-notice ${statusMessage.type === 'success' ? 'xmi-notice-success' : 'xmi-notice-error'}`} role={statusMessage.type === 'error' ? 'alert' : 'status'} aria-live="polite">{statusMessage.type === 'success' ? <CheckIcon size={16} /> : <AlertIcon size={16} />}{statusMessage.text}</div>}

        <div className="xmi-operation-tabs" role="tablist" aria-label="Operación de modelos">
          {!isReadOnly && <button type="button" role="tab" aria-selected={operation === 'import'} className={`xmi-operation-tab ${operation === 'import' ? 'is-active' : ''}`} onClick={() => { setOperation('import'); setStatusMessage(null) }}>Importar</button>}
          <button type="button" role="tab" aria-selected={operation === 'export'} className={`xmi-operation-tab ${operation === 'export' ? 'is-active' : ''}`} onClick={() => { setOperation('export'); setStatusMessage(null) }}>Exportar</button>
        </div>

        <div className="xmi-format-switcher" role="tablist" aria-label="Formato del archivo">
          <button type="button" role="tab" aria-selected={activeFormat === 'xmi'} className={`xmi-format-tab ${activeFormat === 'xmi' ? 'is-active' : ''}`} onClick={() => setFormat('xmi')}><span className="xmi-format-name">Enterprise Architect XMI / XML</span><span className="xmi-format-ext">.xmi · .xml</span></button>
          <button type="button" role="tab" aria-selected={activeFormat === 'json'} className={`xmi-format-tab ${activeFormat === 'json' ? 'is-active' : ''}`} onClick={() => setFormat('json')}><span className="xmi-format-name">Backup JSON completo</span><span className="xmi-format-ext">.json</span></button>
        </div>

        {operation === 'export' ? <section className="xmi-section">
          <div className="xmi-section-heading"><div><p className="xmi-eyebrow">Exportar diagrama</p><h3>Descarga en formato {formatLabel}</h3></div><span className="xmi-badge">{activeFormat === 'xmi' ? 'OMG XMI 2.1' : 'Copia completa'}</span></div>
          <p className="xmi-description">{activeFormat === 'xmi' ? 'Incluye clases, interfaces, enumeraciones, atributos, métodos, relaciones, multiplicidades y geometría del diagrama. Compatible con Sparx Enterprise Architect.' : 'Guarda el diagrama completo, incluidas sus coordenadas, estilos, descripciones y multiplicidades.'}</p>
          <div className="xmi-button-row">
            {activeFormat === 'xmi' ? <><button type="button" className="xmi-button xmi-button-primary" disabled={loading || isLocked} onClick={() => handleExportXmi('xmi')}>Descargar XMI (.xmi)</button><button type="button" className="xmi-button xmi-button-secondary" disabled={loading || isLocked} onClick={() => handleExportXmi('xml')}>Descargar XML (.xml)</button></> : <button type="button" className="xmi-button xmi-button-primary" disabled={loading || isLocked} onClick={handleExportJson}>{loading ? 'Preparando archivo…' : 'Descargar backup JSON (.json)'}</button>}
          </div>
          {activeFormat === 'xmi' && <div className="xmi-ea-import-hint"><strong>Importar en Enterprise Architect:</strong> Publish → Model Exchange → Import → XMI File y activa “Import Diagrams”. El archivo conserva la posición de los elementos.</div>}
        </section> : <section className="xmi-section">
          <div className="xmi-section-heading"><div><p className="xmi-eyebrow">Importar al diagrama</p><h3>{activeFormat === 'xmi' ? 'Archivo XMI o XML' : 'Backup JSON'}</h3></div><span className="xmi-badge xmi-badge-green">{activeFormat === 'xmi' ? 'EA 1.1 / 2.1 · StarUML · Visual Paradigm' : 'Restauración completa'}</span></div>
          <p className="xmi-description">{activeFormat === 'xmi' ? 'Compatible con XMI de Sparx Enterprise Architect, Native XML y tablas relacionales, además de StarUML y Visual Paradigm.' : 'Restaura el diagrama con sus clases, relaciones y posiciones originales.'}</p>
          <form onSubmit={handleImportSubmit}>
            <div className={`xmi-dropzone ${isDragging ? 'is-dragging' : ''} ${selectedFileName ? 'has-file' : ''}`} onDragEnter={e => { e.preventDefault(); setIsDragging(true) }} onDragOver={e => e.preventDefault()} onDragLeave={e => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setIsDragging(false) }} onDrop={onDrop}>
              <input ref={fileInput} className="xmi-hidden-input" type="file" accept={activeFormat === 'xmi' ? '.xmi,.xml,.uml' : '.json'} onChange={onFileChange} disabled={isLocked || loading} aria-label={`Seleccionar archivo ${formatLabel}`} />
              <span className="xmi-upload-icon"><UploadIcon size={20} /></span>
              <div className="xmi-drop-copy"><strong>{selectedFileName || 'Arrastra un archivo aquí'}</strong><span>{selectedFileName ? `${selectedFileSize} · Archivo listo para importar` : `o selecciona desde tu dispositivo · ${activeFormat === 'xmi' ? '.xmi, .xml, .uml' : '.json'}`}</span></div>
              <button type="button" className="xmi-button xmi-button-outline" onClick={() => fileInput.current?.click()} disabled={isLocked || loading}>{selectedFileName ? 'Cambiar archivo' : 'Seleccionar archivo'}</button>
              {selectedFileName && <button type="button" className="xmi-remove-file" aria-label="Quitar archivo seleccionado" onClick={() => { setSelectedFileName(null); setSelectedFileSize(null); setImportText(''); if (fileInput.current) fileInput.current.value = '' }}><TrashIcon size={16} /></button>}
            </div>
            <label className="xmi-editor-label" htmlFor="xmi-import-content">O pega el contenido del archivo</label>
            <textarea id="xmi-import-content" className="xmi-import-textarea" rows={7} value={importText} onChange={e => { setImportText(e.target.value); setStatusMessage(null) }} disabled={isLocked || loading} placeholder={activeFormat === 'xmi' ? 'Pega aquí el contenido XML / XMI…' : 'Pega aquí el contenido JSON del diagrama…'} />
            <div className="xmi-form-actions"><span className="xmi-helper">{importText.trim() ? 'Contenido listo para procesar' : 'El contenido se valida al importarlo'}</span><button type="submit" disabled={loading || isLocked || !importText.trim()} className="xmi-button xmi-button-primary">{loading ? 'Importando modelo…' : `Importar ${activeFormat === 'xmi' ? 'al diagrama' : 'backup al diagrama'}`}</button></div>
          </form>
        </section>}
        <div className="xmi-modal-footer"><span>Diagrama: <strong>{diagramName}</strong></span><button type="button" className="xmi-button xmi-button-quiet" onClick={onClose}>Cerrar</button></div>
      </div>
    </AiModalShell>
  )
}
