import { ReactNode, useRef, useState, useEffect } from 'react'
import { DiagramSummary, ElementDetail, parseUmlProperties, RelationDetail, TipoElementoUML, TipoRelacionUML } from './types'
import {
  MicIcon,
  CommandIcon,
  ImageIcon,
  ExchangeIcon,
  LockIcon,
  UsersIcon,
  ChevronLeftIcon,
  LayersIcon,
  CodeIcon,
  SparklesIcon,
  ChatIcon
} from './icons'
import { LiveTeamChatPanel } from './LiveTeamChatPanel'
import { AgentSpeechControls, AgentSpeechProvider } from './AgentSpeechUI'
import './team-collaboration.css'

const tools: { label: string; icon: string; type?: TipoElementoUML; relation?: TipoRelacionUML }[] = [
  { label: 'Selección', icon: '↖' },
  { label: 'Crear clase', icon: '▤', type: 'CLASE' },
  { label: 'Crear interfaz', icon: '◫', type: 'INTERFAZ' },
  { label: 'Crear clase abstracta', icon: '▱', type: 'CLASE_ABSTRACTA' },
  { label: 'Crear enumeración', icon: '☷', type: 'ENUMERACION' },
  { label: 'Asociación', icon: '—', relation: 'ASOCIACION' },
  { label: 'Herencia', icon: '△', relation: 'HERENCIA' },
  { label: 'Agregación', icon: '◇', relation: 'AGREGACION' },
  { label: 'Composición', icon: '◆', relation: 'COMPOSICION' },
  { label: 'Dependencia', icon: '⇢', relation: 'DEPENDENCIA' }
]

type Props = {
  projectId: string; token: string
  projectName: string; role: string; diagram: DiagramSummary | null; elements: ElementDetail[]; relations: RelationDetail[]
  selectedElement: ElementDetail | null; selectedRelation: RelationDetail | null; members: { id: string; userId: string; name: string; email: string; role: string }[]
  currentUser: { name: string; email: string; role: string }
  comparisonVersionIds: string[]
  savingStatus: string; online: boolean; syncing: boolean; wsConnected: boolean; queueCount: number; readOnly: boolean
  onBack?: () => void; onManageMembers?: () => void; onCreate: (type: TipoElementoUML) => void
  onImageUpload: (file: File) => void
  onEditElement: (id: string) => void; onEditRelation: (relation: RelationDetail) => void; onRelationType: (type: TipoRelacionUML) => void
  onValidate: () => void; onRelational: () => void; onAi: (initialMode?: 'command' | 'image' | 'chat') => void; onGenerator: () => Promise<void>; onFlutterGenerator: () => void
  onVersions: () => void; onActivity: () => void; onComments: () => void; commentMode: boolean
  selectedCommentId: string | null; onEditSelectedComment: () => void; onDeleteSelectedComment: () => void
  onExchange: () => void; onApiTester: () => void; onDeleteSelected: () => void
  onCreateIntermediateClass: (relation: RelationDetail) => void
  onArrangeDiagram: () => void; arrangingDiagram: boolean; arrangeDisabled: boolean
  onRetryPendingSync: () => void
  selectedCount: number; onSelectAll: () => void; onDeselectAll: () => void
  children: ReactNode
}

export function WorkspaceChrome(p: Props) {
  return <AgentSpeechProvider projectId={p.projectId} userKey={p.currentUser.email.toLowerCase()} sessionKey={p.token}><WorkspaceChromeContent {...p} /></AgentSpeechProvider>
}

function WorkspaceChromeContent(p: Props) {
  const [generationNotice, setGenerationNotice] = useState<string | null>(null)
  const [activeTool, setActiveTool] = useState('Selección')
  const [modelingMode, setModelingMode] = useState<'manual' | 'image' | 'voice'>('manual')
  const [relationType, setRelationType] = useState<TipoRelacionUML>('ASOCIACION')
  const [imageName, setImageName] = useState('')
  const [commentActionsOpen, setCommentActionsOpen] = useState(false)
  const imageInput = useRef<HTMLInputElement>(null)

  async function openGeneration(kind: 'backend' | 'flutter') {
    if (kind === 'flutter') {
      p.onFlutterGenerator()
      return
    }
    try {
      await p.onGenerator()
      setGenerationNotice('✅ Backend Spring Boot creado con éxito.')
      window.setTimeout(() => setGenerationNotice(null), 4500)
    } catch {
      setGenerationNotice('❌ No se pudo crear el Backend Spring Boot.')
      window.setTimeout(() => setGenerationNotice(null), 4500)
    }
  }

  // Estado para paneles redimensionables (Lienzo dinámico)
  const [sidebarWidth, setSidebarWidth] = useState(210)
  const [inspectorWidth, setInspectorWidth] = useState(300)
  const [isResizingLeft, setIsResizingLeft] = useState(false)
  const [isResizingRight, setIsResizingRight] = useState(false)

  // Modo del panel lateral derecho (Inspector vs. Espacio de Equipo)
  const [rightPanelMode, setRightPanelMode] = useState<'inspector' | 'team'>('inspector')
  const [isRightPanelCollapsed, setIsRightPanelCollapsed] = useState(false)
  const [isWideTeam, setIsWideTeam] = useState(false)

  const typeLabel = (type: TipoElementoUML) => ({ CLASE: 'Clase', INTERFAZ: 'Interfaz', CLASE_ABSTRACTA: 'Clase abstracta', ENUMERACION: 'Enumeración' })[type]

  const isLocked = p.diagram?.bloqueado ?? false
  const lockUser = p.diagram?.bloqueadoPor ?? ''
  const isEditingDisabled = p.readOnly || isLocked

  // Handlers para arrastrar y ajustar el tamaño de los paneles laterales
  useEffect(() => {
    function handleMouseMove(e: MouseEvent) {
      if (isResizingLeft) {
        const newWidth = Math.max(140, Math.min(400, e.clientX))
        setSidebarWidth(newWidth)
      } else if (isResizingRight) {
        const newWidth = Math.max(180, Math.min(450, window.innerWidth - e.clientX))
        setInspectorWidth(newWidth)
      }
    }
    function handleMouseUp() {
      setIsResizingLeft(false)
      setIsResizingRight(false)
    }
    if (isResizingLeft || isResizingRight) {
      window.addEventListener('mousemove', handleMouseMove)
      window.addEventListener('mouseup', handleMouseUp)
    }
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [isResizingLeft, isResizingRight])

  return <div className={`workspace-shell ${isResizingLeft || isResizingRight ? 'is-resizing' : ''}`}>
    {generationNotice && (
      <div role="status" aria-live="polite" style={{ position: 'fixed', top: '1rem', left: '50%', transform: 'translateX(-50%)', zIndex: 100, padding: '0.8rem 1.1rem', borderRadius: '8px', background: '#eff6ff', border: '1px solid #93c5fd', color: '#1e3a8a', boxShadow: '0 8px 24px rgba(15, 23, 42, 0.18)', fontWeight: 600 }}>
        ℹ️ {generationNotice}
      </div>
    )}
    <header className="workspace-topbar">
      <div className="workspace-brand">
        <button className="chrome-back" onClick={p.onBack} title="Volver a proyectos">
          <ChevronLeftIcon size={18} />
        </button>
        <div>
          <span className="eyebrow">PROYECTO</span>
          <strong>{p.projectName}</strong>
        </div>
        <span className="role">{p.role}</span>
      </div>
      <div className="topbar-diagram">
        <span className="eyebrow">DIAGRAMA</span>
        <strong>{p.diagram?.nombre ?? 'Cargando diagrama…'}</strong>
      </div>
      <div className="topbar-status">
        <span className={`chrome-status ${p.savingStatus.includes('Offline') || p.savingStatus.includes('Error') ? 'warn' : ''}`}>● {p.savingStatus}</span>
        <span className={`chrome-status ${p.online ? 'good' : 'warn'}`}>● {p.online ? 'Conectado' : 'Sin conexión'}</span>
        <span className={`chrome-status ${isLocked ? 'warn' : 'muted'}`}>
          {isLocked ? <><LockIcon size={12} style={{ display: 'inline', marginRight: 4 }} /> Bloqueado ({lockUser})</> : p.readOnly ? 'Solo lectura' : '● Libre'}
        </span>
      </div>
      <div className="topbar-actions">
        <div className="avatar-stack" title="Colaboradores del proyecto">
          {p.members.slice(0, 4).map(m => <span key={m.id} title={`${m.name} · ${m.role}`}>{m.name.slice(0, 1).toUpperCase()}</span>)}
          {p.members.length > 4 && <span>+{p.members.length - 4}</span>}
        </div>
        <button className="chrome-button" onClick={p.onManageMembers}>
          <UsersIcon size={14} style={{ marginRight: 5, verticalAlign: 'middle' }} />
          Colaboradores {p.members.length}
        </button>
        <button
          className={`chrome-button ${rightPanelMode === 'team' && !isRightPanelCollapsed ? 'team-active-btn' : ''}`}
          onClick={() => {
            if (rightPanelMode === 'team' && !isRightPanelCollapsed) {
              setIsRightPanelCollapsed(true)
            } else {
              setRightPanelMode('team')
              setIsRightPanelCollapsed(false)
            }
          }}
          title={rightPanelMode === 'team' && !isRightPanelCollapsed ? 'Colapsar espacio de equipo' : 'Abrir espacio colaborativo del equipo (Canales, Chat y ACODI)'}
        >
          <ChatIcon size={14} style={{ marginRight: 5, verticalAlign: 'middle' }} />
          Espacio de Equipo
        </button>
        <details className="project-actions">
          <summary title="Acciones del proyecto">•••</summary>
          <div className="project-menu">
            <button onClick={p.onBack}>Volver a proyectos</button>
            <button onClick={p.onManageMembers}>Administrar colaboradores</button>
          </div>
        </details>
      </div>
    </header>

    <section className="modeling-modes" aria-label="Modos de modelado">
      <div className="mode-heading">
        <span className="eyebrow">MODOS DE MODELADO</span>
        <span>Selecciona la modalidad de trabajo</span>
      </div>
      <button className="mode-card active" onClick={() => setActiveTool('Selección')}>
        <span className="mode-icon"><CommandIcon size={16} /></span>
        <span>
          <strong>Modelado manual</strong>
          <small>Diseña clases y relaciones en el lienzo</small>
        </span>
        <span className="mode-arrow">›</span>
      </button>
      <button className="mode-card" onClick={() => imageInput.current?.click()}>
        <span className="mode-icon"><ImageIcon size={16} /></span>
        <span>
          <strong>Imagen → modelo</strong>
          <small>{imageName || 'Cargar diagrama UML desde imagen'}</small>
        </span>
        <span className="mode-arrow">↑</span>
      </button>
      <input
        ref={imageInput}
        className="visually-hidden"
        type="file"
        accept="image/png,image/jpeg,image/webp,image/heic,image/heif"
        onChange={e => { const file = e.target.files?.[0]; if (file) { setImageName(file.name); p.onImageUpload(file) } e.currentTarget.value = '' }}
      />
      <div className="mode-card voice-mode" onClick={() => p.onAi('command')} style={{ cursor: 'pointer' }}>
        <span className="mode-icon"><MicIcon size={16} /></span>
        <span>
          <strong>Modelado por voz</strong>
          <small>Dictado de clases, atributos y relaciones</small>
        </span>
        <button className="mic-button" title="Abrir micrófono para modelado por voz" aria-label="Micrófono disponible" onClick={(e) => { e.stopPropagation(); p.onAi('command') }}>
          <MicIcon size={16} color="#2563eb" />
        </button>
        <span className="voice-state">● Activo</span>
      </div>
    </section>

    {isLocked && (
      <div style={{ background: '#fef3c7', border: '1px solid #f59e0b', color: '#92400e', padding: '0.6rem 1.2rem', fontSize: '0.88rem', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
        <LockIcon size={16} />
        <span>Bloqueo Colaborativo Activo:</span>
        <span>El usuario <strong>{lockUser}</strong> está realizando una operación de importación/exportación con Enterprise Architect. Las modificaciones están bloqueadas temporalmente.</span>
      </div>
    )}

    {/* Ancho efectivo del panel lateral derecho considerando modo de equipo y colapso */}
    {(() => {
      const effectiveRightWidth = isRightPanelCollapsed
        ? 0
        : rightPanelMode === 'team'
        ? (isWideTeam ? 480 : Math.max(340, inspectorWidth))
        : inspectorWidth

      return (
        <div
          className="workspace-grid"
          style={{
            gridTemplateColumns: isRightPanelCollapsed
              ? `${sidebarWidth}px 5px minmax(300px, 1fr)`
              : `${sidebarWidth}px 5px minmax(300px, 1fr) 5px ${effectiveRightWidth}px`
          }}
        >
          <aside className="tool-sidebar">
            <div className="panel-heading">
              <span className="eyebrow">MODELADO MANUAL</span>
              <strong>Herramientas</strong>
            </div>
            <details className="tool-module" open>
              <summary>Colaboración</summary>
              <div className="tool-module-content">
                <button type="button" className={`tool-item ${p.commentMode ? 'selected' : ''}`} title="Activa el modo para crear una nota en cualquier punto del lienzo" onClick={p.onComments}>{p.commentMode ? 'Cancelar creación' : 'Crear comentario'}</button>
                {p.selectedCommentId && <button type="button" className={`tool-item ${commentActionsOpen ? 'selected' : ''}`} title="Opciones para el comentario seleccionado" onClick={() => setCommentActionsOpen(open => !open)}>Opciones del comentario</button>}
                {commentActionsOpen && p.selectedCommentId && <div className="comment-tool-actions" role="group" aria-label="Acciones del comentario seleccionado">
                  <button type="button" onClick={() => { p.onEditSelectedComment(); setCommentActionsOpen(false) }}>Editar comentario</button>
                  <button type="button" onClick={() => { p.onDeleteSelectedComment(); setCommentActionsOpen(false) }}>Eliminar comentario</button>
                </div>}
              </div>
            </details>
            <details className="tool-module generation-module" open>
              <summary>Generación de software</summary>
              <div className="tool-module-content">
                <button
                  type="button"
                  className="tool-item generation-tool-item"
                  title="Configurar y generar un backend Spring Boot desde el diagrama UML"
                  onClick={() => void openGeneration('backend')}
                >
                  <CodeIcon size={15} />
                  <span>Generar Backend Spring Boot</span>
                </button>
                <button
                  type="button"
                  className="tool-item generation-tool-item"
                  title="Generar el frontend Flutter contextualizado y registro por voz"
                  onClick={() => openGeneration('flutter')}
                >
                  <span>Generar Backend + Flutter</span>
                </button>
                <small className="tool-help-text">Configura el proyecto, previsualiza los archivos y descarga el ZIP.</small>
              </div>
            </details>
            <details className="tool-module">
              <summary>Modos de modelado</summary>
              <div className="tool-module-content mode-module-content">
                <button className={`mode-card mode-card-sidebar ${modelingMode === 'manual' ? 'active' : ''}`} onClick={() => { setModelingMode('manual'); setActiveTool('Selección') }}>
                  <span className="mode-icon"><CommandIcon size={15} /></span>
                  <span><strong>Modelado manual</strong><small>Clases y relaciones en el lienzo</small></span>
                </button>
                <button className={`mode-card mode-card-sidebar ${modelingMode === 'image' ? 'active' : ''}`} onClick={() => { setModelingMode('image'); imageInput.current?.click() }}>
                  <span className="mode-icon"><ImageIcon size={15} /></span>
                  <span><strong>Imagen → modelo</strong><small>{imageName || 'Cargar diagrama UML'}</small></span>
                </button>
                <button className={`mode-card mode-card-sidebar ${modelingMode === 'voice' ? 'active' : ''}`} onClick={() => { setModelingMode('voice'); p.onAi('command') }}>
                  <span className="mode-icon"><MicIcon size={15} /></span>
                  <span><strong>Modelado por voz</strong><small>Dictado de clases y relaciones</small></span>
                </button>
              </div>
            </details>
            <details className="tool-module">
              <summary>Elementos</summary>
              <div className="tool-module-content">
                <button className={`tool-item ${activeTool === 'Selección' ? 'selected' : ''}`} title="Seleccionar y mover elementos" onClick={() => { setModelingMode('manual'); setActiveTool('Selección') }}>
                  <span>↖</span>Selección
                </button>
                {tools.slice(1, 5).map(t => (
                  <button key={t.label} className={`tool-item ${activeTool === t.label ? 'selected' : ''}`} disabled={isEditingDisabled} title={`${t.label} · crear en el lienzo`} onClick={() => { setModelingMode('manual'); setActiveTool(t.label); if (t.type) p.onCreate(t.type) }}>
                    <span>{t.icon}</span>{t.label}
                  </button>
                ))}
              </div>
            </details>
            <details className="tool-module">
              <summary>Relaciones <small>arrastra entre nodos</small></summary>
              <div className="tool-module-content">
                <button className={`tool-item relation-create ${activeTool === 'Crear relación' ? 'selected' : ''}`} disabled={isEditingDisabled} title="Conecta dos elementos desde sus puntos de conexión" onClick={() => { setModelingMode('manual'); setActiveTool('Crear relación') }}>
                  <span>⌁</span>Crear relación
                </button>
                {tools.slice(5).map(t => (
                  <button key={t.label} className={`tool-item relation-sub ${relationType === t.relation ? 'selected' : ''}`} disabled={isEditingDisabled} title={`Usar tipo ${t.label}; arrastra desde un nodo a otro`} onClick={() => { setModelingMode('manual'); setRelationType(t.relation!); p.onRelationType(t.relation!); setActiveTool('Crear relación') }}>
                    <span>{t.icon}</span>{t.label}
                  </button>
                ))}
              </div>
            </details>
            <p className="sidebar-hint">{activeTool === 'Crear relación' ? 'Arrastra desde un punto de conexión hasta otro elemento.' : 'Selecciona una herramienta para continuar.'}</p>
          </aside>

          {/* Handles de arrastre para redimensionar paneles */}
          <div className={`panel-resizer ${isResizingLeft ? 'dragging' : ''}`} onMouseDown={() => setIsResizingLeft(true)} title="Arrastrar para ajustar ancho del panel de herramientas" />

          <main className="canvas-column">
            <div className="canvas-heading">
              <div>
                <span className="eyebrow">LIENZO UML</span>
                <span>{p.elements.length} elementos · {p.relations.length} relaciones</span>
              </div>
              <div className="canvas-heading-actions">
                {p.selectedCount > 0 ? (
                  <>
                    <span className="selection-chip">{p.selectedCount} seleccionados</span>
                    {!isEditingDisabled && <button className="quiet-button danger-text" onClick={p.onDeleteSelected}>Eliminar</button>}
                    <button className="quiet-button" onClick={p.onDeselectAll}>Limpiar selección</button>
                  </>
                ) : (
                  <button className="quiet-button" onClick={p.onSelectAll}>Seleccionar todo</button>
                )}
                <button
                  type="button"
                  className="quiet-button organize-diagram-button"
                  disabled={isEditingDisabled || p.arrangingDiagram || p.arrangeDisabled || p.elements.length < 2}
                  onClick={p.onArrangeDiagram}
                  title="Organizar diagrama"
                  aria-label="Organizar diagrama"
                >
                  <svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="9" y="9" width="6" height="6" rx="1" />
                    <rect x="2" y="3" width="5" height="5" rx="1" />
                    <rect x="17" y="3" width="5" height="5" rx="1" />
                    <rect x="2" y="16" width="5" height="5" rx="1" />
                    <rect x="17" y="16" width="5" height="5" rx="1" />
                    <path d="M7 6h2m6 0h2M4.5 8v5m15-5v5M7 18.5h2m6 0h2" />
                  </svg>
                  <span>{p.arrangingDiagram ? 'Organizando…' : 'Organizar'}</span>
                </button>
                {isRightPanelCollapsed && (
                  <button
                    className="quiet-button open-panel-btn"
                    onClick={() => setIsRightPanelCollapsed(false)}
                    title="Abrir panel lateral derecho"
                  >
                    ◀ Abrir {rightPanelMode === 'team' ? 'Espacio de Equipo' : 'Inspector'}
                  </button>
                )}
                <details className="more-tools">
                  <summary>Más herramientas</summary>
                  <div className="project-menu">
                    <button onClick={p.onValidate}>Validar modelo</button>
                    <button onClick={p.onRelational}>Relacional y SQL</button>
                    <button onClick={() => p.onAi('command')}>Asistente de modelado</button>
                    <button onClick={() => void openGeneration('backend')}>Generar software</button>
                    <button onClick={p.onVersions}>Versiones</button>
                    <button onClick={p.onActivity}>Bitácora de actividad</button>
                    <button onClick={p.onComments}>Comentarios UML</button>
                    <button onClick={p.onExchange}>Enterprise Architect / XMI</button>
                    <button onClick={p.onApiTester}>Probar API</button>
                  </div>
                </details>
              </div>
            </div>
            <div className="canvas-frame" data-active-tool={activeTool} data-relation-type={relationType}>
              <div className="canvas-hint">
                {activeTool === 'Crear relación' ? `RELACIÓN · ${relationType.toLowerCase()} · conecta dos elementos` : 'SELECCIÓN · arrastra para mover · rueda para zoom'}
              </div>
              {p.commentMode && <div className="canvas-hint" style={{ background: '#fef3c7', color: '#92400e' }}>MODO COMENTARIO · haz clic en cualquier punto del lienzo para escribir una observación</div>}
              <div className="canvas-mount">{p.children}</div>
            </div>
          </main>

          <>
              <div
                className={`panel-resizer right-panel-resizer ${isResizingRight ? 'dragging' : ''}`}
                style={{ display: isRightPanelCollapsed ? 'none' : undefined }}
                onMouseDown={() => setIsResizingRight(true)}
                title="Arrastrar para ajustar ancho del panel derecho"
              />

              <div className="right-panel-container" style={{ width: effectiveRightWidth, display: isRightPanelCollapsed ? 'none' : undefined }}>
                <div className="right-panel-tabbar">
                  <button
                    type="button"
                    className={`right-tab-btn ${rightPanelMode === 'inspector' ? 'active' : ''}`}
                    onClick={() => setRightPanelMode('inspector')}
                    title="Ver propiedades del elemento o diagrama"
                  >
                    Inspector
                  </button>
                  <button
                    type="button"
                    className={`right-tab-btn ${rightPanelMode === 'team' ? 'active' : ''}`}
                    onClick={() => setRightPanelMode('team')}
                    title="Abrir espacio colaborativo con canales y ACODI"
                  >
                    <ChatIcon size={12} style={{ marginRight: 4, verticalAlign: 'middle' }} />
                    Espacio de Equipo
                  </button>
                  <button
                    type="button"
                    className="right-tab-collapse"
                    onClick={() => setIsRightPanelCollapsed(true)}
                    title="Colapsar panel derecho (maximizar área de modelado)"
                  >
                    ⇥
                  </button>
                </div>

                <div className="team-panel-host" hidden={rightPanelMode !== 'team'}>
                  <LiveTeamChatPanel
                    key={p.projectId + ':' + p.token}
                    projectId={p.projectId}
                    diagramId={p.diagram?.id ?? null}
                    projectName={p.projectName}
                    diagramName={p.diagram?.nombre ?? 'Diagrama UML'}
                    token={p.token}
                    members={p.members}
                    currentUser={p.currentUser}
                    comparisonVersionIds={p.comparisonVersionIds}
                    isWide={isWideTeam}
                    visible={rightPanelMode === 'team' && !isRightPanelCollapsed}
                    onToggleWide={() => setIsWideTeam(!isWideTeam)}
                    onClose={() => setIsRightPanelCollapsed(true)}
                    onSwitchToInspector={() => setRightPanelMode('inspector')}
                  />
                </div>
                {rightPanelMode === 'inspector' && (
                  <aside className="properties-panel">
                    <div className="panel-heading">
                      <span className="eyebrow">INSPECTOR</span>
                      <strong>{p.selectedElement || p.selectedRelation ? 'Propiedades' : 'Diagrama'}</strong>
                    </div>
                    {p.selectedElement ? (
                      <>
                        <div className="property-type">{typeLabel(p.selectedElement.tipoElemento)}</div>
                        <h3 className="property-name">{p.selectedElement.nombre}</h3>
                        <PropertyRows title="ATRIBUTOS" rows={parseUmlProperties(p.selectedElement.propiedadesJson).attributes.map(a => `${a.visibility} ${a.name}: ${a.type}`)} empty="Sin atributos"/>
                        <PropertyRows title="MÉTODOS" rows={parseUmlProperties(p.selectedElement.propiedadesJson).methods.map(m => `${m.visibility} ${m.name}(${m.parameters}): ${m.returnType}`)} empty="Sin métodos"/>
                        <button className="chrome-button full-button" disabled={isEditingDisabled} onClick={() => p.onEditElement(p.selectedElement!.id)}>Editar elemento</button>
                      </>
                    ) : p.selectedRelation ? (
                      <>
                        <div className="property-type">{p.selectedRelation.tipoRelacion}</div>
                        <h3 className="property-name">{p.selectedRelation.nombreRelacion || 'Relación UML'}</h3>
                        <PropertyRows title="ORIGEN" rows={[p.elements.find(e => e.id === p.selectedRelation!.elementoOrigen)?.nombre ?? 'Elemento']} empty="—"/>
                        <PropertyRows title="DESTINO" rows={[p.elements.find(e => e.id === p.selectedRelation!.elementoDestino)?.nombre ?? 'Elemento']} empty="—"/>
                        <PropertyRows title="MULTIPLICIDAD · ORIGEN" rows={[p.selectedRelation.multiplicidadOrigen || 'No definida']} empty="—"/>
                        <PropertyRows title="MULTIPLICIDAD · DESTINO" rows={[p.selectedRelation.multiplicidadDestino || 'No definida']} empty="—"/>
                        <button className="chrome-button full-button" disabled={isEditingDisabled} onClick={() => p.onEditRelation(p.selectedRelation!)}>Editar relación</button>
                        {isMany(p.selectedRelation.multiplicidadOrigen) && isMany(p.selectedRelation.multiplicidadDestino) && (
                          <button className="chrome-button full-button" disabled={isEditingDisabled} onClick={() => p.onCreateIntermediateClass(p.selectedRelation!)}>
                            Crear clase intermedia
                          </button>
                        )}
                      </>
                    ) : (
                      <>
                        <p className="inspector-copy">Resumen y estado del diagrama activo.</p>
                        <PropertyRows title="ESTADO" rows={[p.diagram?.estado ?? 'Cargando…']} empty="—"/>
                        <PropertyRows title="BLOQUEO" rows={[isLocked ? `Bloqueado por ${lockUser}` : '● Disponible']} empty="—"/>
                        <PropertyRows title="ELEMENTOS" rows={[String(p.elements.length)]} empty="0"/>
                        <PropertyRows title="RELACIONES" rows={[String(p.relations.length)]} empty="0"/>
                        <PropertyRows title="ÚLTIMO GUARDADO" rows={[p.savingStatus]} empty="—"/>
                        <div className="selection-note">Selecciona una clase o relación para ver sus propiedades.</div>
                      </>
                    )}
                    <section className="ea-card">
                      <div className="ea-title">
                        <span><ExchangeIcon size={14} /></span>
                        <div>
                          <strong>Enterprise Architect</strong>
                          <small>Importación / Exportación XMI 2.1</small>
                        </div>
                      </div>
                      <button onClick={p.onExchange} disabled={isLocked} title="Importar o exportar modelos XMI compatibles con Sparx Enterprise Architect">
                        <ExchangeIcon size={13} style={{ marginRight: 4, verticalAlign: 'middle' }} />
                        Importar / Exportar EA (XMI)
                      </button>
                      <div className="ea-state">{isLocked ? '🔒 Operación en curso...' : '● Listo para interoperabilidad'}</div>
                    </section>
                  </aside>
                )}
              </div>
          </>
        </div>
      )
    })()}

    <footer className="workspace-footer">
      <AgentSpeechControls />
      <span className={p.online ? 'footer-good' : 'footer-warn'}>● {p.online ? 'Conexión disponible' : 'Sin conexión'}</span>
      <span>{p.wsConnected ? 'Colaboración en vivo' : 'Presencia en tiempo real'}</span>
      <span>{p.members.length} colaboradores</span>
      <span>Diagrama: {p.diagram?.estado ?? 'Cargando'}</span>
      <span className="footer-last">{p.queueCount ? `${p.queueCount} cambios locales pendientes` : p.savingStatus}</span>
      {p.online && !p.syncing && p.queueCount > 0 && p.savingStatus.includes('Conflicto') && (
        <button type="button" className="footer-retry-sync" onClick={p.onRetryPendingSync} title="Revisar los cambios locales y confirmar su reintento con la revisión actual del servidor">
          Revisar y reintentar
        </button>
      )}
    </footer>
  </div>
}

function isMany(value: string | null) {
  return value != null && /^(?:\*|n|m|(?:0|1)\.\.(?:\*|n|m))$/i.test(value.trim())
}

function PropertyRows({ title, rows, empty }: { title: string; rows: string[]; empty: string }) {
  return <div className="property-section"><span className="eyebrow">{title}</span>{rows.length ? rows.map((row, i) => <div className="property-row" key={`${row}-${i}`}>{row}</div>) : <div className="property-empty">{empty}</div>}</div>
}
