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
  savingStatus: string; online: boolean; syncing: boolean; wsConnected: boolean; queueCount: number; readOnly: boolean
  onBack?: () => void; onManageMembers?: () => void; onCreate: (type: TipoElementoUML) => void
  onImageUpload: (file: File) => void
  onEditElement: (id: string) => void; onEditRelation: (relation: RelationDetail) => void; onRelationType: (type: TipoRelacionUML) => void
  onValidate: () => void; onRelational: () => void; onAi: (initialMode?: 'command' | 'image' | 'chat') => void; onGenerator: () => void
  onVersions: () => void; onExchange: () => void; onApiTester: () => void; onDeleteSelected: () => void
  selectedCount: number; onSelectAll: () => void; onDeselectAll: () => void
  children: ReactNode
}

export function WorkspaceChrome(p: Props) {
  return <AgentSpeechProvider projectId={p.projectId} userKey={p.currentUser.email.toLowerCase()} sessionKey={p.token}><WorkspaceChromeContent {...p} /></AgentSpeechProvider>
}

function WorkspaceChromeContent(p: Props) {
  const [activeTool, setActiveTool] = useState('Selección')
  const [relationType, setRelationType] = useState<TipoRelacionUML>('ASOCIACION')
  const [imageName, setImageName] = useState('')
  const imageInput = useRef<HTMLInputElement>(null)

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
            <button className={`tool-item ${activeTool === 'Selección' ? 'selected' : ''}`} title="Seleccionar y mover elementos" onClick={() => setActiveTool('Selección')}>
              <span>↖</span>Selección
            </button>
            <div className="tool-group-label">ELEMENTOS</div>
            {tools.slice(1, 5).map(t => (
              <button key={t.label} className={`tool-item ${activeTool === t.label ? 'selected' : ''}`} disabled={isEditingDisabled} title={`${t.label} · crear en el lienzo`} onClick={() => { setActiveTool(t.label); if (t.type) p.onCreate(t.type) }}>
                <span>{t.icon}</span>{t.label}
              </button>
            ))}
            <div className="tool-group-label">RELACIONES <small>arrastra entre nodos</small></div>
            <button className={`tool-item relation-create ${activeTool === 'Crear relación' ? 'selected' : ''}`} disabled={isEditingDisabled} title="Conecta dos elementos desde sus puntos de conexión" onClick={() => setActiveTool('Crear relación')}>
              <span>⌁</span>Crear relación
            </button>
            {tools.slice(5).map(t => (
              <button key={t.label} className={`tool-item relation-sub ${relationType === t.relation ? 'selected' : ''}`} disabled={isEditingDisabled} title={`Usar tipo ${t.label}; arrastra desde un nodo a otro`} onClick={() => { setRelationType(t.relation!); p.onRelationType(t.relation!); setActiveTool('Crear relación') }}>
                <span>{t.icon}</span>{t.label}
              </button>
            ))}
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
                    <button onClick={p.onGenerator}>Generar software</button>
                    <button onClick={p.onVersions}>Versiones</button>
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
              <div className="canvas-mount">{p.children}</div>
            </div>
          </main>

          <>
              <div
                className={`panel-resizer ${isResizingRight ? 'dragging' : ''}`}
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
    </footer>
  </div>
}

function PropertyRows({ title, rows, empty }: { title: string; rows: string[]; empty: string }) {
  return <div className="property-section"><span className="eyebrow">{title}</span>{rows.length ? rows.map((row, i) => <div className="property-row" key={`${row}-${i}`}>{row}</div>) : <div className="property-empty">{empty}</div>}</div>
}
