import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createAgentSpeech, createSpeechPreferences, spanishLocalVoice, spokenText } from './agentSpeech.mjs'

const local = { name: 'Español local', lang: 'es-BO', localService: true }
const source = { projectId: 'project', conversationId: 'conversation', scope: 'GROUP' }
const message = (id = 'message', content = 'Equipo, conviene revisar la multiplicidad.') => ({ id, content, senderType: 'AGENT', senderUserId: null, recipientUserId: null, replyToMessageId: null, status: 'COMPLETED', createdAt: new Date(100100).toISOString() })
const frame = (id, content) => ({ type: 'AI_CONVERSATION_MESSAGE', ...source, message: message(id, content) })
function fixture(voices = [local]) {
  let time = 100000
  const calls = [], events = new Map(), snapshots = []
  let cancelled = 0, resumed = 0
  class Utterance { constructor(text) { this.text = text } }
  const synthesis = {
    getVoices: () => voices, speak: utterance => calls.push(utterance), cancel: () => { cancelled++ }, resume: () => { resumed++ },
    addEventListener: (name, callback) => events.set(name, callback), removeEventListener: name => events.delete(name)
  }
  const engine = createAgentSpeech({ synthesis, Utterance, projectId: 'project', now: () => time, onState: state => snapshots.push(state) })
  engine.view({ conversationId: 'conversation', scope: 'GROUP', active: true })
  return { engine, synthesis, calls, events, snapshots, clock: value => { time = value }, get cancelled() { return cancelled }, get resumed() { return resumed } }
}
function connected(f) { f.engine.session({ room: true, chat: true }); f.engine.setAutomatic(true); f.clock(100200) }

test('manual playback uses the existing Spanish response, selected local voice, rate and volume', () => {
  const f = fixture(); f.engine.configure({ rate: 1.2, volume: 0.4 })
  assert.equal(f.engine.manual(message(), source), true); assert.equal(f.calls.length, 1)
  const utterance = f.calls[0]; assert.equal(utterance.text.trim(), message().content); assert.equal(utterance.lang, 'es-BO'); assert.equal(utterance.voice, local)
  assert.equal(utterance.rate, 1.2); assert.equal(utterance.volume, 0.4); assert.equal(f.engine.state().speaking, false)
  utterance.onstart(); assert.equal(f.engine.state().speaking, true); utterance.onend(); assert.equal(f.engine.state().busy, false)
})
test('automatic voice starts disabled and requires explicit activation plus room and chat', () => {
  const f = fixture(); assert.equal(f.engine.state().automatic, false)
  assert.equal(f.engine.receive(frame('off')), false)
  f.engine.setAutomatic(true); f.clock(100200); assert.equal(f.engine.receive(frame('no-room')), false)
  f.engine.session({ room: true, chat: false }); assert.equal(f.engine.receive(frame('no-chat')), false)
  f.engine.session({ room: true, chat: true }); f.clock(100400)
  const fresh = frame('fresh'); fresh.message.createdAt = new Date(100300).toISOString()
  assert.equal(f.engine.receive(fresh), true); assert.equal(f.calls.length, 1)
})
test('deduplicates recommendations by stable message ID even after playback completes', () => {
  const f = fixture(); connected(f); const event = frame('one')
  assert.equal(f.engine.receive(event), true); assert.equal(f.engine.receive(event), false)
  f.calls[0].onend(); assert.equal(f.engine.receive(event), false); assert.equal(f.calls.length, 1)
})
test('short queue keeps only the newest pending recommendation without simultaneous utterances', () => {
  const f = fixture(); connected(f)
  f.engine.receive(frame('one', 'Primera')); f.engine.receive(frame('two', 'Segunda')); f.engine.receive(frame('three', 'Tercera'))
  assert.equal(f.calls.length, 1); assert.equal(f.engine.state().pending, true)
  f.calls[0].onend(); assert.equal(f.calls.length, 2); assert.equal(f.calls[1].text, 'Tercera')
  f.calls[1].onend(); assert.equal(f.engine.state().busy, false)
})
test('newer recommendations supersede out of order and expired pending messages', () => {
  const f = fixture(); connected(f); const newer = frame('newer'); newer.message.createdAt = new Date(100150).toISOString()
  f.engine.receive(newer); assert.equal(f.engine.receive(frame('out-of-order')), false)
  const pending = frame('pending'); pending.message.createdAt = new Date(100170).toISOString(); f.engine.receive(pending)
  f.clock(140000); f.calls[0].onend(); assert.equal(f.calls.length, 1)
})
test('disable cancels current and pending audio and late callbacks cannot restart it', () => {
  const f = fixture(); connected(f); f.engine.receive(frame('one')); f.engine.receive(frame('two'))
  const old = f.calls[0]; f.engine.setAutomatic(false); assert.equal(f.cancelled, 1); assert.equal(f.engine.state().busy, false)
  old.onstart(); old.onend(); old.onerror({ error: 'not-allowed' }); assert.equal(f.calls.length, 1); assert.equal(f.engine.state().error, '')
})
test('leaving or reconnecting the voice room cancels audio and drops messages from before rejoining', () => {
  const f = fixture(); connected(f); f.engine.receive(frame('one'))
  f.engine.session({ room: false, chat: true }); assert.equal(f.cancelled, 1)
  f.clock(101000); f.engine.session({ room: true, chat: true }); f.clock(101200)
  assert.equal(f.engine.receive(frame('replayed-old')), false)
  const fresh = frame('new'); fresh.message.createdAt = new Date(101100).toISOString(); assert.equal(f.engine.receive(fresh), true)
})
test('chat reconnect does not replay old history or buffered broadcasts', () => {
  const f = fixture(); connected(f); f.engine.session({ room: true, chat: false }); f.clock(101000)
  f.engine.session({ room: true, chat: true }); f.clock(101200); assert.equal(f.engine.receive(frame('buffered')), false)
  assert.equal(f.calls.length, 0)
})
test('private messages cannot play automatically or manually inside a group voice room', () => {
  const f = fixture(); connected(f)
  assert.equal(f.engine.receive({ ...frame('private'), scope: 'PRIVATE' }), false)
  f.engine.view({ conversationId: 'conversation', scope: 'PRIVATE', active: true })
  assert.equal(f.engine.manual(message('private'), { ...source, scope: 'PRIVATE' }), false); assert.equal(f.calls.length, 0)
  f.engine.session({ room: false, chat: true }); assert.equal(f.engine.manual(message('private'), { ...source, scope: 'PRIVATE' }), true)
  f.engine.session({ room: true, chat: true }); assert.equal(f.engine.state().busy, false)
})
test('individual answers, human messages, errors, malformed times and foreign projects are excluded from automation', () => {
  const f = fixture(); connected(f)
  for (const invalid of [
    { ...frame('foreign'), projectId: 'other' },
    { ...frame('user'), message: { ...message('user'), senderType: 'USER' } },
    { ...frame('reply'), message: { ...message('reply'), replyToMessageId: 'question' } },
    { ...frame('recipient'), message: { ...message('recipient'), recipientUserId: 'user' } },
    { ...frame('error'), message: { ...message('error'), status: 'ERROR' } },
    { ...frame('bad-time'), message: { ...message('bad-time'), createdAt: 'invalid' } },
    { ...frame('future'), message: { ...message('future'), createdAt: new Date(9999999).toISOString() } }
  ]) assert.equal(f.engine.receive(invalid), false)
  assert.equal(f.calls.length, 0)
})
test('manual playback validates the open conversation and cancels on view changes', () => {
  const f = fixture(); assert.equal(f.engine.manual(message(), { ...source, projectId: 'other' }), false)
  assert.equal(f.engine.manual(message(), { ...source, conversationId: 'other' }), false)
  f.engine.manual(message(), source); f.engine.view({ conversationId: 'other', scope: 'GROUP', active: true }); assert.equal(f.engine.state().busy, false)
  assert.equal(f.engine.manual(message(), source), false)
})
test('unsupported browsers and missing local Spanish voices expose a recoverable state', () => {
  const unsupported = createAgentSpeech({ synthesis: null, Utterance: null, projectId: 'project' })
  assert.equal(unsupported.state().supported, false); unsupported.setAutomatic(true); assert.equal(unsupported.state().automatic, false)
  assert.equal(unsupported.manual(message(), source), false); assert.match(unsupported.state().error, /no admite/); unsupported.dispose()
  const f = fixture([]); assert.equal(f.engine.manual(message(), source), false); assert.match(f.engine.state().error, /voz local/)
  assert.equal(f.calls.length, 0); f.events.get('voiceschanged')(); assert.equal(f.engine.state().available, false)
})
test('Spanish fallback remains local and rejects remote voices', () => {
  const mx = { lang: 'es-MX', localService: true }, remote = { lang: 'es-BO', localService: false }
  assert.equal(spanishLocalVoice([mx, remote, local]), local); assert.equal(spanishLocalVoice([remote, mx]), mx)
  assert.equal(spanishLocalVoice([remote, { lang: 'en-US', localService: true }]), null)
})
test('browser autoplay refusal disables automation and provides manual recovery', () => {
  const f = fixture(); connected(f); f.engine.receive(frame('blocked')); f.calls[0].onerror({ error: 'not-allowed' })
  assert.equal(f.engine.state().automatic, false); assert.match(f.engine.state().error, /bloqueó/)
  assert.equal(f.engine.manual(message('manual'), source), true); assert.equal(f.calls.length, 2)
})
test('synthesis exceptions and expected cancellations do not leave a stuck queue', () => {
  const f = fixture(); f.synthesis.speak = () => { throw new Error('No device') }
  assert.equal(f.engine.manual(message(), source), false); assert.equal(f.engine.state().busy, false); assert.match(f.engine.state().error, /iniciar/)
  const g = fixture(); g.engine.manual(message(), source); g.calls[0].onerror({ error: 'canceled' }); assert.equal(g.engine.state().error, '')
})
test('unmuting human microphone stops the agent without altering WebRTC or replaying skipped speech', () => {
  const f = fixture(); connected(f); f.engine.receive(frame('one')); f.engine.session({ room: true, chat: true, mic: true })
  assert.equal(f.engine.state().busy, false); assert.equal(f.engine.receive(frame('while-talking')), false)
  assert.equal(f.engine.manual(message(), source), false); f.engine.session({ room: true, chat: true, mic: false })
  assert.equal(f.engine.receive(frame('while-talking')), false); assert.equal(f.calls.length, 1)
})
test('long manual responses are chunked sequentially; stop clears all remaining chunks', () => {
  const f = fixture(); f.engine.manual(message('long', 'Texto de respuesta. '.repeat(100)), source)
  assert.equal(f.calls.length, 1); assert.ok(f.calls[0].text.length <= 451)
  f.calls[0].onend(); assert.equal(f.calls.length, 2); const old = f.calls[1]
  f.engine.stop(); old.onend(); assert.equal(f.calls.length, 2); assert.equal(f.engine.state().busy, false)
  assert.match(spokenText('Texto ```sql\nselect 1\n``` https://example.test **fin**'), /Código disponible/)
})
test('disposal removes voice listeners and prevents playback or stale callbacks', () => {
  const f = fixture(); f.engine.manual(message(), source); const old = f.calls[0]; f.engine.dispose()
  old.onend(); assert.equal(f.events.size, 0); assert.equal(f.engine.manual(message(), source), false); assert.equal(f.engine.receive(frame('late')), false)
})
test('session preferences survive panel navigation but never pass to another user or authentication session', () => {
  const preferences = createSpeechPreferences()
  assert.equal(preferences.read('Ana', 'session-a'), null)
  preferences.write('Ana', 'session-a', { automatic: true, rate: 1.2, volume: 0.4 })
  assert.equal(preferences.read('Ana', 'session-a').automatic, true)
  assert.equal(preferences.read('Bruno', 'session-a'), null)
  assert.equal(preferences.read('Ana', 'session-b'), null)
  const copy = preferences.read('Ana', 'session-a'); copy.volume = 0
  assert.equal(preferences.read('Ana', 'session-a').volume, 0.4)
})
test('controls render outside the team panel and the synthesis module has no network, recording or UML dependency', async () => {
  const speech = await readFile(new URL('./agentSpeech.mjs', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')
  const chat = await readFile(new URL('./LiveTeamChatPanel.tsx', import.meta.url), 'utf8')
  assert.ok(!/\b(fetch|MediaRecorder|getUserMedia|RTCPeerConnection)\s*\(/.test(speech)); assert.ok(!speech.includes('/api/'))
  assert.match(workspace, /workspace-footer[\s\S]*<AgentSpeechControls/); assert.match(chat, /speech\.receive\(frame\)/)
  const bundled = await build({ entryPoints: [fileURLToPath(new URL('./AgentSpeechUI.tsx', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'cjs', jsx: 'automatic', external: ['react','react/jsx-runtime'] })
  const module = { exports: {} }; new Function('require', 'module', 'exports', bundled.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports)
  const html = renderToStaticMarkup(React.createElement(module.exports.AgentSpeechControls))
  assert.match(html, /Voz automática del agente/); assert.match(html, /Síntesis no disponible/); assert.match(html, /Detener voz/)
  assert.ok(!html.includes('checked=""'))
})
