import { useState } from 'react'

type GeneratedFile = {
  path: string
  language: string
  content: string
}

type GenerationPreview = {
  projectName: string
  basePackage: string
  totalBackendFiles: number
  totalFrontendFiles: number
  totalFlutterFiles: number
  files: GeneratedFile[]
}

type Props = {
  projectName: string
  diagramId: string
  projectId: string
  token: string
  initialFlutter?: boolean
  simplifiedBackend?: boolean
  onClose: () => void
  onError: (err: unknown) => void
}

export function CodeGeneratorModal({
  projectName,
  diagramId,
  projectId,
  token,
  initialFlutter = false,
  simplifiedBackend = false,
  onClose,
  onError
}: Props) {
  const [packageName, setPackageName] = useState('bo.edu.generated')
  const [targetProjectName, setTargetProjectName] = useState(
    projectName.toLowerCase().replace(/[^a-z0-9]/g, '-') || 'app-generada'
  )
  const [serverPort, setServerPort] = useState(8081)
  const [databaseName, setDatabaseName] = useState('app_db')
  // El flujo Flutter genera backend + Flutter; React solo se incluye si se activa manualmente.
  const [includeFrontend, setIncludeFrontend] = useState(false)
  const [includeFlutter, setIncludeFlutter] = useState(initialFlutter)

  const [loading, setLoading] = useState(false)
  const [preview, setPreview] = useState<GenerationPreview | null>(null)
  const [selectedFile, setSelectedFile] = useState<GeneratedFile | null>(null)
  const [copied, setCopied] = useState(false)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  const configPayload = {
    packageName,
    projectName: targetProjectName,
    serverPort,
    databaseName,
    includeFrontend,
    includeFlutter
  }

  // Previsualizar código generado
  async function handlePreview() {
    try {
      setLoading(true)
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/generate/preview`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify(configPayload)
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.message || 'Error al generar previsualización.')
      }
      const data: GenerationPreview = await res.json()
      setPreview(data)
      if (data.files.length > 0) {
        setSelectedFile(data.files[0])
      }
      setSuccessMessage(
        includeFlutter
          ? '¡Backend Spring Boot y frontend Flutter generados correctamente!'
          : includeFrontend
            ? '¡Backend Spring Boot y frontend React generados correctamente!'
            : '¡Backend Spring Boot generado correctamente!'
      )
    } catch (err) {
      onError(err)
    } finally {
      setLoading(false)
    }
  }

  // Descargar ZIP completo
  async function handleDownloadZip() {
    try {
      setLoading(true)
      const res = await fetch(`/api/projects/${projectId}/diagrams/${diagramId}/generate/zip`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`
        },
        body: JSON.stringify(configPayload)
      })
      if (!res.ok) {
        throw new Error('Error al descargar el archivo ZIP del proyecto.')
      }
      const blob = await res.blob()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${targetProjectName}.zip`
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

  function handleCopy() {
    if (!selectedFile) return
    navigator.clipboard.writeText(selectedFile.content)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="uml-modal-backdrop">
      <div className="uml-modal generator-modal" style={{ maxWidth: preview ? '1050px' : '650px' }}>
        <div className="uml-modal-header">
          <h3>Generación Automática de Software (Spring Boot + React)</h3>
          <button type="button" className="close-btn" onClick={onClose}>
            ✕
          </button>
        </div>

        <div className="generator-body">
          {successMessage && (
            <div
              role="status"
              style={{
                marginBottom: '1rem',
                padding: '0.85rem 1rem',
                borderRadius: '8px',
                border: '1px solid #86efac',
                background: '#f0fdf4',
                color: '#166534',
                fontWeight: 600
              }}
            >
              ✅ {successMessage}
            </div>
          )}
          {/* Formulario de opciones */}
          <div className="generator-config-card">
            <h4>Configuración del Proyecto</h4>
            <div className="config-grid" style={{ display: simplifiedBackend ? 'none' : undefined }}>
              <label>
                Nombre del Proyecto:
                <input
                  type="text"
                  value={targetProjectName}
                  onChange={(e) => setTargetProjectName(e.target.value)}
                  placeholder="ej. sistema-ventas"
                />
              </label>

              <label>
                Paquete Base Java:
                <input
                  type="text"
                  value={packageName}
                  onChange={(e) => setPackageName(e.target.value)}
                  placeholder="ej. com.empresa.proyecto"
                />
              </label>

              <label>
                Puerto Servidor:
                <input
                  type="number"
                  value={serverPort}
                  onChange={(e) => setServerPort(Number(e.target.value))}
                />
              </label>

              <label>
                Base de Datos PostgreSQL:
                <input
                  type="text"
                  value={databaseName}
                  onChange={(e) => setDatabaseName(e.target.value)}
                  placeholder="ej. app_db"
                />
              </label>
            </div>

            <div className="checkbox-row" style={{ marginTop: '0.8rem', display: simplifiedBackend ? 'none' : undefined }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={includeFrontend}
                  onChange={(e) => setIncludeFrontend(e.target.checked)}
                />
                <strong>Generar Frontend React + TypeScript adaptado al backend</strong>
              </label>
            </div>

            <div className="checkbox-row" style={{ marginTop: '0.8rem', display: simplifiedBackend ? 'none' : undefined }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={includeFlutter}
                  onChange={(e) => setIncludeFlutter(e.target.checked)}
                />
                <strong>Generar Frontend Flutter contextualizado, voz y script para APK</strong>
              </label>
            </div>

            <div className="generator-actions" style={{ marginTop: '1.2rem', display: 'flex', gap: '0.75rem' }}>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={loading}
                onClick={handlePreview}
              >
                {loading ? 'Generando...' : '🔍 Previsualizar Código'}
              </button>

              <button
                type="button"
                className="btn btn-primary"
                disabled={loading}
                onClick={handleDownloadZip}
                style={{ background: '#16a34a' }}
              >
                📥 Descargar Proyecto Completo (.ZIP)
              </button>
            </div>
          </div>

          {/* Área de previsualización */}
          {preview && (
            <div className="generator-preview-card" style={{ marginTop: '1.5rem' }}>
              <div className="preview-header">
                <div>
                  <h4>Archivos Generados</h4>
                  <span className="badge" style={{ marginRight: '0.5rem' }}>
                    ☕ Backend: {preview.totalBackendFiles} archivos
                  </span>
                  {preview.totalFrontendFiles > 0 && (
                    <span className="badge" style={{ background: '#0284c7' }}>
                      ⚛️ Frontend: {preview.totalFrontendFiles} archivos
                    </span>
                  )}
                  {preview.totalFlutterFiles > 0 && (
                    <span className="badge" style={{ background: '#0f766e', marginLeft: '0.5rem' }}>
                      Flutter: {preview.totalFlutterFiles} archivos
                    </span>
                  )}
                </div>
                {selectedFile && (
                  <button type="button" className="btn btn-secondary btn-sm" onClick={handleCopy}>
                    {copied ? '✓ Copiado' : 'Copiar Archivo'}
                  </button>
                )}
              </div>

              <div className="preview-workspace" style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: '1rem', marginTop: '1rem' }}>
                {/* Lista de archivos */}
                <div className="file-tree" style={{ maxHeight: '380px', overflowY: 'auto', border: '1px solid #e2e8f0', borderRadius: '6px', padding: '0.5rem' }}>
                  {preview.files.map((file) => (
                    <div
                      key={file.path}
                      onClick={() => setSelectedFile(file)}
                      style={{
                        padding: '0.4rem 0.6rem',
                        fontSize: '0.8rem',
                        cursor: 'pointer',
                        borderRadius: '4px',
                        marginBottom: '0.2rem',
                        background: selectedFile?.path === file.path ? '#e0e7ff' : 'transparent',
                        color: selectedFile?.path === file.path ? '#3730a3' : '#334155',
                        fontWeight: selectedFile?.path === file.path ? '600' : 'normal',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis'
                      }}
                      title={file.path}
                    >
                      {file.path.startsWith('src/main/java') ? '☕ ' : file.path.endsWith('.tsx') || file.path.endsWith('.ts') ? '⚛️ ' : '📄 '}
                      {file.path}
                    </div>
                  ))}
                </div>

                {/* Visor de código */}
                <div className="file-content" style={{ maxHeight: '380px', overflowY: 'auto', background: '#0f172a', color: '#f8fafc', padding: '1rem', borderRadius: '6px', fontFamily: 'monospace', fontSize: '0.82rem' }}>
                  <div style={{ paddingBottom: '0.5rem', borderBottom: '1px solid #334155', marginBottom: '0.75rem', color: '#94a3b8' }}>
                    {selectedFile?.path}
                  </div>
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{selectedFile?.content}</pre>
                </div>
              </div>
            </div>
          )}
        </div>

        <div className="uml-modal-actions" style={{ marginTop: '1.5rem', display: 'flex', justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cerrar
          </button>
        </div>
      </div>
    </div>
  )
}
