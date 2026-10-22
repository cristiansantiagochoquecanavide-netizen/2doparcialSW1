import test from 'node:test'
import assert from 'node:assert/strict'
import { createContextDictation } from './contextDictation.mjs'

function harness() {
  const states = [], errors = [], transcripts = []
  let instance
  class Recognition {
    constructor() { instance = this }
    starts = 0; stops = 0; aborts = 0
    start() { this.starts++ }
    stop() { this.stops++; this.onend?.() }
    abort() { this.aborts++ }
  }
  const controller = createContextDictation(Recognition, { onTranscript: value => transcripts.push(value), onState: value => states.push(value), onError: value => errors.push(value) })
  return { controller, states, errors, transcripts, get recognition() { return instance } }
}
test('microphone requires explicit start, never starts twice, and never saves context automatically', () => {
  const h = harness()
  assert.equal(h.recognition, undefined)
  h.controller.start(); h.controller.start()
  assert.equal(h.recognition.starts, 1)
  assert.equal(h.recognition.lang, 'es-BO')
  h.recognition.onresult({ results: [[{ transcript: 'Sistema para un gimnasio' }], [{ transcript: 'con clientes y pagos' }]] })
  assert.deepEqual(h.transcripts, ['Sistema para un gimnasio con clientes y pagos'])
  assert.deepEqual(h.states, [true])
})
test('stop keeps the transcript available for review and confirmation', () => {
  const h = harness(); h.controller.start()
  h.recognition.onresult({ results: [[{ transcript: 'Crear clase no autoriza editar el UML' }]] })
  h.controller.stop()
  assert.deepEqual(h.states, [true, false])
  assert.equal(h.transcripts.length, 1)
  assert.equal(h.recognition.stops, 1)
})
test('leaving the view releases microphone and ignores subsequent results', () => {
  const h = harness(); h.controller.start()
  const lateResult = h.recognition.onresult
  h.controller.dispose()
  lateResult({ results: [[{ transcript: 'no guardar' }]] })
  assert.deepEqual(h.transcripts, [])
  assert.equal(h.recognition.aborts, 1)
  assert.equal(h.recognition.onresult, null)
})
test('unsupported recognition and permission refusal expose recoverable errors', () => {
  const errors = []
  createContextDictation(undefined, { onTranscript() {}, onState() {}, onError: e => errors.push(e) }).start()
  assert.match(errors[0], /Puedes escribir/)
  const h = harness(); h.controller.start(); h.recognition.onerror({ error: 'not-allowed' })
  assert.match(h.errors[0], /Permiso de micrófono denegado/)
})
