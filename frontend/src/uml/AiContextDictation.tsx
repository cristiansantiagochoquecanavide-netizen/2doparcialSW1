import { useEffect, useRef, useState } from 'react'
import { createContextDictation, type ContextRecognitionConstructor } from './contextDictation.mjs'

type Props = { disabled: boolean; active: boolean; onConfirm: (text: string) => Promise<void> }
export function AiContextDictation({ disabled, active, onConfirm }: Props) {
  const [transcript, setTranscript] = useState('')
  const [listening, setListening] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const controller = useRef<ReturnType<typeof createContextDictation> | null>(null)
  useEffect(() => {
    const browser = window as unknown as { SpeechRecognition?: ContextRecognitionConstructor; webkitSpeechRecognition?: ContextRecognitionConstructor }
    controller.current = createContextDictation(browser.SpeechRecognition || browser.webkitSpeechRecognition, {
      onTranscript: setTranscript, onState: setListening, onError: setError
    })
    return () => { controller.current?.dispose(); controller.current = null }
  }, [])
  useEffect(() => { if (!active || disabled) controller.current?.stop() }, [active, disabled])
  async function confirm() {
    if (disabled || listening || !transcript.trim() || saving) return
    setSaving(true); setError('')
    try { await onConfirm(transcript.trim()); setTranscript('') }
    catch (e) { setError(e instanceof Error ? e.message : 'No se pudo guardar el contexto.') }
    finally { setSaving(false) }
  }
  return <fieldset className="ai-context-dictation" disabled={disabled || saving}>
    <legend>Describir el sistema por voz</legend>
    <p>Activa el micrófono para dictar el propósito del sistema. Revisa el texto antes de guardarlo.</p>
    <button type="button" onClick={() => { setError(''); if (listening) controller.current?.stop(); else controller.current?.start() }}>{listening ? 'Detener dictado' : 'Dictar contexto'}</button>
    {(transcript || listening) && <><label>Transcripción para revisar<textarea value={transcript} onChange={(e) => setTranscript(e.target.value)} rows={3} maxLength={4000} /></label><button type="button" disabled={listening || !transcript.trim()} onClick={() => void confirm()}>{saving ? 'Guardando…' : 'Confirmar y guardar como contexto compartido'}</button><button type="button" onClick={() => { controller.current?.stop(); setTranscript('') }}>Descartar transcripción</button></>}
    {error && <p role="alert">{error}</p>}
  </fieldset>
}
