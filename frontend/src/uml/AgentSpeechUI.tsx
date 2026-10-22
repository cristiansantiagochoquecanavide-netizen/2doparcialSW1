import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createAgentSpeech, createSpeechPreferences, type AgentSpeech, type SpeechMessage, type SpeechSource, type SpeechState, type SpeechView } from './agentSpeech.mjs'

const initial: SpeechState = { supported: false, available: false, automatic: false, speaking: false, busy: false, pending: false, roomConnected: false, microphoneActive: false, rate: 1, volume: 0.7, error: '' }
type VoiceContext = { state: SpeechState; automatic(value: boolean): void; configure(options: { rate?: number; volume?: number }): void; stop(): void; room(connected: boolean, microphoneActive: boolean): void; chat(connected: boolean): void; receive(frame: unknown): void; view(view: SpeechView): void; manual(message: SpeechMessage, source: SpeechSource): void }
const Context = createContext<VoiceContext>({ state: initial, automatic() {}, configure() {}, stop() {}, room() {}, chat() {}, receive() {}, view() {}, manual() {} })
// Memory only, scoped to authenticated user/session. No global browser storage.
const preferences = createSpeechPreferences()

export function AgentSpeechProvider({ projectId, userKey, sessionKey, children }: { projectId: string; userKey: string; sessionKey: string; children: ReactNode }) {
  const [snapshot, setSnapshot] = useState(initial)
  const controller = useRef<AgentSpeech | null>(null)
  const session = useRef({ room: false, chat: false, mic: false })
  const identity = `${projectId}:${userKey}:${sessionKey}`
  const liveIdentity = useRef(identity); liveIdentity.current = identity
  const readyIdentity = useRef('')
  const current = () => readyIdentity.current === liveIdentity.current ? controller.current : null
  useEffect(() => {
    session.current = { room: false, chat: false, mic: false }
    const saved = preferences.read(userKey, sessionKey)
    const engine = createAgentSpeech({ projectId, synthesis: window.speechSynthesis || null,
      Utterance: window.SpeechSynthesisUtterance || null, onState: state => {
        if (liveIdentity.current !== identity) return
        setSnapshot(state)
        preferences.write(userKey, sessionKey, state)
      } })
    controller.current = engine; readyIdentity.current = identity
    if (saved?.session === sessionKey) { engine.configure(saved); engine.setAutomatic(saved.automatic) }
    function invalidate() { engine.stop(); engine.setAutomatic(false); engine.session({ room: false, chat: false }) }
    window.addEventListener('case:unauthorized', invalidate)
    window.addEventListener('pagehide', invalidate)
    return () => { readyIdentity.current = ''; engine.dispose(); controller.current = null; window.removeEventListener('case:unauthorized', invalidate); window.removeEventListener('pagehide', invalidate) }
  }, [identity])
  const actions = useMemo(() => ({
    automatic: value => current()?.setAutomatic(value), configure: options => current()?.configure(options), stop: () => current()?.stop(),
    room: (room, mic) => { session.current = { ...session.current, room, mic }; current()?.session(session.current) },
    chat: chat => { session.current = { ...session.current, chat }; current()?.session(session.current) },
    receive: frame => { current()?.receive(frame) }, view: view => { current()?.view(view) }, manual: (message, source) => { current()?.manual(message, source) }
  } satisfies Omit<VoiceContext, 'state'>), [identity])
  const value: VoiceContext = { ...actions, state: readyIdentity.current === identity ? snapshot : initial }
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useAgentSpeech() { return useContext(Context) }

export function AgentSpeechControls() {
  const voice = useAgentSpeech(), state = voice.state
  return <section className="agent-speech-controls" aria-label="Voz del agente">
    <label><input type="checkbox" checked={state.automatic} disabled={!state.supported || (!state.available && !state.automatic)} onChange={e => voice.automatic(e.target.checked)} />Voz automática del agente</label>
    <span role="status">{!state.supported ? 'Síntesis no disponible' : !state.available ? 'Falta una voz local en español' : state.speaking ? 'Agente hablando' : state.busy ? 'Iniciando voz…' : state.roomConnected ? state.microphoneActive ? 'En pausa mientras hablas' : 'Voz local · Sala de diseño' : 'Voz local · Fuera de la sala'}</span>
    <button type="button" onClick={voice.stop} disabled={!state.busy && !state.pending}>Detener voz</button>
    <details><summary>Volumen y velocidad</summary><label>Volumen del agente<input aria-label="Volumen del agente" type="range" min="0" max="1" step="0.05" value={state.volume} onChange={e => voice.configure({ volume: Number(e.target.value) })} /></label><label>Velocidad<input aria-label="Velocidad del agente" type="range" min="0.5" max="1.5" step="0.1" value={state.rate} onChange={e => voice.configure({ rate: Number(e.target.value) })} /></label><small>Audio local. La lectura automática requiere sala conectada y micrófono propio silenciado. Las respuestas privadas solo se leen manualmente fuera de la sala.</small></details>
    {state.error && <span role="alert">{state.error}</span>}
  </section>
}
