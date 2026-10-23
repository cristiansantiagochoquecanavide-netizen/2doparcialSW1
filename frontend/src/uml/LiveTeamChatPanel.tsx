import React, { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState } from 'react'
import { ChatIcon, UsersIcon, MicIcon, SendIcon, VolumeIcon, HashIcon, MaximizeIcon, MinimizeIcon } from './icons'
import { VoiceRoom } from './VoiceRoom'
import { AiConversationsPanel } from './AiConversationsPanel'
import { useAgentSpeech } from './AgentSpeechUI'
import { AcodiChannelMessage } from './AcodiChannelMessage'
import { insertAcodiMention, channelSpeechMessage, channelRecommendationFrame } from './acodiChannelClient.mjs'

export interface ChannelItem { id: string; slug: string; name: string; description: string | null; createdAt: string }
export interface ChatMessage {
  id: string; channelId: string; senderUserId: string | null; senderName: string; senderEmail: string | null
  senderRole: string; authorType: 'USER' | 'AGENT'; clientMessageId: string; content: string
  createdAt: string; replyToMessageId: string | null
  canonicalAiMessageId?: string | null
  acodi?: { status: string; diagramId: string | null; attempts: number; errorCode: string | null; retryAfter: string | null; responseMessageId: string | null; maxAttempts?: number } | null
}
export interface TeamMember { id: string; userId: string; name: string; email: string; role: string }
type MessagePage = { messages: ChatMessage[]; nextCursor: string | null; hasMore: boolean }
type PageState = { nextCursor: string | null; hasMore: boolean; loaded: boolean; loadingOlder: boolean }
type Outgoing = { clientMessageId: string; content: string; channelId: string; diagramId: string | null }
interface Props {
  projectId: string; diagramId: string | null; projectName: string; diagramName: string; token: string; members: TeamMember[]
  currentUser: { name: string; email: string; role: string }; isWide: boolean; visible?: boolean
  onToggleWide: () => void; onClose: () => void; onSwitchToInspector: () => void
}
const PAGE_SIZE = 50
const apiBase = import.meta.env.VITE_API_BASE_URL || ''

function mergeMessages(old: ChatMessage[], next: ChatMessage[]) {
  const map = new Map(old.map((message) => [message.id, message]))
  next.forEach((message) => map.set(message.id, message))
  return [...map.values()].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id.localeCompare(b.id))
}
async function request<T>(path: string, token: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(apiBase + path, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), Authorization: 'Bearer ' + token, ...options.headers }
  })
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event('case:unauthorized'))
    const raw = await response.text()
    let message = ''
    try { message = (JSON.parse(raw) as { message?: string }).message || '' } catch { message = raw }
    throw new Error(message || 'No se pudo completar la operación (' + response.status + ').')
  }
  return response.json() as Promise<T>
}
function messageTime(value: string) {
  return new Intl.DateTimeFormat('es-BO', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

export function LiveTeamChatPanel({
  projectId, diagramId, projectName, diagramName, token, members, currentUser, isWide, visible = true,
  onToggleWide, onClose, onSwitchToInspector
}: Props) {
  const speech = useAgentSpeech()
  const composerRef = useRef<HTMLTextAreaElement>(null)
  const drafts = useRef(new Map<string, string>())
  const retryKeys = useRef(new Map<string, string>())
  const [agentBusy, setAgentBusy] = useState(false)
  const [channels, setChannels] = useState<ChannelItem[]>([])
  const [activeChannelId, setActiveChannelId] = useState('')
  const [activeTab, setActiveTab] = useState<'chat' | 'members' | 'voice'>('chat')
  const [agentConversations, setAgentConversations] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [reload, setReload] = useState(0)
  const [connection, setConnection] = useState<'connecting' | 'connected' | 'offline' | 'revoked'>('connecting')
  const [messages, setMessages] = useState<Record<string, ChatMessage[]>>({})
  const [pages, setPages] = useState<Record<string, PageState>>({})
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [retry, setRetry] = useState<Outgoing | null>(null)
  const [sendError, setSendError] = useState('')
  const channelsRef = useRef<ChannelItem[]>([])
  const activeRef = useRef(activeChannelId)
  const nearBottom = useRef(true)
  const scrollRef = useRef<HTMLDivElement>(null)
  const endRef = useRef<HTMLDivElement>(null)
  activeRef.current = activeChannelId

  const activeChannel = channels.find((channel) => channel.id === activeChannelId) || null
  const activeMessages = messages[activeChannelId] || []
  const page = pages[activeChannelId]
  const initialLoaded = Boolean(page?.loaded)
  const [, setRetryClock] = useState(0)
  const hasFailedAgent = activeMessages.some(message => message.acodi?.status === 'FALLIDO')
  useEffect(() => { if (!hasFailedAgent) return; const timer = window.setInterval(() => setRetryClock(value => value + 1), 2000); return () => clearInterval(timer) }, [hasFailedAgent])
  useEffect(() => {
    if (activeTab === 'chat' && !agentConversations) speech.view({ conversationId: 'channel:' + activeChannelId, scope: 'GROUP', active: visible && Boolean(activeChannelId) })
    else if (activeTab !== 'chat') speech.view({ conversationId: '', scope: 'GROUP', active: false })
  }, [activeTab, activeChannelId, agentConversations, visible, speech.view])
  function selectChannel(id: string) { drafts.current.set(activeChannelId, input); setActiveChannelId(id); setInput(drafts.current.get(id) || ''); setAgentConversations(false) }
  function mention() {
    const result = insertAcodiMention(input, composerRef.current?.selectionStart ?? input.length)
    if (result.text.length > 4000) { setSendError('El mensaje está lleno. Deja espacio para insertar @ACODI.'); return }
    setInput(result.text); requestAnimationFrame(() => { composerRef.current?.focus(); composerRef.current?.setSelectionRange(result.cursor, result.cursor) })
  }
  async function retryAcodi(message: ChatMessage) {
    if (agentBusy) return
    setAgentBusy(true); setSendError('')
    const key = retryKeys.current.get(message.id) || crypto.randomUUID(); retryKeys.current.set(message.id, key)
    try { await request(`/api/projects/${projectId}/chat/channels/${message.channelId}/messages/${message.id}/acodi/retry`, token, { method: 'POST', body: JSON.stringify({ clientRequestId: key, diagramId }) }); retryKeys.current.delete(message.id); await refresh() }
    catch (error) { setSendError(error instanceof Error ? error.message : 'No se pudo reintentar ACODI.') } finally { setAgentBusy(false) }
  }

  const refresh = useCallback(async () => {
    const results = await Promise.all(channelsRef.current.map(async (channel) => ({
      channel, page: await request<MessagePage>(
        '/api/projects/' + projectId + '/chat/channels/' + channel.id + '/messages?limit=' + PAGE_SIZE, token
      )
    })))
    results.forEach(({ channel, page }) => {
      setMessages((old) => ({ ...old, [channel.id]: mergeMessages(old[channel.id] || [], page.messages) }))
      setPages((old) => ({ ...old, [channel.id]: { nextCursor: page.nextCursor, hasMore: page.hasMore, loaded: true, loadingOlder: false } }))
    })
  }, [projectId, token])

  useEffect(() => {
    let cancelled = false
    channelsRef.current = []
    setChannels([]); setActiveChannelId(''); setMessages({}); setPages({})
    setLoading(true); setLoadError(''); setSendError('')
    drafts.current.clear(); retryKeys.current.clear(); setInput('')
    async function load() {
      try {
        const list = await request<ChannelItem[]>('/api/projects/' + projectId + '/chat/channels', token)
        if (cancelled) return
        channelsRef.current = list
        setChannels(list)
        setActiveChannelId((current) => list.some((item) => item.id === current) ? current : (list.find((item) => item.slug === 'general') || list[0])?.id || '')
        const results = await Promise.all(list.map(async (channel) => ({
          channel, page: await request<MessagePage>(
            '/api/projects/' + projectId + '/chat/channels/' + channel.id + '/messages?limit=' + PAGE_SIZE, token
          )
        })))
        if (cancelled) return
        const nextMessages: Record<string, ChatMessage[]> = {}
        const nextPages: Record<string, PageState> = {}
        results.forEach(({ channel, page }) => {
          nextMessages[channel.id] = mergeMessages([], page.messages)
          nextPages[channel.id] = { nextCursor: page.nextCursor, hasMore: page.hasMore, loaded: true, loadingOlder: false }
        })
        setMessages((old) => {
          const merged = { ...nextMessages }
          Object.entries(old).forEach(([id, items]) => { merged[id] = mergeMessages(merged[id] || [], items) })
          return merged
        })
        setPages(nextPages)
      } catch (error) {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : 'No se pudo cargar el chat.')
      } finally { if (!cancelled) setLoading(false) }
    }
    void load()
    return () => { cancelled = true }
  }, [projectId, token, reload])

  useEffect(() => {
    let disposed = false
    let socket: WebSocket | null = null
    let timer: number | undefined
    let delay = 1000
    function connect() {
      if (disposed) return
      setConnection('connecting')
      const url = new URL(apiBase || window.location.origin)
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      url.pathname = '/ws/projects/' + projectId + '/chat'
      url.search = ''
      socket = new WebSocket(url.toString(), ['case-events-v1', 'bearer.' + token])
      socket.onopen = () => { delay = 1000; setConnection('connected'); speech.chat(true); void refresh().catch(() => undefined) }
      socket.onmessage = (event) => {
        try {
          const frame = JSON.parse(String(event.data)) as { type?: string; projectId?: string; channelId?: string; message?: ChatMessage }
          if (frame.type === 'AI_CONVERSATION_MESSAGE' && frame.projectId === projectId) {
            speech.receive(frame)
            window.dispatchEvent(new CustomEvent('case:ai-message', { detail: frame }))
            return
          }
          if (frame.type !== 'CHAT_MESSAGE' || frame.projectId !== projectId || !frame.channelId || !frame.message || frame.message.channelId !== frame.channelId) return
          const msg = frame.message
          const recommendation = channelRecommendationFrame(projectId, msg)
          if (recommendation) speech.receive(recommendation)
          const scroll = activeRef.current === frame.channelId && nearBottom.current
          setMessages((old) => ({ ...old, [frame.channelId as string]: mergeMessages(old[frame.channelId as string] || [], [msg]) }))
          if (scroll) requestAnimationFrame(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }))
        } catch { /* REST recovers messages after reconnect. */ }
      }
      socket.onclose = (event) => {
        if (disposed) return
        speech.chat(false)
        if (event.code === 1008) { setConnection('revoked'); return }
        setConnection('offline')
        timer = window.setTimeout(connect, delay)
        delay = Math.min(delay * 2, 30000)
      }
      socket.onerror = () => socket?.close()
    }
    connect()
    return () => { disposed = true; speech.chat(false); if (timer !== undefined) clearTimeout(timer); socket?.close() }
  }, [projectId, token, refresh, speech.chat, speech.receive])

  useEffect(() => {
    if (activeChannelId && initialLoaded) {
      nearBottom.current = true
      endRef.current?.scrollIntoView({ behavior: 'auto' })
    }
  }, [activeChannelId, initialLoaded])

  function onScroll() {
    const node = scrollRef.current
    if (node) nearBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 80
  }
  async function loadOlder() {
    if (!activeChannel || !page?.hasMore || !page.nextCursor || page.loadingOlder) return
    const node = scrollRef.current, height = node?.scrollHeight || 0, top = node?.scrollTop || 0
    const channel = activeChannel
    setPages((old) => ({ ...old, [channel.id]: { ...old[channel.id], loadingOlder: true } }))
    try {
      const result = await request<MessagePage>(
        '/api/projects/' + projectId + '/chat/channels/' + channel.id + '/messages?limit=' + PAGE_SIZE + '&before=' + page.nextCursor, token
      )
      setMessages((old) => ({ ...old, [channel.id]: mergeMessages(result.messages, old[channel.id] || []) }))
      setPages((old) => ({ ...old, [channel.id]: { nextCursor: result.nextCursor, hasMore: result.hasMore, loaded: true, loadingOlder: false } }))
      requestAnimationFrame(() => { if (node) node.scrollTop = top + node.scrollHeight - height })
    } catch (error) {
      setPages((old) => ({ ...old, [channel.id]: { ...old[channel.id], loadingOlder: false } }))
      setLoadError(error instanceof Error ? error.message : 'No se pudieron cargar mensajes anteriores.')
    }
  }
  async function send() {
    if (sending || !activeChannelId) return
    const outgoing = retry || { clientMessageId: crypto.randomUUID(), content: input.trim(), channelId: activeChannelId, diagramId }
    if (!outgoing.content) return
    setSending(true); setSendError('')
    try {
      const message = await request<ChatMessage>(
        '/api/projects/' + projectId + '/chat/channels/' + outgoing.channelId + '/messages',
        token, { method: 'POST', body: JSON.stringify(outgoing) }
      )
      setMessages((old) => ({ ...old, [message.channelId]: mergeMessages(old[message.channelId] || [], [message]) }))
      setPages((old) => ({ ...old, [message.channelId]: { ...old[message.channelId], loaded: true } }))
      if (activeRef.current === message.channelId) setInput((value) => value.trim() === outgoing.content ? '' : value)
      if (drafts.current.get(message.channelId)?.trim() === outgoing.content) drafts.current.delete(message.channelId)
      setRetry(null); nearBottom.current = true
      requestAnimationFrame(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }))
    } catch (error) {
      setRetry(outgoing); setSendError(error instanceof Error ? error.message : 'No se pudo enviar el mensaje.')
    } finally { setSending(false) }
  }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void send() }
  function inputKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit() }
  }
  const connectionLabel = connection === 'connected' ? 'CHAT EN VIVO' : connection === 'connecting' ? 'CONECTANDO' : connection === 'revoked' ? 'SIN ACCESO' : 'RECONECTANDO'

  return (
    <aside className={'team-panel' + (isWide ? ' team-panel-wide' : '')}>
      <header className="team-panel-header">
        <div className="team-panel-title">
          <div className="team-badge-row"><span className="team-tag">ESPACIO DE EQUIPO</span><span className={'team-online-pill ' + (connection === 'connected' ? 'chat-connected' : '')}>{connectionLabel}</span></div>
          <h2 title={projectName}>{projectName}</h2><small className="team-diagram-sub" title={diagramName}>Diagrama: {diagramName}</small>
        </div>
        <div className="team-panel-actions">
          <button type="button" className="team-tool-btn" onClick={onToggleWide} title={isWide ? 'Cambiar a ancho estándar (340px)' : 'Cambiar a vista amplia (480px)'}>
            {isWide ? <MinimizeIcon size={14} /> : <MaximizeIcon size={14} />}
          </button>
          <button type="button" className="team-tool-btn" onClick={onSwitchToInspector}>Inspector</button>
          <button type="button" className="team-tool-btn close-btn" onClick={onClose} title="Colapsar panel derecho">×</button>
        </div>
      </header>
      <div className="team-subtabs">
        <button type="button" className={'team-subtab-btn ' + (activeTab === 'chat' ? 'active' : '')} onClick={() => setActiveTab('chat')}><ChatIcon size={13} /> Canales y Chat</button>
        <button type="button" className={'team-subtab-btn ' + (activeTab === 'members' ? 'active' : '')} onClick={() => setActiveTab('members')}><UsersIcon size={13} /> Participantes ({members.length})</button>
      </div>

      <div className="team-voice-view" hidden={activeTab !== 'voice'}>
        <VoiceRoom key={projectId + ':' + token} projectId={projectId} token={token} currentUserName={currentUser.name} onBackToChat={() => setActiveTab('chat')} />
      </div>
      {activeTab === 'members' && (
        <div className="team-members-view">
          <div className="team-virtual-agent"><span className="team-avatar">AC</span><strong>ACODI</strong><span className="role-pill">Agente IA</span><small>Agente Colaborador de Diagramado · Invócalo con @ACODI en un canal.</small></div>
          <div className="team-section-label">MIEMBROS DEL PROYECTO</div>
          {members.length === 0 ? <div className="team-state-container"><p>No hay miembros disponibles.</p></div> : (
            <ul className="team-member-list">{members.map((member) => (
              <li key={member.id} className="team-member-item">
                <div className="team-avatar">{member.name.slice(0, 1).toUpperCase()}</div>
                <div className="team-member-meta"><div className="team-member-name-row"><strong>{member.name}</strong>{member.email.toLowerCase() === currentUser.email.toLowerCase() && <span className="role-pill">TÚ</span>}</div><span className="team-member-email">{member.email}</span></div>
                <span className="role-pill">{member.role}</span>
              </li>
            ))}</ul>
          )}
        </div>
      )}
        <div className="team-content-split" hidden={activeTab !== 'chat'}>
          <nav className="team-channel-sidebar" aria-label="Canales del proyecto">
            <div className="team-channel-group-title">CANALES DE TEXTO</div>
            {channels.map((channel) => <button key={channel.id} type="button" className={'team-channel-link ' + (activeChannelId === channel.id ? 'active' : '')} onClick={() => selectChannel(channel.id)} title={channel.description || channel.name}><HashIcon size={13} className="channel-icon" /><span className="channel-name">{channel.name}</span></button>)}
            <div className="team-channel-group-title">CANALES DE VOZ</div>
            <button type="button" className="team-channel-link voice-channel" onClick={() => setActiveTab('voice')} title="Entrar a la sala de voz"><VolumeIcon size={13} className="channel-icon" /><span className="channel-name">Sala de diseño</span></button>
            <div className="team-channel-group-title">CONVERSACIONES DE ACODI</div>
            <button type="button" className={'team-channel-link ' + (agentConversations ? 'active' : '')} onClick={() => setAgentConversations(true)}>Historiales y chats privados</button>
          </nav>
          <section className="team-chat-area" hidden={!agentConversations}>
            <AiConversationsPanel key={projectId + ':' + token} projectId={projectId} diagramId={diagramId} token={token} currentUser={currentUser} active={visible && activeTab === 'chat' && agentConversations} chatOnly />
          </section>
          <section className="team-chat-area" hidden={agentConversations}>
            <div className="team-chat-header"><div className="team-chat-header-info"><HashIcon size={14} className="channel-icon-header" /><strong>{activeChannel?.name || 'Canales del proyecto'}</strong></div><span className="team-chat-header-desc">{activeChannel?.description || 'Mensajes visibles para miembros autorizados.'}</span></div>
            {loading ? <div className="team-state-container"><div className="team-spinner" /><p>Cargando canales y mensajes…</p></div>
              : loadError && channels.length === 0 ? <div className="team-state-container team-error-state"><h4>No se pudo cargar el chat</h4><p>{loadError}</p><button type="button" className="team-retry-btn" onClick={() => setReload((value) => value + 1)}>Reintentar</button></div>
                : channels.length === 0 ? <div className="team-state-container"><ChatIcon size={28} color="#94a3b8" /><p>Este proyecto todavía no tiene canales.</p></div>
                  : <>
                    {loadError && <div className="team-inline-error" role="alert">{loadError}<button type="button" onClick={() => { setLoadError(''); setReload((value) => value + 1) }}>Reintentar</button></div>}
                    <div className="team-messages-scroll" ref={scrollRef} onScroll={onScroll}>
                      {page?.hasMore && <button type="button" className="team-load-older-btn" disabled={page.loadingOlder} onClick={() => void loadOlder()}>{page.loadingOlder ? 'Cargando…' : 'Cargar mensajes anteriores'}</button>}
                      {activeMessages.length === 0 && initialLoaded ? <div className="team-state-container"><ChatIcon size={28} color="#94a3b8" /><p>Aún no hay mensajes en #{activeChannel?.name}.</p><small>Los mensajes se guardan y sincronizan con integrantes autorizados.</small></div>
                        : activeMessages.map((message) => {
                          const own = message.senderEmail?.toLowerCase() === currentUser.email.toLowerCase()
                          return <AcodiChannelMessage key={message.id} message={message} own={own} canListen={speech.state.available && !speech.state.microphoneActive && activeTab === 'chat' && visible} canRetry={own} busy={agentBusy} onListen={() => speech.manual(channelSpeechMessage(message), { projectId, conversationId: 'channel:' + activeChannelId, scope: 'GROUP' })} onRetry={() => void retryAcodi(message)} />
                        })}
                      <div ref={endRef} />
                    </div>
                  </>}
            <form className="team-input-form" onSubmit={submit}>
              <div className="team-input-scope-indicator"><UsersIcon size={11} /><span><strong>Canal del proyecto:</strong> visible para sus miembros autorizados</span></div>
              <div className="team-input-bar">
                <textarea ref={composerRef} value={input} rows={2} maxLength={4000} onChange={(event) => { setInput(event.target.value); if (retry && event.target.value.trim() !== retry.content) { setRetry(null); setSendError('') } }} onKeyDown={inputKeyDown} placeholder={activeChannel ? 'Mensaje para #' + activeChannel.name : 'Selecciona un canal'} disabled={!activeChannel || loading || connection === 'revoked' || sending} aria-label="Escribe un mensaje" />
                <button type="button" className="team-acodi-invoke" disabled={!activeChannel || loading || sending || connection === 'revoked'} onClick={mention} title="Invocar a ACODI — Agente Colaborador de Diagramado">@ACODI</button>
                <button type="submit" className="team-send-btn" disabled={!activeChannel || loading || !input.trim() || sending || connection === 'revoked' || Boolean(retry && retry.channelId !== activeChannelId)} title={retry ? (retry.channelId === activeChannelId ? 'Reintentar con la misma clave de idempotencia' : 'Vuelve a #' + (channels.find((channel) => channel.id === retry.channelId)?.name || 'canal') + ' para reintentar') : 'Enviar mensaje'}><SendIcon size={14} /><span>{sending ? 'Enviando…' : retry ? (retry.channelId === activeChannelId ? 'Reintentar' : 'Volver al canal') : 'Enviar'}</span></button>
              </div>
              <div className="team-input-hint"><small>Enter envía · Shift+Enter agrega una línea · @ACODI ayuda: memoria, análisis y propuestas</small>{sendError && <small className="team-send-error" role="alert">{sendError}</small>}</div>
            </form>
          </section>
        </div>
    </aside>
  )
}
