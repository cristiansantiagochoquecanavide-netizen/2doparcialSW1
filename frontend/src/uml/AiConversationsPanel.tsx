import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { AiContextDictation } from './AiContextDictation'
import { AiProactivePanel } from './AiProactivePanel'
import { UmlProposalsPanel } from './UmlProposalsPanel'
import { canEditSharedContext } from './agentAnalysisClient.mjs'
import { useAgentSpeech } from './AgentSpeechUI'
import { ExpandableMessage } from './ExpandableMessage'

type Scope = 'GROUP' | 'PRIVATE'
type Conversation = { id: string; scope: Scope; title: string; diagramId: string | null; updatedAt: string; archivedAt: string | null }
type Message = { id: string; clientMessageId: string | null; senderType: 'USER' | 'AGENT'; senderName: string; recipientName: string | null; content: string; status: 'PENDING' | 'COMPLETED' | 'ERROR'; errorCode: string | null; createdAt: string; replyToMessageId: string | null }
type Page = { messages: Message[]; nextCursor: string | null; hasMore: boolean }
type Memory = { projectMemory: { systemName: string | null; systemDescription: string | null; businessDomain: string | null; objectives: string | null; additionalContext: string | null } | null; entries: { id: string; entryType: string; confidenceScope: string; content: string; diagramId: string | null }[] }
type Props = { projectId: string; diagramId: string | null; token: string; currentUser: { name: string; email: string; role: string }; comparisonVersionIds: string[]; active?: boolean; chatOnly?: boolean; navigationHidden?: boolean; selectedConversationId?: string; selectedConversationTitle?: string }
const apiBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
const entryLabels: Record<string,string> = { SYSTEM_CONTEXT: 'Contexto del sistema', BUSINESS_RULE: 'Regla de negocio', FUNCTIONAL_CONSTRAINT: 'Restricción funcional', ARCHITECTURE_DECISION: 'Decisión arquitectónica', MODELING_CONVENTION: 'Convención de modelado', ADDITIONAL: 'Información adicional' }

async function api<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(apiBase + path, { ...init, headers: { ...(init.body ? { 'Content-Type': 'application/json' } : {}), Authorization: 'Bearer ' + token, ...init.headers } })
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event('case:unauthorized'))
    const body = await response.text()
    let detail = body
    try { detail = JSON.parse(body).message || body } catch { /* Plain text error. */ }
    throw new Error(detail || `Error ${response.status}`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export function AiConversationsPanel({ projectId, diagramId, token, currentUser, comparisonVersionIds, active = true, chatOnly = false, navigationHidden = false, selectedConversationId, selectedConversationTitle }: Props) {
  const speech = useAgentSpeech()
  const base = `/api/projects/${projectId}/ai`
  const [scope, setScope] = useState<Scope>('GROUP')
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState('')
  useEffect(() => { if (!active) return; speech.view({ conversationId: activeId, scope, active }); return () => speech.view({ conversationId: '', scope, active: false }) }, [activeId, scope, active, speech.view])
  const activeKey = useRef('')
  const speechSources = useRef(new Map<string, { projectId: string; conversationId: string; scope: Scope }>())
  activeKey.current = projectId + ':' + (navigationHidden ? selectedConversationId || '' : activeId)
  const [messageHistory, setMessages] = useState<Message[]>([])
  const [historyId, setHistoryId] = useState('')
  const [cursor, setCursor] = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const messagesRef = useRef<HTMLDivElement>(null)
  const nearBottom = useRef(true)
  const olderLoading = useRef(false)
  const [input, setInput] = useState('')
  const conversationDrafts = useRef(new Map<string, string>()), lastDraftId = useRef(''), inputRef = useRef(input)
  inputRef.current = input
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [memory, setMemory] = useState<Memory | null>(null)
  const [memoryOpen, setMemoryOpen] = useState(false)
  const [rule, setRule] = useState('')
  const [contextSaving, setContextSaving] = useState(false)
  const [contextSaved, setContextSaved] = useState(false)
  const [draft, setDraft] = useState({ systemName: '', systemDescription: '', businessDomain: '', objectives: '', additionalContext: '' })
  const visibleConversations = useMemo(() => conversations.filter((item) => item.scope === scope), [conversations, scope])
  const conversationReady = historyId === activeId && (!navigationHidden || selectedConversationId === activeId) && conversations.some(item => item.id === activeId && item.scope === scope)
  const messages = conversationReady ? messageHistory : []
  useEffect(() => {
    conversationDrafts.current.set(lastDraftId.current, inputRef.current)
    lastDraftId.current = activeId; setInput(conversationDrafts.current.get(activeId) || '')
  }, [activeId])
  useEffect(() => { conversationDrafts.current.clear(); lastDraftId.current = '' }, [projectId, token])

  async function loadList(selectId?: string) {
    const list = await api<Conversation[]>(base + '/conversations', token)
    setConversations(list)
    const chosen = selectId || (list.some((item) => item.id === activeId && item.scope === scope) ? activeId : list.find((item) => item.scope === scope)?.id || '')
    setActiveId(chosen)
    return chosen
  }
  async function loadHistory(id: string, before?: string) {
    const node = messagesRef.current, height = node?.scrollHeight || 0, top = node?.scrollTop || 0
    if (before) olderLoading.current = true
    const page = await api<Page>(`${base}/conversations/${id}/messages?limit=40${before ? `&before=${before}` : ''}`, token)
    if (activeKey.current !== projectId + ':' + id) return
    const conversation = conversations.find(item => item.id === id)
    if (conversation) page.messages.forEach(message => speechSources.current.set(message.id, { projectId, conversationId: id, scope: conversation.scope }))
    setMessages((old) => before ? [...page.messages, ...old] : page.messages)
    setHistoryId(id)
    setCursor(page.nextCursor); setHasMore(page.hasMore)
    if (before) requestAnimationFrame(() => { if (node) node.scrollTop = top + node.scrollHeight - height; olderLoading.current = false })
  }
  function playResponse(message: Message) {
    const source = speechSources.current.get(message.id)
    if (source && source.projectId === projectId && source.conversationId === activeId && source.scope === scope)
      speech.manual(message, source)
  }
  useEffect(() => { speechSources.current.clear() }, [projectId, token])
  useEffect(() => { let alive = true; setConversations([]); setActiveId(''); void api<Conversation[]>(base + '/conversations', token).then((list) => { if (!alive) return; setConversations(list); const selected = navigationHidden ? list.find(item => item.id === selectedConversationId) : list.find((item) => item.scope === scope); if (selected) setScope(selected.scope); setActiveId(selected?.id || '') }).catch((e) => { if (alive) setError(e.message) }); return () => { alive = false } }, [base, token, selectedConversationId, navigationHidden])
  useEffect(() => {
    const selected = conversations.find((item) => item.id === activeId)
    if (!navigationHidden && (!selected || selected.scope !== scope)) setActiveId(conversations.find((item) => item.scope === scope)?.id || '')
  }, [scope, conversations, activeId, navigationHidden])
  useEffect(() => { setMessages([]); setError(''); setCursor(null); setHasMore(false); nearBottom.current = true; olderLoading.current = false; if (activeId) void loadHistory(activeId).catch((e) => { if (activeKey.current === projectId + ':' + activeId) setError(e.message) }); else setMessages([]) }, [activeId, base, token])
  useEffect(() => { if (!nearBottom.current || olderLoading.current) return; const node = messagesRef.current; if (node) node.scrollTop = node.scrollHeight }, [messageHistory, historyId])
  useEffect(() => { setMemory(null); setMemoryOpen(false); setInput(''); setDraft({ systemName: '', systemDescription: '', businessDomain: '', objectives: '', additionalContext: '' }); setRule(''); setContextSaved(false) }, [projectId, token])
  useEffect(() => {
    function onMessage(event: Event) {
      const frame = (event as CustomEvent<{ projectId?: string; conversationId?: string; message?: Message }>).detail
      if (frame?.projectId !== projectId) return
      if (frame?.conversationId && frame.message) speechSources.current.set(frame.message.id, { projectId, conversationId: frame.conversationId, scope: 'GROUP' })
      if (scope === 'GROUP' && frame?.conversationId === activeId && frame.message && historyId === activeId) setMessages((old) => old.some((item) => item.id === frame.message!.id) ? old.map(item => item.id === frame.message!.id ? frame.message! : item) : [...old, frame.message!])
      if (frame?.conversationId && !conversations.some(item => item.id === frame.conversationId)) void loadList().catch(() => undefined)
    }
    window.addEventListener('case:ai-message', onMessage)
    return () => window.removeEventListener('case:ai-message', onMessage)
  }, [activeId, scope, conversations, base, token, historyId])
  async function createConversation() {
    setBusy(true); setError('')
    try { const created = await api<Conversation>(base + '/conversations', token, { method: 'POST', body: JSON.stringify({ scope, diagramId, title: scope === 'GROUP' ? 'Conversación grupal' : 'New Chat' }) }); await loadList(created.id) }
    catch (e) { setError(e instanceof Error ? e.message : 'No se pudo crear la conversación.') } finally { setBusy(false) }
  }
  async function send(event: FormEvent) {
    event.preventDefault(); const content = input.trim(); if (!content || !activeId || busy || !conversationReady) return
    const clientMessageId = crypto.randomUUID(); setBusy(true); setError(''); setInput(''); nearBottom.current = true
    const optimistic: Message = { id: clientMessageId, clientMessageId, senderType: 'USER', senderName: currentUser.name, recipientName: null, content, status: 'PENDING', errorCode: null, createdAt: new Date().toISOString(), replyToMessageId: null }
    setMessages((old) => [...old, optimistic])
    try {
      const turn = await api<{ question: Message; answer: Message | null; proposalId?: string | null }>(`${base}/conversations/${activeId}/messages`, token, { method: 'POST', body: JSON.stringify({ clientMessageId, content, comparisonVersionIds: comparisonVersionIds.slice(0, 2) }) })
      if (activeKey.current !== projectId + ':' + activeId) return
      if (turn.answer) speechSources.current.set(turn.answer.id, { projectId, conversationId: activeId, scope })
      if (turn.proposalId && scope === 'PRIVATE') window.dispatchEvent(new CustomEvent('case:canvas-private-proposal', { detail: { projectId, diagramId, proposalId: turn.proposalId } }))
      setMessages((old) => [...old.filter((item) => item.id !== optimistic.id && item.id !== turn.question.id && (!turn.answer || item.id !== turn.answer.id)), turn.question, ...(turn.answer ? [turn.answer] : [])])
    } catch (e) { if (activeKey.current !== projectId + ':' + activeId) return; setMessages((old) => old.map((item) => item.id === clientMessageId ? { ...item, status: 'ERROR', errorCode: 'REQUEST_FAILED' } : item)); setError(e instanceof Error ? e.message : 'No se pudo consultar al agente.') }
    finally { setBusy(false) }
  }
  async function retry(message: Message) { setBusy(true); setError(''); try { const turn = await api<{ question: Message; answer: Message | null }>(`${base}/conversations/${activeId}/messages/${message.id}/retry`, token, { method: 'POST', body: JSON.stringify({ comparisonVersionIds: comparisonVersionIds.slice(0, 2) }) }); if (activeKey.current !== projectId + ':' + activeId) return; if (turn.answer) speechSources.current.set(turn.answer.id, { projectId, conversationId: activeId, scope }); setMessages((old) => [...old.filter((item) => item.id !== turn.question.id && (!turn.answer || item.id !== turn.answer.id)), turn.question, ...(turn.answer ? [turn.answer] : [])]) } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo reintentar.') } finally { setBusy(false) } }
  async function rename(item: Conversation) { const title = window.prompt('Nombre de la conversación', item.title)?.trim(); if (!title || title === item.title) return; try { await api(`${base}/conversations/${item.id}`, token, { method: 'PATCH', body: JSON.stringify({ title }) }); await loadList(item.id) } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo renombrar.') } }
  async function archive(item: Conversation) { if (!window.confirm(`¿Archivar “${item.title}”?`)) return; try { await api(`${base}/conversations/${item.id}/archive`, token, { method: 'POST' }); await loadList() } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo archivar.') } }
  async function loadMemory() { const result = await api<Memory>(`${base}/memory${diagramId ? `?diagramId=${diagramId}` : ''}`, token); setMemory(result); setDraft({ systemName: result.projectMemory?.systemName || '', systemDescription: result.projectMemory?.systemDescription || '', businessDomain: result.projectMemory?.businessDomain || '', objectives: result.projectMemory?.objectives || '', additionalContext: result.projectMemory?.additionalContext || '' }) }
  async function saveMemory(event: FormEvent) { event.preventDefault(); setContextSaving(true); setContextSaved(false); setError(''); try { await api(`${base}/memory`, token, { method: 'PUT', body: JSON.stringify(draft) }); await loadMemory(); setContextSaved(true) } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar el contexto.') } finally { setContextSaving(false) } }
  async function saveRule(event?: FormEvent) { event?.preventDefault(); if (!rule.trim()) return; try { await api(`${base}/memory/entries`, token, { method: 'POST', body: JSON.stringify({ entryType: 'BUSINESS_RULE', diagramId, content: rule.trim() }) }); setRule(''); await loadMemory() } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo agregar la regla.') } }
  async function editEntry(entry: NonNullable<Memory['entries']>[number]) {
    const content = window.prompt(entry.confidenceScope === 'INFERRED' ? 'Revisa y confirma/corrige esta hipótesis' : 'Editar contexto confirmado', entry.content)?.trim()
    if (!content || !canEditSharedContext(currentUser.role)) return
    try { await api(`${base}/memory/entries/${entry.id}`, token, { method: 'PUT', body: JSON.stringify({ entryType: entry.entryType, diagramId: entry.diagramId, content }) }); await loadMemory() }
    catch (e) { setError(e instanceof Error ? e.message : 'No se pudo actualizar el contexto.') }
  }
  async function toggleMemory() { const opening = !memoryOpen; setMemoryOpen(opening); if (opening) try { await loadMemory() } catch (e) { setError(e instanceof Error ? e.message : 'No se pudo cargar la memoria.') } }
  async function saveDictatedContext(text: string) {
    await api(`${base}/memory`, token, { method: 'PUT', body: JSON.stringify({ ...draft, systemDescription: text }) })
    await loadMemory(); setContextSaved(true)
  }

  return <div className={'ai-conversations' + (chatOnly ? ' ai-chat-only' : '')}>
    {!chatOnly && diagramId && <UmlProposalsPanel projectId={projectId} diagramId={diagramId} conversationId={activeId} token={token} role={currentUser.role} active={active} refreshKey={messages.map(message => message.id + message.status).join(':')} />}
    {!chatOnly && <AiProactivePanel projectId={projectId} diagramId={diagramId} token={token} role={currentUser.role} active={active} onDescribe={() => { setMemoryOpen(true); void loadMemory().catch(e => setError(e.message)) }} onOpenConversation={(id) => { setScope('GROUP'); void loadList(id).catch(e => setError(e.message)) }} />}
    {!navigationHidden && <div className="ai-scope-tabs">{chatOnly ? <select aria-label="Ámbito de conversación" value={scope} onChange={e => setScope(e.target.value as Scope)}><option value="GROUP">Historiales grupales</option><option value="PRIVATE">Chats privados</option></select> : <><button className={scope === 'GROUP' ? 'active' : ''} onClick={() => setScope('GROUP')}>Grupales</button><button className={scope === 'PRIVATE' ? 'active' : ''} onClick={() => setScope('PRIVATE')}>Privadas</button></>}<button className="ai-new" disabled={busy} onClick={() => void createConversation()}>＋ Nuevo chat</button></div>}
    {chatOnly && !navigationHidden && <div className="ai-chat-picker"><select aria-label="Conversación de COIN" value={activeId} onChange={e => setActiveId(e.target.value)}><option value="" disabled>Selecciona una conversación</option>{visibleConversations.map(item => <option key={item.id} value={item.id}>{item.title} · {new Date(item.updatedAt).toLocaleDateString('es-BO')}</option>)}</select><button disabled={!activeId} onClick={() => { const item=conversations.find(c=>c.id===activeId);if(item)void rename(item) }}>Renombrar</button><button disabled={!activeId} onClick={() => { const item=conversations.find(c=>c.id===activeId);if(item)void archive(item) }}>Archivar</button></div>}
    <div className="ai-conversation-layout">{!chatOnly && <nav className="ai-conversation-list">{visibleConversations.map((item) => <div className={'ai-conversation-item' + (activeId === item.id ? ' active' : '')} key={item.id}><button onClick={() => setActiveId(item.id)}>{item.title}<small>{new Intl.DateTimeFormat('es-BO', { dateStyle: 'short' }).format(new Date(item.updatedAt))}</small></button><button title="Renombrar" onClick={() => void rename(item)}>⋯</button><button title="Archivar" onClick={() => void archive(item)}>×</button></div>)}{visibleConversations.length === 0 && <p className="ai-empty">Crea una conversación para empezar. No consume IA hasta enviar un mensaje.</p>}</nav>}
      <section className="ai-conversation-main"><div className="ai-context-toolbar"><span>{navigationHidden ? (selectedConversationTitle || conversations.find(item => item.id === activeId)?.title || 'COIN') : scope === 'GROUP' ? 'Visible para miembros autorizados' : 'Privada para ti'}</span>{!chatOnly && <button onClick={() => void toggleMemory()}>{memoryOpen ? 'Cerrar contexto' : 'Contexto del proyecto'}</button>}</div>
        {memoryOpen && <form className="ai-memory-editor" onSubmit={(e) => void saveMemory(e)}><strong>Memoria compartida confirmada</strong><AiContextDictation disabled={!canEditSharedContext(currentUser.role)} active={active && memoryOpen} onConfirm={saveDictatedContext} />{(['systemName', 'systemDescription', 'businessDomain', 'objectives', 'additionalContext'] as const).map((key) => <label key={key}>{({ systemName: 'Nombre del sistema', systemDescription: 'Descripción', businessDomain: 'Dominio', objectives: 'Objetivos', additionalContext: 'Información adicional' })[key]}<textarea value={draft[key]} maxLength={key === 'systemName' ? 160 : key === 'businessDomain' ? 1000 : key === 'systemDescription' ? 4000 : 12000} rows={key === 'systemName' ? 1 : 2} onChange={(e) => setDraft({ ...draft, [key]: e.target.value })} disabled={!canEditSharedContext(currentUser.role)} /></label>)}<button disabled={contextSaving || !canEditSharedContext(currentUser.role)}>{contextSaving ? 'Guardando…' : contextSaved ? 'Guardado' : 'Guardar contexto'}</button>{memory?.entries.map((entry) => <p key={entry.id}><strong>{entryLabels[entry.entryType] || 'Contexto'} · {entry.confidenceScope === 'INFERRED' ? 'Hipótesis por confirmar' : 'Confirmado'}</strong> · {entry.content} <button type="button" disabled={!canEditSharedContext(currentUser.role)} onClick={() => void editEntry(entry)}>{entry.confidenceScope === 'INFERRED' ? 'Revisar y confirmar' : 'Editar'}</button></p>)}<label>Agregar regla confirmada<textarea value={rule} onChange={(e) => setRule(e.target.value)} maxLength={4000} /></label><button type="button" disabled={!rule.trim() || !canEditSharedContext(currentUser.role)} onClick={() => void saveRule()}>Agregar regla</button></form>}
        <div className="ai-conversation-messages" ref={messagesRef} onScroll={() => { const node = messagesRef.current; if (node) nearBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 80 }}>{hasMore && <button className="ai-load-more" onClick={() => cursor && activeId && !olderLoading.current && void loadHistory(activeId, cursor).catch(e => { olderLoading.current = false; setError(e.message) })}>Cargar mensajes anteriores</button>}{messages.map((message) => <article key={message.id} className={'ai-message ' + (message.senderType === 'USER' ? 'own' : 'agent')}><header><strong>{message.senderType === 'AGENT' ? `COIN · ${message.recipientName || 'Equipo'}` : message.senderName}</strong><time>{new Intl.DateTimeFormat('es-BO', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: 'short' }).format(new Date(message.createdAt))}</time></header><ExpandableMessage content={message.content} />{message.senderType === 'AGENT' && message.status === 'COMPLETED' && <button type="button" disabled={!speech.state.available || speech.state.microphoneActive || !speechSources.current.has(message.id) || (scope === 'PRIVATE' && speech.state.roomConnected)} title={scope === 'PRIVATE' ? 'Lectura privada local; sal de la sala para reproducirla.' : 'Reproducir localmente esta respuesta escrita'} onClick={() => playResponse(message)}>Reproducir respuesta</button>}{message.status === 'PENDING' && <small className="ai-pending-status" role="status" aria-live="polite"><span className="ai-pending-spinner" aria-hidden="true" />Solicitud en curso. La inferencia local se procesa en cola para proteger la memoria; Groq puede respaldarla si está habilitado.</small>}{message.status === 'ERROR' && <><small role="alert">No se obtuvo respuesta ({message.errorCode || 'error'}).</small><button disabled={busy} onClick={() => void retry(message)}>Reintentar</button></>}</article>)}{!activeId && <p className="ai-empty">Selecciona o crea una conversación.</p>}</div>
        {error && <div className="ai-error" role="alert">{error}<button onClick={() => setError('')}>×</button></div>}<form className="ai-compose" onSubmit={(e) => void send(e)}><textarea value={conversationReady ? input : ''} onChange={(e) => setInput(e.target.value)} maxLength={8000} rows={2} disabled={!activeId || busy || !conversationReady} placeholder="Pregunta sobre el sistema o el diagrama…" /><button disabled={!activeId || !input.trim() || busy || !conversationReady}>{busy ? 'Consultando…' : 'Enviar'}</button></form>
      </section></div>
  </div>
}
