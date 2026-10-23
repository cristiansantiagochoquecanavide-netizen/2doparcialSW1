import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useAgentSpeech } from './AgentSpeechUI'

type ConnectionState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed'
type Participant = { participantId: string; name: string; muted: boolean; connectionState: ConnectionState | 'waiting' }
export type VoiceRoomPresence = { projectId: string; roomId: string; serverId: string; revision: number; maxParticipants: number; participants: Participant[] }
type IceServerConfig = { urls: string | string[]; username?: string; credential?: string }
type PeerState = {
  pc: RTCPeerConnection
  polite: boolean
  makingOffer: boolean
  ignoreOffer: boolean
  isSettingRemoteAnswerPending: boolean
  pendingCandidates: RTCIceCandidateInit[]
}
type VoiceEvent = {
  type?: string; code?: string; message?: string; participantId?: string; name?: string
  participants?: Participant[]; participant?: Participant; fromParticipantId?: string; fromName?: string
  muted?: boolean; description?: RTCSessionDescriptionInit; candidate?: RTCIceCandidateInit
  maxParticipants?: number; iceServers?: IceServerConfig[]
}
type Props = { projectId: string; token: string; currentUserName: string; onBackToChat: () => void; roomId?: string; roomName?: string }

const apiBase = import.meta.env.VITE_API_BASE_URL || ''

function friendlyMediaError(error: unknown) {
  const name = error instanceof DOMException ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'El navegador denegó el micrófono. Revisa los permisos del sitio y vuelve a intentarlo.'
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return 'No se encontró un micrófono disponible.'
  if (name === 'NotReadableError' || name === 'TrackStartError') return 'El micrófono está ocupado o no se puede iniciar.'
  if (!navigator.mediaDevices?.getUserMedia) return 'Este navegador no permite usar el micrófono aquí. Usa localhost o una conexión HTTPS.'
  return 'No se pudo iniciar el micrófono. Comprueba el dispositivo y sus permisos.'
}

export function VoiceRoom({ projectId, token, currentUserName, onBackToChat, roomId, roomName = 'Sala de diseño' }: Props) {
  const speech = useAgentSpeech()
  const [status, setStatus] = useState<'idle' | 'requesting' | 'connecting' | 'connected' | 'reconnecting' | 'error'>('idle')
  const [muted, setMuted] = useState(true)
  const [participants, setParticipants] = useState<Participant[]>([])
  const [errorText, setErrorText] = useState('')
  const [playbackBlocked, setPlaybackBlocked] = useState(false)
  const [volume, setVolume] = useState(1)
  const [limit, setLimit] = useState(6)
  const [observedPresence, setObservedPresence] = useState<VoiceRoomPresence | null>(null)
  const [presenceError, setPresenceError] = useState('')
  const [joinedBefore, setJoinedBefore] = useState(false)
  const socketRef = useRef<WebSocket | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const peersRef = useRef(new Map<string, PeerState>())
  const audioHostRef = useRef<HTMLDivElement>(null)
  const audioElementsRef = useRef(new Map<string, HTMLAudioElement>())
  const participantIdRef = useRef('')
  const ownNameRef = useRef(currentUserName)
  const iceServersRef = useRef<IceServerConfig[]>([])
  const volumeRef = useRef(volume)
  const connectSocketRef = useRef<(generation: number) => void>(() => undefined)
  const stopSessionRef = useRef<(keepError?: boolean) => void>(() => undefined)
  const desiredRef = useRef(false)
  const generationRef = useRef(0)
  const retryTimerRef = useRef<number | undefined>(undefined)
  const retryDelayRef = useRef(1000)
  const retryAttemptsRef = useRef(0)
  const statusRef = useRef(status)
  statusRef.current = status
  ownNameRef.current = currentUserName
  useEffect(() => { speech.room(status === 'connected', !muted) }, [status, muted, speech.room])
  useEffect(() => () => speech.room(false, false), [speech.room])

  // Observe the existing server room without requesting media or opening signaling.
  useEffect(() => {
    let active = true, requestNumber = 0
    let current: VoiceRoomPresence | null = null
    let controller: AbortController | undefined
    setObservedPresence(null); setPresenceError('')
    if (!roomId) return
    function apply(next: VoiceRoomPresence) {
      if (!active || next.projectId !== projectId || next.roomId !== roomId || !Array.isArray(next.participants)) return
      if (current?.serverId === next.serverId && current.revision > next.revision) return
      current = next; setObservedPresence(next); setLimit(next.maxParticipants); setPresenceError('')
    }
    async function refresh() {
      const request = ++requestNumber
      controller?.abort(); controller = new AbortController()
      try {
        const response = await fetch(`${apiBase}/api/projects/${projectId}/chat/voice-rooms/${roomId}/participants`, {
          headers: { Authorization: 'Bearer ' + token }, signal: controller.signal
        })
        if (!response.ok) throw new Error('No se pudo consultar la presencia de la sala.')
        const next = await response.json() as VoiceRoomPresence
        if (request === requestNumber) apply(next)
      } catch (error) {
        if (active && request === requestNumber && !(error instanceof DOMException && error.name === 'AbortError')) {
          setPresenceError('No se pudo actualizar la lista de participantes.'); setObservedPresence(null)
        }
      }
    }
    function onPresence(event: Event) {
      const next = (event as CustomEvent<VoiceRoomPresence>).detail
      if (next?.projectId !== projectId || next.roomId !== roomId) return
      ++requestNumber; controller?.abort(); apply(next)
    }
    function onConnection(event: Event) {
      const state = (event as CustomEvent<{ projectId: string; connected: boolean }>).detail
      if (state?.projectId !== projectId) return
      if (state.connected) void refresh()
      else { ++requestNumber; controller?.abort(); current = null; setObservedPresence(null) }
    }
    window.addEventListener('case:voice-presence', onPresence)
    window.addEventListener('case:chat-connection', onConnection)
    void refresh()
    return () => {
      active = false; ++requestNumber; controller?.abort()
      window.removeEventListener('case:voice-presence', onPresence)
      window.removeEventListener('case:chat-connection', onConnection)
    }
  }, [projectId, roomId, token])

  const updateParticipant = useCallback((participantId: string, update: Partial<Participant>) => {
    setParticipants((current) => current.map((participant) => participant.participantId === participantId ? { ...participant, ...update } : participant))
  }, [])

  const sendSignal = useCallback((frame: Record<string, unknown>) => {
    const socket = socketRef.current
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(frame))
  }, [])

  const removePeer = useCallback((participantId: string) => {
    const peer = peersRef.current.get(participantId)
    if (peer) {
      peer.pc.onicecandidate = null
      peer.pc.ontrack = null
      peer.pc.onnegotiationneeded = null
      peer.pc.onconnectionstatechange = null
      peer.pc.close()
      peersRef.current.delete(participantId)
    }
    const audio = audioElementsRef.current.get(participantId)
    if (audio) {
      audio.pause()
      audio.srcObject = null
      audio.remove()
      audioElementsRef.current.delete(participantId)
    }
  }, [])

  const clearPeers = useCallback(() => {
    for (const id of [...peersRef.current.keys()]) removePeer(id)
    setParticipants([])
  }, [removePeer])

  const makePeer = useCallback((participant: Participant): PeerState => {
    const existing = peersRef.current.get(participant.participantId)
    if (existing) return existing
    const ownId = participantIdRef.current
    const pc = new RTCPeerConnection({ iceServers: iceServersRef.current })
    const peer: PeerState = {
      pc,
      polite: ownId.localeCompare(participant.participantId) > 0,
      makingOffer: false,
      ignoreOffer: false,
      isSettingRemoteAnswerPending: false,
      pendingCandidates: []
    }
    peersRef.current.set(participant.participantId, peer)
    for (const track of streamRef.current?.getAudioTracks() || []) pc.addTrack(track, streamRef.current as MediaStream)
    pc.onicecandidate = (event) => {
      if (event.candidate) sendSignal({ type: 'VOICE_ICE_CANDIDATE', toParticipantId: participant.participantId, candidate: event.candidate.toJSON() })
    }
    pc.ontrack = (event) => {
      const stream = event.streams[0] || new MediaStream([event.track])
      let audio = audioElementsRef.current.get(participant.participantId)
      if (!audio) {
        audio = document.createElement('audio')
        audio.autoplay = true
        audio.setAttribute('playsinline', '')
        audio.dataset.participantId = participant.participantId
        audio.volume = volumeRef.current
        audioHostRef.current?.appendChild(audio)
        audioElementsRef.current.set(participant.participantId, audio)
      }
      audio.srcObject = stream
      void audio.play().then(() => setPlaybackBlocked(false)).catch(() => setPlaybackBlocked(true))
    }
    pc.onconnectionstatechange = () => {
      updateParticipant(participant.participantId, { connectionState: pc.connectionState as ConnectionState })
      if (pc.connectionState === 'failed') {
        try { pc.restartIce() } catch { setErrorText('Falló una conexión de audio. Intenta salir y volver a la sala.') }
      }
    }
    pc.onnegotiationneeded = async () => {
      try {
        peer.makingOffer = true
        await pc.setLocalDescription()
        if (pc.localDescription) sendSignal({ type: pc.localDescription.type === 'offer' ? 'VOICE_OFFER' : 'VOICE_ANSWER', toParticipantId: participant.participantId, description: pc.localDescription })
      } catch {
        setErrorText('No se pudo negociar una conexión de audio con ' + participant.name + '.')
      } finally { peer.makingOffer = false }
    }
    return peer
  }, [sendSignal, updateParticipant])

  const receiveDescription = useCallback(async (event: VoiceEvent) => {
    if (!event.fromParticipantId || !event.description) return
    const participant: Participant = {
      participantId: event.fromParticipantId, name: event.fromName || 'Colaborador', muted: true, connectionState: 'connecting'
    }
    setParticipants((current) => current.some((item) => item.participantId === participant.participantId) ? current : [...current, participant])
    const peer = makePeer(participant)
    const pc = peer.pc
    const readyForOffer = !peer.makingOffer && (pc.signalingState === 'stable' || peer.isSettingRemoteAnswerPending)
    const offerCollision = event.description.type === 'offer' && !readyForOffer
    peer.ignoreOffer = !peer.polite && offerCollision
    if (peer.ignoreOffer) return
    try {
      peer.isSettingRemoteAnswerPending = event.description.type === 'answer'
      await pc.setRemoteDescription(event.description)
      peer.isSettingRemoteAnswerPending = false
      for (const candidate of peer.pendingCandidates.splice(0)) await pc.addIceCandidate(candidate)
      if (event.description.type === 'offer') {
        await pc.setLocalDescription()
        if (pc.localDescription) sendSignal({ type: 'VOICE_ANSWER', toParticipantId: participant.participantId, description: pc.localDescription })
      }
    } catch {
      if (!peer.ignoreOffer) setErrorText('No se pudo procesar la señalización WebRTC recibida.')
    }
  }, [makePeer, sendSignal])

  const connectSocket = useCallback((generation: number) => {
    if (!desiredRef.current || generation !== generationRef.current) return
    const url = new URL(apiBase || window.location.origin)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.pathname = '/ws/projects/' + projectId + '/voice'
    url.search = ''
    const socket = new WebSocket(url.toString(), ['case-events-v1', 'bearer.' + token])
    socketRef.current = socket
    setStatus(participantIdRef.current ? 'reconnecting' : 'connecting')
    socket.onopen = () => { retryDelayRef.current = 1000 }
    socket.onmessage = (message) => {
      let event: VoiceEvent
      try { event = JSON.parse(String(message.data)) as VoiceEvent } catch { return }
      if (event.type === 'VOICE_READY') {
        const reconnecting = participantIdRef.current !== ''
        if (reconnecting) clearPeers()
        participantIdRef.current = event.participantId || ''
        iceServersRef.current = event.iceServers || []
        setLimit(event.maxParticipants || 6)
        ownNameRef.current = event.name || currentUserName
        if (desiredRef.current) sendSignal({ type: 'VOICE_JOIN', ...(roomId ? { roomId } : {}) })
      } else if (event.type === 'VOICE_JOINED') {
        retryAttemptsRef.current = 0
        const self: Participant = { participantId: event.participantId || participantIdRef.current, name: ownNameRef.current, muted: true, connectionState: 'connected' }
        const others = event.participants || []
        setParticipants([self, ...others])
        for (const participant of others) makePeer(participant)
        setStatus('connected'); setJoinedBefore(true); setErrorText('')
      } else if (event.type === 'VOICE_PARTICIPANT_JOINED' && event.participant) {
        setParticipants((current) => current.some((participant) => participant.participantId === event.participant?.participantId) ? current : [...current, event.participant as Participant])
        makePeer(event.participant)
      } else if (event.type === 'VOICE_PARTICIPANT_LEFT' && event.participantId) {
        removePeer(event.participantId)
        setParticipants((current) => current.filter((participant) => participant.participantId !== event.participantId))
      } else if (event.type === 'VOICE_MUTE_STATE' && event.participantId && typeof event.muted === 'boolean') {
        updateParticipant(event.participantId, { muted: event.muted })
      } else if (event.type === 'VOICE_OFFER' || event.type === 'VOICE_ANSWER') {
        void receiveDescription(event)
      } else if (event.type === 'VOICE_ICE_CANDIDATE' && event.fromParticipantId && event.candidate) {
        const participant: Participant = { participantId: event.fromParticipantId, name: event.fromName || 'Colaborador', muted: true, connectionState: 'connecting' }
        setParticipants((current) => current.some((item) => item.participantId === participant.participantId) ? current : [...current, participant])
        const peer = makePeer(participant)
        if (peer.pc.remoteDescription) void peer.pc.addIceCandidate(event.candidate).catch(() => { if (!peer.ignoreOffer) setErrorText('No se pudo añadir un candidato ICE.') })
        else peer.pendingCandidates.push(event.candidate)
      } else if (event.type === 'VOICE_ROOM_DELETED') {
        setErrorText(event.message || 'Esta sala fue eliminada.'); stopSessionRef.current(true)
      } else if (event.type === 'VOICE_ERROR') {
        setErrorText(event.message || 'El servidor rechazó una operación de voz.')
        if (event.code === 'ROOM_FULL' || event.code === 'NOT_IN_ROOM' || event.code === 'ROOM_UNAVAILABLE') stopSessionRef.current(true)
      }
    }
    socket.onclose = (event) => {
      if (socketRef.current === socket) socketRef.current = null
      if (!desiredRef.current || generation !== generationRef.current) return
      if (event.code === 4004) { setErrorText('Esta sala fue eliminada.'); stopSessionRef.current(true); return }
      if (event.code === 1008) {
        setErrorText('Tu acceso al proyecto expiró o fue revocado.'); stopSession(true); return
      }
      clearPeers()
      retryAttemptsRef.current += 1
      if (retryAttemptsRef.current > 8) {
        setErrorText('No se pudo recuperar la conexión de voz después de varios intentos. Comprueba la red y vuelve a entrar.')
        stopSessionRef.current(true)
        return
      }
      setStatus('reconnecting')
      retryTimerRef.current = window.setTimeout(() => connectSocketRef.current(generation), retryDelayRef.current)
      retryDelayRef.current = Math.min(retryDelayRef.current * 2, 30000)
    }
    socket.onerror = () => socket.close()
  }, [clearPeers, currentUserName, makePeer, projectId, receiveDescription, removePeer, roomId, sendSignal, token, updateParticipant])
  connectSocketRef.current = connectSocket

  const stopSession = useCallback((keepError = false) => {
    desiredRef.current = false
    generationRef.current += 1
    if (retryTimerRef.current !== undefined) window.clearTimeout(retryTimerRef.current)
    retryTimerRef.current = undefined
    const socket = socketRef.current
    socketRef.current = null
    if (socket?.readyState === WebSocket.OPEN) {
      try { socket.send(JSON.stringify({ type: 'VOICE_LEAVE' })) } catch { /* Closing the socket also removes server presence. */ }
    }
    socket?.close()
    clearPeers()
    for (const track of streamRef.current?.getTracks() || []) { track.onended = null; track.stop() }
    streamRef.current = null
    participantIdRef.current = ''
    setMuted(true)
    setPlaybackBlocked(false)
    setJoinedBefore(false)
    setStatus('idle')
    if (!keepError) setErrorText('')
  }, [clearPeers])
  stopSessionRef.current = stopSession

  useEffect(() => {
    volumeRef.current = volume
    for (const audio of audioElementsRef.current.values()) audio.volume = volume
  }, [volume])

  useEffect(() => () => {
    desiredRef.current = false
    generationRef.current += 1
    if (retryTimerRef.current !== undefined) window.clearTimeout(retryTimerRef.current)
    socketRef.current?.close()
    for (const peer of peersRef.current.values()) peer.pc.close()
    peersRef.current.clear()
    for (const audio of audioElementsRef.current.values()) { audio.pause(); audio.srcObject = null; audio.remove() }
    audioElementsRef.current.clear()
    for (const track of streamRef.current?.getTracks() || []) { track.onended = null; track.stop() }
    streamRef.current = null
  }, [projectId, token])

  async function joinRoom() {
    if (statusRef.current === 'requesting' || desiredRef.current) return
    if (typeof RTCPeerConnection === 'undefined') { setStatus('error'); setErrorText('Este navegador no es compatible con WebRTC para llamadas de voz.'); return }
    if (!navigator.mediaDevices?.getUserMedia) { setStatus('error'); setErrorText(friendlyMediaError(null)); return }
    const generation = ++generationRef.current
    setStatus('requesting'); setErrorText('')
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false })
      if (generation !== generationRef.current) { stream.getTracks().forEach((track) => track.stop()); return }
      for (const track of stream.getAudioTracks()) {
        track.enabled = false
        track.onended = () => { setStatus('error'); setErrorText('El micrófono se desconectó. Puedes seleccionar otro dispositivo y volver a entrar.') }
      }
      streamRef.current = stream
      desiredRef.current = true
      retryAttemptsRef.current = 0
      setMuted(true); setStatus('connecting'); setJoinedBefore(false)
      connectSocket(generation)
    } catch (error) {
      const signallingFailed = streamRef.current !== null
      if (signallingFailed) stopSession()
      setStatus('error')
      setErrorText(signallingFailed ? 'No se pudo abrir la conexión de señalización. Revisa el servidor y vuelve a entrar.' : friendlyMediaError(error))
    }
  }

  function toggleMute() {
    if (!streamRef.current) return
    const nextMuted = !muted
    for (const track of streamRef.current.getAudioTracks()) track.enabled = !nextMuted
    setMuted(nextMuted)
    const ownId = participantIdRef.current
    updateParticipant(ownId, { muted: nextMuted })
    sendSignal({ type: 'VOICE_MUTE_STATE', muted: nextMuted })
  }

  function resumePlayback() {
    Promise.all([...audioElementsRef.current.values()].map((audio) => audio.play()))
      .then(() => setPlaybackBlocked(false))
      .catch(() => setErrorText('El navegador todavía bloquea la reproducción. Revisa los permisos de audio del sitio.'))
  }

  const stateLabel = status === 'idle' ? 'Fuera de la sala' : status === 'requesting' ? 'Esperando permiso del micrófono' : status === 'connecting' ? 'Conectando' : status === 'connected' ? 'Conectado' : status === 'reconnecting' ? 'Reconectando' : 'Error de conexión'

  const visibleParticipants = status === 'connected' ? participants : observedPresence?.participants || []
  const presenceKnown = status === 'connected' || observedPresence !== null

  return (
    <section className="voice-room-card" aria-label="Sala de voz del proyecto">
      <header className="voice-room-heading">
        <div><span className="team-section-label">SALA DE VOZ</span><h3>{roomName}</h3></div>
        <span className={'voice-room-state voice-state-' + status}>{stateLabel}</span>
      </header>
      <p className="voice-room-copy">Audio en vivo entre miembros autorizados. Tu micrófono solo se solicita cuando eliges unirte.</p>
      {status === 'idle' || (status === 'error' && !desiredRef.current) ? (
        <button type="button" className="voice-action-btn" onClick={() => void joinRoom()}>{status === 'error' ? 'Reintentar conexión' : 'Unirse a la sala'}</button>
      ) : (
        <div className="voice-controls">
          <button type="button" className={'voice-action-btn ' + (muted ? 'muted' : 'unmuted')} onClick={toggleMute} disabled={status !== 'connected'} aria-pressed={!muted}>
            {muted ? 'Activar micrófono' : 'Silenciar micrófono'}
          </button>
          <button type="button" className="voice-leave-btn" onClick={() => stopSession()}>Salir de la sala</button>
        </div>
      )}
      {status === 'requesting' && <div className="voice-hint">Acepta el permiso de micrófono en el navegador.</div>}
      <div className="voice-participants-heading">Participantes ({presenceKnown ? visibleParticipants.length : '…'} / {limit})</div>
      {visibleParticipants.length === 0 ? <div className="voice-empty">{!presenceKnown ? 'Consultando participantes…' : 'No hay participantes conectados.'}</div> : (
        <ul className="voice-participant-list">{visibleParticipants.map((participant) => (
          <li key={participant.participantId}>
            <span className="team-avatar">{participant.name.slice(0, 1).toUpperCase()}</span>
            <span className="voice-participant-name">{participant.name}{participant.participantId === participantIdRef.current ? ' (tú)' : ''}</span>
            <span className="voice-participant-state" title={participant.connectionState}>{participant.connectionState === 'connected' ? 'Conectado' : 'Conectando'}</span>
            <span className={'voice-mic-indicator ' + (participant.muted ? 'is-muted' : '')} title={participant.muted ? 'Micrófono silenciado' : 'Micrófono activo'}>{participant.muted ? 'Silenciado' : 'Micrófono activo'}</span>
          </li>
        ))}</ul>
      )}
      {status === 'connected' && participants.some((participant) => participant.participantId !== participantIdRef.current) && (
        <label className="voice-volume-control">Volumen de salida <input type="range" min="0" max="1" step="0.05" value={volume} onChange={(event) => setVolume(Number(event.target.value))} /></label>
      )}
      {playbackBlocked && <button type="button" className="voice-back-btn" onClick={resumePlayback}>Activar audio de participantes</button>}
      {errorText && <div className="voice-error" role="alert">{errorText}</div>}
      {presenceError && status !== 'connected' && <div className="voice-error" role="alert">{presenceError}</div>}
      <button type="button" className="voice-back-btn" onClick={onBackToChat}>Volver al chat</button>
      <div ref={audioHostRef} className="voice-audio-host" aria-hidden="true" />
    </section>
  )
}
