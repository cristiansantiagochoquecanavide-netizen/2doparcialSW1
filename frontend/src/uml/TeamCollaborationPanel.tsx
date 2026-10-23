import React, { useState, useRef, useEffect, FormEvent } from 'react'
import {
  ChatIcon,
  UsersIcon,
  MicIcon,
  SendIcon,
  LockIcon,
  VolumeIcon,
  HashIcon,
  MaximizeIcon,
  MinimizeIcon,
  BotIcon,
  AlertIcon,
  SquareIcon
} from './icons'

export type ChannelType = 'text' | 'voice' | 'ai-group' | 'ai-private'

export interface ChannelItem {
  id: string
  name: string
  type: ChannelType
  description: string
  badge?: string
}

export interface ChatMessage {
  id: string
  channelId: string
  senderName: string
  senderEmail: string
  senderRole: string
  targetAudience?: 'Equipo' | string // Dirigido a «Equipo» o al autor personal
  isAgent?: boolean
  isPrivate?: boolean
  text: string
  timestamp: string
  isDemo?: boolean
}

export interface TeamMember {
  id: string
  name: string
  email: string
  role: string
  isOnline?: boolean
}

interface Props {
  projectName: string
  diagramName: string
  members: TeamMember[]
  currentUser: {
    name: string
    email: string
    role: string
  }
  isWide: boolean
  onToggleWide: () => void
  onClose: () => void
  onSwitchToInspector: () => void
}

const CHANNELS: ChannelItem[] = [
  {
    id: 'general',
    name: 'general',
    type: 'text',
    description: 'Canal de debate general y coordinación del equipo'
  },
  {
    id: 'diseno-uml',
    name: 'diseño-uml',
    type: 'text',
    description: 'Discusión sobre entidades, relaciones y arquitectura del modelo'
  },
  {
    id: 'voz-diseno',
    name: 'Sala de diseño',
    type: 'voice',
    description: 'Canal de voz WebRTC en tiempo real',
    badge: 'Bloque 11'
  },
  {
    id: 'ai-grupo',
    name: 'ACODI (Grupal)',
    type: 'ai-group',
    description: 'Consultoría y recomendaciones públicas para todos los colaboradores'
  },
  {
    id: 'ai-privado',
    name: 'Mis notas con el Agente (Privado)',
    type: 'ai-private',
    description: 'Consultas confidenciales aisladas de otros participantes'
  }
]

const DEMO_FIXTURES: ChatMessage[] = [
  {
    id: 'msg-1',
    channelId: 'general',
    senderName: 'Carlos R.',
    senderEmail: 'carlos@caseapp.edu.bo',
    senderRole: 'PROPIETARIO',
    text: 'Bienvenidos al espacio de trabajo colaborativo. En este canal coordinamos el diseño del modelo UML.',
    timestamp: '10:15',
    isDemo: true
  },
  {
    id: 'msg-2',
    channelId: 'general',
    senderName: 'ACODI',
    senderEmail: 'agent@caseapp.internal',
    senderRole: 'AGENTE',
    targetAudience: 'Equipo',
    isAgent: true,
    text: 'Equipo: He analizado el diagrama activo. El modelo cuenta con entidades clave y consistencia estructural inicial. Supervisaré las modificaciones confirmadas para proponer mejoras.',
    timestamp: '10:16',
    isDemo: true
  },
  {
    id: 'msg-3',
    channelId: 'diseno-uml',
    senderName: 'ACODI',
    senderEmail: 'agent@caseapp.internal',
    senderRole: 'AGENTE',
    targetAudience: 'Equipo',
    isAgent: true,
    text: 'Equipo: Canal de diseño activo. Pueden debatir sobre multiplicidades o solicitarme verificar la normalización del esquema antes de generar código.',
    timestamp: '10:18',
    isDemo: true
  },
  {
    id: 'msg-4',
    channelId: 'ai-grupo',
    senderName: 'ACODI',
    senderEmail: 'agent@caseapp.internal',
    senderRole: 'AGENTE',
    targetAudience: 'Equipo',
    isAgent: true,
    text: 'Equipo: Espacio de consultoría grupal. Cualquier recomendación de arquitectura o propuesta estructurada generada aquí será visible para todos los miembros del proyecto.',
    timestamp: '10:20',
    isDemo: true
  },
  {
    id: 'msg-5',
    channelId: 'ai-privado',
    senderName: 'ACODI',
    senderEmail: 'agent@caseapp.internal',
    senderRole: 'AGENTE',
    targetAudience: 'Carlos R.',
    isAgent: true,
    isPrivate: true,
    text: 'Carlos: Este es tu espacio privado de consulta. Las preguntas técnicas y diagnósticos de errores aquí no se difunden por WebSocket grupal ni son visibles para otros colaboradores.',
    timestamp: '10:21',
    isDemo: true
  }
]

export function TeamCollaborationPanel({
  projectName,
  diagramName,
  members,
  currentUser,
  isWide,
  onToggleWide,
  onClose,
  onSwitchToInspector
}: Props) {
  const [activeChannelId, setActiveChannelId] = useState<string>('general')
  const [activeTab, setActiveTab] = useState<'chat' | 'members'>('chat')
  const [uiState, setUiState] = useState<'ready' | 'loading' | 'empty' | 'error'>('ready')
  const [inputMessage, setInputMessage] = useState('')
  const [isListening, setIsListening] = useState(false)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const recognitionRef = useRef<any>(null)

  const activeChannel = CHANNELS.find((c) => c.id === activeChannelId) ?? CHANNELS[0]
  const currentMessages = DEMO_FIXTURES.filter((m) => m.channelId === activeChannelId)

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [activeChannelId, uiState])

  // Reconocimiento de voz del navegador (Web Speech API para pruebas de interfaz)
  function toggleVoiceInput() {
    if (isListening) {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop()
        } catch {}
      }
      setIsListening(false)
      return
    }

    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    if (!SpeechRecognition) {
      alert('Tu navegador no soporta Web Speech API para dictado por voz.')
      return
    }

    try {
      const recognition = new SpeechRecognition()
      recognition.lang = 'es-ES'
      recognition.continuous = false
      recognition.interimResults = true

      recognition.onstart = () => setIsListening(true)
      recognition.onresult = (event: any) => {
        let transcript = ''
        for (let i = event.resultIndex; i < event.results.length; i++) {
          transcript += event.results[i][0].transcript
        }
        setInputMessage((prev) => (prev ? `${prev} ${transcript}` : transcript).trim())
      }
      recognition.onerror = () => setIsListening(false)
      recognition.onend = () => setIsListening(false)

      recognitionRef.current = recognition
      recognition.start()
    } catch {
      setIsListening(false)
    }
  }

  function handleFormSubmit(e: FormEvent) {
    e.preventDefault()
  }

  // Lista consolidada de participantes incluyendo al ACODI
  const allParticipants = [
    {
      id: 'ai-agent-user',
      name: 'ACODI',
      email: 'agente@case.local',
      role: 'ASISTENTE',
      isOnline: true,
      isAgent: true
    },
    ...members.map((m) => ({
      ...m,
      isOnline: true,
      isAgent: false
    }))
  ]

  return (
    <aside className={`team-panel ${isWide ? 'team-panel-wide' : ''}`}>
      {/* 1. Encabezado principal del panel */}
      <header className="team-panel-header">
        <div className="team-panel-title">
          <div className="team-badge-row">
            <span className="team-tag">ESPACIO DE EQUIPO</span>
            <span className="team-online-pill">EN LÍNEA</span>
          </div>
          <h2 title={projectName}>{projectName}</h2>
          <small className="team-diagram-sub" title={diagramName}>
            Diagrama: {diagramName}
          </small>
        </div>

        <div className="team-panel-actions">
          <button
            type="button"
            className="team-tool-btn"
            onClick={onToggleWide}
            title={isWide ? 'Cambiar a ancho estándar (340px)' : 'Cambiar a vista amplia (480px)'}
          >
            {isWide ? <MinimizeIcon size={14} /> : <MaximizeIcon size={14} />}
          </button>
          <button
            type="button"
            className="team-tool-btn"
            onClick={onSwitchToInspector}
            title="Cambiar al Inspector de propiedades UML"
          >
            Inspector
          </button>
          <button
            type="button"
            className="team-tool-btn close-btn"
            onClick={onClose}
            title="Colapsar panel derecho (maximizar lienzo)"
          >
            ✕
          </button>
        </div>
      </header>

      {/* 2. Barra de navegación de sub-pestañas */}
      <div className="team-subtabs">
        <button
          type="button"
          className={`team-subtab-btn ${activeTab === 'chat' ? 'active' : ''}`}
          onClick={() => setActiveTab('chat')}
        >
          <ChatIcon size={13} style={{ marginRight: 4, verticalAlign: 'middle' }} />
          Canales y Chat
        </button>
        <button
          type="button"
          className={`team-subtab-btn ${activeTab === 'members' ? 'active' : ''}`}
          onClick={() => setActiveTab('members')}
        >
          <UsersIcon size={13} style={{ marginRight: 4, verticalAlign: 'middle' }} />
          Participantes ({allParticipants.length})
        </button>
      </div>

      {/* Selector de verificación de estados de UI requeridos por Bloque 9 */}
      <div className="team-ui-state-bar">
        <span className="state-bar-label">Vista de UI:</span>
        <button
          type="button"
          className={`state-toggle-btn ${uiState === 'ready' ? 'active' : ''}`}
          onClick={() => setUiState('ready')}
          title="Ver interfaz con datos demo tipados"
        >
          Demo
        </button>
        <button
          type="button"
          className={`state-toggle-btn ${uiState === 'loading' ? 'active' : ''}`}
          onClick={() => setUiState('loading')}
          title="Comprobar estado de carga visual"
        >
          Cargando
        </button>
        <button
          type="button"
          className={`state-toggle-btn ${uiState === 'empty' ? 'active' : ''}`}
          onClick={() => setUiState('empty')}
          title="Comprobar estado vacío"
        >
          Vacío
        </button>
        <button
          type="button"
          className={`state-toggle-btn ${uiState === 'error' ? 'active' : ''}`}
          onClick={() => setUiState('error')}
          title="Comprobar estado de error"
        >
          Error
        </button>
      </div>

      {activeTab === 'members' ? (
        /* Vista de participantes */
        <div className="team-members-view">
          <div className="team-section-label">MIEMBROS DEL EQUIPO Y ASISTENTE</div>
          <ul className="team-member-list">
            {allParticipants.map((p) => (
              <li key={p.id} className={`team-member-item ${p.isAgent ? 'is-agent-member' : ''}`}>
                <div className="team-avatar">
                  {p.isAgent ? <BotIcon size={15} /> : p.name.slice(0, 1).toUpperCase()}
                </div>
                <div className="team-member-meta">
                  <div className="team-member-name-row">
                    <strong>{p.name}</strong>
                    {p.isAgent ? (
                      <span className="role-pill agent-pill">ASISTENTE IA</span>
                    ) : (
                      <span className="role-pill">{p.role}</span>
                    )}
                  </div>
                  <span className="team-member-email">{p.email}</span>
                </div>
                <span className="team-presence-indicator" title="Participante conectado">
                  ACTIVO
                </span>
              </li>
            ))}
          </ul>
          <div className="team-members-footer">
            <small>
              El ACODI está reconocido como miembro técnico del equipo para consultas y
              supervisión continua.
            </small>
          </div>
        </div>
      ) : (
        /* Vista de Canales y Chat */
        <div className="team-content-split">
          {/* Navegación de canales (estilo Discord) */}
          <nav className="team-channel-sidebar">
            <div className="team-channel-group-title">CANALES DE TEXTO</div>
            {CHANNELS.filter((c) => c.type === 'text').map((c) => (
              <button
                key={c.id}
                type="button"
                className={`team-channel-link ${activeChannelId === c.id ? 'active' : ''}`}
                onClick={() => setActiveChannelId(c.id)}
                title={c.description}
              >
                <HashIcon size={13} className="channel-icon" />
                <span className="channel-name">{c.name}</span>
              </button>
            ))}

            <div className="team-channel-group-title">CANALES DE VOZ</div>
            {CHANNELS.filter((c) => c.type === 'voice').map((c) => (
              <div
                key={c.id}
                className={`team-channel-link voice-channel ${
                  activeChannelId === c.id ? 'active' : ''
                }`}
                title={c.description}
                onClick={() => setActiveChannelId(c.id)}
              >
                <VolumeIcon size={13} className="channel-icon" />
                <span className="channel-name">{c.name}</span>
                {c.badge && <span className="channel-badge">{c.badge}</span>}
              </div>
            ))}

            <div className="team-channel-group-title">ASISTENTE IA</div>
            {CHANNELS.filter((c) => c.type === 'ai-group' || c.type === 'ai-private').map((c) => (
              <button
                key={c.id}
                type="button"
                className={`team-channel-link ${c.type === 'ai-private' ? 'private-channel' : ''} ${
                  activeChannelId === c.id ? 'active' : ''
                }`}
                onClick={() => setActiveChannelId(c.id)}
                title={c.description}
              >
                {c.type === 'ai-private' ? (
                  <LockIcon size={12} className="channel-icon" />
                ) : (
                  <BotIcon size={13} className="channel-icon" />
                )}
                <span className="channel-name">{c.name}</span>
              </button>
            ))}
          </nav>

          {/* Área de mensajes del canal seleccionado */}
          <section className="team-chat-area">
            <div className="team-chat-header">
              <div className="team-chat-header-info">
                {activeChannel.type === 'text' && <HashIcon size={14} className="channel-icon-header" />}
                {activeChannel.type === 'voice' && <VolumeIcon size={14} className="channel-icon-header" />}
                {activeChannel.type === 'ai-group' && <BotIcon size={14} className="channel-icon-header" />}
                {activeChannel.type === 'ai-private' && <LockIcon size={14} className="channel-icon-header" />}
                <strong>{activeChannel.name}</strong>
              </div>
              <span className="team-chat-header-desc">{activeChannel.description}</span>
            </div>

            {/* Banner explícito de maqueta Bloque 9 */}
            <div className="team-mock-banner">
              <small>
                <strong>DEMO BLOQUE 9:</strong> Interfaz visual y canales estructurados. La persistencia en
                PostgreSQL y difusión por WebSocket se conectarán en el Bloque 10.
              </small>
            </div>

            {/* Renderizado condicional por estado de UI */}
            {uiState === 'loading' ? (
              <div className="team-state-container">
                <div className="team-spinner" />
                <p>Cargando mensajes del canal #{activeChannel.name}…</p>
              </div>
            ) : uiState === 'error' ? (
              <div className="team-state-container team-error-state">
                <AlertIcon size={24} color="#dc2626" />
                <h4>No se pudo conectar al canal</h4>
                <p>Error temporal de comunicación con el servicio de mensajería.</p>
                <button
                  type="button"
                  className="team-retry-btn"
                  onClick={() => setUiState('ready')}
                >
                  Reintentar
                </button>
              </div>
            ) : uiState === 'empty' || currentMessages.length === 0 ? (
              <div className="team-state-container">
                <ChatIcon size={28} color="#94a3b8" />
                <p>Aún no hay mensajes en #{activeChannel.name}.</p>
                <small>Canal listo para recibir consultas e intervenciones del equipo.</small>
              </div>
            ) : (
              <>
                {/* Sala de voz (en desarrollo Bloque 11) */}
                {activeChannel.type === 'voice' && (
                  <div className="team-voice-room-card">
                    <VolumeIcon size={24} color="#2563eb" />
                    <h4>Sala de voz colaborativa</h4>
                    <p>
                      Permitirá comunicación por voz WebRTC entre desarrolladores y escucha del Agente
                      CASE IA durante sesiones de modelado.
                    </p>
                    <div className="voice-room-status">
                      <span className="status-label">Estado:</span>
                      <strong>Disponible en Bloque 11 (WebRTC y señalización)</strong>
                    </div>
                    <button
                      type="button"
                      className="voice-connect-btn disabled"
                      disabled
                      title="Disponible en Bloque 11"
                    >
                      Unirse a la sala (Bloque 11)
                    </button>
                  </div>
                )}

                {/* Lista de mensajes */}
                <div className="team-messages-scroll">
                  {currentMessages.map((msg) => (
                    <article
                      key={msg.id}
                      className={`team-message-card ${
                        msg.isAgent ? 'team-msg-agent' : ''
                      } ${msg.isPrivate ? 'team-msg-private' : ''}`}
                    >
                      <div className="team-msg-avatar">
                        {msg.isAgent ? <BotIcon size={15} /> : msg.senderName.slice(0, 1).toUpperCase()}
                      </div>
                      <div className="team-msg-body">
                        <header className="team-msg-header">
                          <strong className="team-msg-author">{msg.senderName}</strong>
                          {msg.isAgent ? (
                            <span className="role-pill agent-pill">AGENTE IA</span>
                          ) : (
                            <span className="role-pill">{msg.senderRole}</span>
                          )}
                          {msg.targetAudience && (
                            <span className="target-pill" title={`Dirigido a: ${msg.targetAudience}`}>
                              Para: {msg.targetAudience}
                            </span>
                          )}
                          {msg.isPrivate && (
                            <span className="role-pill private-pill" title="Solo visible para ti">
                              PRIVADO
                            </span>
                          )}
                          <span className="demo-chip">DEMO</span>
                          <time className="team-msg-time">{msg.timestamp}</time>
                        </header>
                        <div className="team-msg-text">{msg.text}</div>
                      </div>
                    </article>
                  ))}
                  <div ref={messagesEndRef} />
                </div>
              </>
            )}

            {/* Formulario de entrada de mensajes */}
            <form className="team-input-form" onSubmit={handleFormSubmit}>
              <div className="team-input-scope-indicator">
                {activeChannel.type === 'ai-private' ? (
                  <span>
                    <LockIcon size={11} style={{ marginRight: 3, verticalAlign: 'middle' }} />
                    <strong>Ámbito privado:</strong> Consulta confidencial aislada del resto del equipo
                  </span>
                ) : (
                  <span>
                    <UsersIcon size={11} style={{ marginRight: 3, verticalAlign: 'middle' }} />
                    <strong>Canal público:</strong> Mensaje visible para todos los colaboradores de {projectName}
                  </span>
                )}
              </div>

              <div className="team-input-bar">
                <input
                  type="text"
                  value={inputMessage}
                  onChange={(e) => setInputMessage(e.target.value)}
                  placeholder={
                    activeChannel.type === 'ai-private'
                      ? 'Entrada privada (Disponible en Bloque 10 con persistencia y WebSocket)'
                      : `Mensaje para #${activeChannel.name} (Disponible en Bloque 10)`
                  }
                  disabled
                  title="El envío interactivo se habilitará con los endpoints y WebSocket del Bloque 10"
                />
                <button
                  type="button"
                  className={`team-mic-btn ${isListening ? 'listening' : ''}`}
                  onClick={toggleVoiceInput}
                  title="Dictado por voz con Web Speech API (Activo en navegador para pruebas de entrada)"
                >
                  {isListening ? <SquareIcon size={14} /> : <MicIcon size={14} />}
                </button>
                <button
                  type="submit"
                  className="team-send-btn disabled"
                  disabled
                  title="Disponible en Bloque 10"
                >
                  <SendIcon size={14} />
                </button>
              </div>

              <div className="team-input-hint">
                <small>
                  El envío de mensajes y la persistencia en base de datos corresponden al{' '}
                  <strong>Bloque 10</strong>. La sala de voz WebRTC corresponde al <strong>Bloque 11</strong>.
                </small>
              </div>
            </form>
          </section>
        </div>
      )}
    </aside>
  )
}
