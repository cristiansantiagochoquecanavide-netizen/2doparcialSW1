/** Browser-only playback. No requests, recording, WebRTC tracks or UML actions. */
export function spanishLocalVoice(voices) {
  const spanish = voices.filter(voice => voice.localService === true && /^es(?:[-_]|$)/i.test(voice.lang))
  return spanish.find(voice => /^es[-_]BO$/i.test(voice.lang)) || spanish.find(voice => voice.default) || spanish[0] || null
}

export function spokenText(content, limit = 8000) {
  return typeof content === 'string' ? content.replace(/```[\s\S]*?```/g, ' Código disponible en el mensaje escrito. ')
    .replace(/https?:\/\/\S+/g, '').replace(/[*_`#]/g, '').replace(/\s+/g, ' ').trim().slice(0, limit) : ''
}

export function createSpeechPreferences() {
  const users = new Map()
  return {
    read(user, session) { const entry = users.get(user); return entry?.session === session ? { ...entry } : null },
    write(user, session, state) {
      users.set(user, { session, automatic: state.automatic, rate: state.rate, volume: state.volume })
      if (users.size > 8) users.delete(users.keys().next().value)
    }
  }
}

export function createAgentSpeech({ synthesis, Utterance, projectId, now = Date.now, onState = () => {} }) {
  let disposed = false, automatic = false, roomConnected = false, chatConnected = false, microphoneActive = false
  let enabledAt = Infinity, roomSince = Infinity, chatSince = Infinity, latestAt = 0
  let rate = 1, volume = 0.7, current = null, pending = null, generation = 0, error = ''
  let view = { conversationId: '', scope: 'GROUP', active: false }
  const seen = new Set()
  const supported = Boolean(synthesis && Utterance)
  const voice = () => { try { return spanishLocalVoice(synthesis?.getVoices() || []) } catch { return null } }
  const state = () => ({ supported, available: supported && Boolean(voice()), automatic, speaking: Boolean(current?.started),
    busy: Boolean(current), pending: Boolean(pending), roomConnected, microphoneActive, rate, volume, error })
  const emit = () => { if (!disposed) onState(state()) }
  function remember(id) { seen.add(id); if (seen.size > 512) seen.delete(seen.values().next().value) }
  function cancel() {
    generation++; pending = null
    if (current) { current = null; try { synthesis.cancel() } catch { /* Browser teardown. */ } }
    emit()
  }
  function automaticAllowed() { return automatic && roomConnected && chatConnected && !microphoneActive }
  function nextChunk() {
    const item = current
    if (!item || disposed) return
    if (!item.chunks.length) {
      current = null; emit()
      const next = pending; pending = null
      if (next && automaticAllowed() && now() - next.createdAt <= 30000) start(next)
      return
    }
    const selected = voice()
    if (!selected) { error = 'No hay una voz local en español disponible. Instala una voz española en el sistema o continúa leyendo el chat.'; cancel(); return }
    const token = generation
    try {
      const utterance = new Utterance(item.chunks.shift())
      utterance.voice = selected; utterance.lang = selected.lang || 'es-BO'; utterance.rate = rate; utterance.volume = volume
      utterance.onstart = () => { if (!disposed && token === generation && current === item) { item.started = true; emit() } }
      utterance.onend = () => { if (!disposed && token === generation && current === item) nextChunk() }
      utterance.onerror = event => {
        if (disposed || token !== generation || current !== item) return
        const blocked = event.error === 'not-allowed'
        if (blocked) automatic = false
        error = blocked ? 'El navegador bloqueó la voz. Usa «Reproducir respuesta» y vuelve a activar la lectura automática si lo deseas.'
          : ['canceled','interrupted'].includes(event.error) ? '' : 'No se pudo reproducir la voz. Puedes volver a intentarlo manualmente.'
        cancel()
      }
      synthesis.speak(utterance); emit()
    } catch { error = 'No se pudo iniciar la síntesis de voz. Prueba reproducir manualmente.'; cancel() }
  }
  function start(item) {
    error = ''; generation++
    const text = spokenText(item.content, item.manual ? 8000 : 700)
    if (!text) return false
    // Only one utterance is submitted at a time, even for long manual responses.
    const chunks = text.match(/.{1,450}(?:\s|$)|\S{1,450}/g) || [text]
    current = { ...item, chunks, started: false }; nextChunk(); return Boolean(current)
  }
  const voicesChanged = () => emit()
  synthesis?.addEventListener?.('voiceschanged', voicesChanged)
  const api = {
    state,
    setAutomatic(value) {
      if (disposed) return
      automatic = Boolean(value && supported); enabledAt = now(); error = ''
      if (!automatic) cancel()
      else { try { synthesis.resume?.() } catch { /* Manual playback remains available. */ } }
      emit()
    },
    configure(options) {
      if (disposed) return
      if (Number.isFinite(options.rate)) rate = Math.max(0.5, Math.min(1.5, options.rate))
      if (Number.isFinite(options.volume)) volume = Math.max(0, Math.min(1, options.volume))
      emit()
    },
    session({ room, chat, mic = false }) {
      if (disposed) return
      if (room !== roomConnected) { roomSince = room ? now() : Infinity; cancel() }
      if (chat !== chatConnected) { chatSince = chat ? now() : Infinity; cancel() }
      if (mic && !microphoneActive) cancel()
      roomConnected = room; chatConnected = chat; microphoneActive = mic; emit()
    },
    view(next) {
      if (disposed) return
      if (current?.manual && (next.conversationId !== view.conversationId || next.scope !== view.scope || !next.active)) cancel()
      view = next
    },
    receive(frame) {
      if (disposed || frame?.type !== 'AI_CONVERSATION_MESSAGE' || frame.projectId !== projectId || !frame.conversationId) return false
      const message = frame.message
      // Only server group broadcasts representing proactive recommendations have no recipient/reply.
      if (!message?.id || message.senderType !== 'AGENT' || message.status !== 'COMPLETED' || message.senderUserId !== null
          || message.recipientUserId !== null || message.replyToMessageId !== null || frame.scope === 'PRIVATE') return false
      if (seen.has(message.id)) return false
      remember(message.id)
      const createdAt = Date.parse(message.createdAt)
      if (!automaticAllowed() || !Number.isFinite(createdAt) || createdAt <= Math.max(enabledAt, roomSince, chatSince)
          || now() - createdAt > 30000 || createdAt > now() + 5000 || createdAt < latestAt) return false
      latestAt = createdAt
      const item = { id: message.id, content: message.content, createdAt, manual: false }
      if (current) { pending = item; emit(); return true }
      return start(item)
    },
    manual(message, source) {
      if (disposed) return false
      if (!supported) { error = 'Este navegador no admite síntesis de voz.'; emit(); return false }
      if (!view.active || !source.conversationId || source.projectId !== projectId || source.conversationId !== view.conversationId || source.scope !== view.scope
          || message.senderType !== 'AGENT' || message.status !== 'COMPLETED' || microphoneActive || (source.scope === 'PRIVATE' && roomConnected)) {
        error = 'Abre la conversación autorizada. Para leer una respuesta privada, sal primero de la sala de voz.'; emit(); return false
      }
      cancel(); remember(message.id)
      return start({ id: message.id, content: message.content, manual: true, createdAt: now() })
    },
    stop() { error = ''; cancel() },
    dispose() { cancel(); disposed = true; synthesis?.removeEventListener?.('voiceschanged', voicesChanged) }
  }
  emit(); return api
}
