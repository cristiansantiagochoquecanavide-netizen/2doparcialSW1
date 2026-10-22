/** A microphone is opened only by calling start(); recognizing speech never saves or edits UML. */
export function createContextDictation(Recognition, { onTranscript, onState, onError }) {
  let recognition = null
  let disposed = false
  let transcript = ''
  return {
    start() {
      if (disposed || recognition) return
      if (!Recognition) { onError('Este navegador no admite dictado. Puedes escribir el contexto.'); return }
      const current = new Recognition()
      recognition = current
      current.lang = 'es-BO'
      current.interimResults = true
      current.continuous = false
      transcript = ''
      current.onresult = (event) => {
        if (disposed || recognition !== current) return
        transcript = Array.from(event.results).map((result) => result[0]?.transcript || '').join(' ').trim()
        onTranscript(transcript)
      }
      current.onerror = (event) => {
        if (!disposed && event.error !== 'aborted') onError(event.error === 'not-allowed' ? 'Permiso de micrófono denegado. Puedes escribir el contexto.' : 'No se pudo reconocer la voz. Revisa o escribe el contexto.')
      }
      current.onend = () => { if (recognition === current) recognition = null; if (!disposed) onState(false) }
      try { current.start(); onState(true) }
      catch { recognition = null; onState(false); onError('No se pudo iniciar el dictado.') }
    },
    stop() { recognition?.stop() },
    dispose() {
      disposed = true
      if (recognition) { recognition.onresult = null; recognition.onend = null; recognition.onerror = null; recognition.abort() }
      recognition = null
    }
  }
}
