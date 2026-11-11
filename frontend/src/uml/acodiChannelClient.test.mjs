import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { insertAcodiMention, mentionPattern, acodiError, channelSpeechMessage, channelRecommendationFrame } from './acodiChannelClient.mjs'
import { createAgentSpeech } from './agentSpeech.mjs'
const human = { id: 'question', channelId: 'general', senderUserId: 'ana', senderName: 'Ana', senderEmail: 'ana@test', senderRole: 'EDITOR', authorType: 'USER', clientMessageId: 'key', content: '@ACODI revisa Venta', createdAt: '2026-01-01T00:00:00Z', replyToMessageId: null }
const agent = { ...human, id: 'answer', senderUserId: null, senderName: 'ACODI', senderEmail: null, senderRole: 'AGENTE', authorType: 'AGENT', content: 'Ana, revisa la multiplicidad.', replyToMessageId: human.id }
async function component() {
  const result = await build({ entryPoints: [fileURLToPath(new URL('./AcodiChannelMessage.tsx', import.meta.url))], bundle: true, platform: 'node', format: 'cjs', write: false, jsx: 'automatic', external: ['react','react/jsx-runtime'] })
  const module = { exports: {} }; new Function('require','module','exports',result.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports); return module.exports.AcodiChannelMessage
}
test('mention button inserts at the cursor and preserves all existing text', () => {
  assert.deepEqual(insertAcodiMention('revisa Venta', 0), { text: '@COIN revisa Venta', cursor: 6 })
  assert.deepEqual(insertAcodiMention('Hola revisa', 5), { text: 'Hola @COIN revisa', cursor: 11 })
  assert.deepEqual(insertAcodiMention('Hola', 4), { text: 'Hola @COIN ', cursor: 11 })
})
test('mention button focuses existing mentions without adding duplicates', () => {
  assert.equal(insertAcodiMention('Hola @aCoDi revisa', 2).text, 'Hola @aCoDi revisa')
  assert.equal(insertAcodiMention('').text, '@COIN ')
})
test('mention matching excludes emails partial names and plain ACODI', () => {
  for (const text of ['ACODI revisa','Carlos revisa','persona@acodi.com','@ACODIextra','@@ACODI','á@ACODI']) assert.equal(mentionPattern().test(text), false)
  for (const text of ['@COIN revisa','Hola @coin, revisa','@ACODI revisa']) assert.equal(mentionPattern().test(text), true)
})
test('ACODI message renders its avatar, reply link, identity and manual listen control', async () => {
  const Card = await component(); const html = renderToStaticMarkup(React.createElement(Card, { message: agent, own: false, canListen: true, canRetry: false, busy: false, onListen() {}, onRetry() {} }))
  assert.match(html, /Avatar COIN/); assert.match(html, /Asistente IA/); assert.match(html, /En respuesta a la consulta/); assert.match(html, /Ana, revisa/); assert.match(html, />Escuchar</)
})
test('human processing and failure states are shown without a fake successful agent answer', async () => {
  const Card = await component()
  for (const status of ['PENDIENTE','PROCESANDO','FALLIDO']) {
    const html = renderToStaticMarkup(React.createElement(Card, { message: { ...human, acodi: { status, attempts: 1, errorCode: 'RATE_LIMITED' } }, own: true, canListen: false, canRetry: true, busy: false, onListen() {}, onRetry() {} }))
    assert.match(html, status === 'FALLIDO' ? /límite temporal/ : /cola para COIN|preparando una respuesta/)
    assert.ok(!html.includes('>Escuchar<')); assert.match(html, /Ana/)
  }
})
test('failed calls expose useful explanations instead of bare internal codes', () => {
  for (const code of ['RATE_LIMITED','LIMIT_REACHED','CONTEXT_REQUIRED','CONTEXT_UNAVAILABLE','INTERRUPTED','PROVIDER_UNAVAILABLE','RESPONSE_FAILED']) { assert.ok(acodiError(code).length > 20); assert.ok(!acodiError(code).includes(code)) }
})
test('voice adapter reads the same public response without generating another request', () => {
  const adapted = channelSpeechMessage(agent)
  assert.equal(adapted.content, agent.content); assert.equal(adapted.senderType, 'AGENT'); assert.equal(adapted.status, 'COMPLETED'); assert.equal(adapted.id, agent.id)
  assert.equal(channelRecommendationFrame('project', agent), null)
  assert.equal(channelRecommendationFrame('project', human), null)
})
test('canonical recommendation ID deduplicates historical-conversation and channel speech events', () => {
  let time = 1000; const calls = []; class Utterance { constructor(text) { this.text = text } }
  const engine = createAgentSpeech({ projectId: 'project', now: () => time, Utterance, synthesis: { getVoices: () => [{ lang: 'es-BO', localService: true }], speak: item => calls.push(item), cancel() {} } })
  engine.setAutomatic(true); engine.session({ room: true, chat: true }); time = 1200
  const recommendation = { ...agent, replyToMessageId: null, canonicalAiMessageId: 'canonical', createdAt: new Date(1100).toISOString() }
  assert.equal(engine.receive(channelRecommendationFrame('project', recommendation)), true)
  assert.equal(engine.receive({ type: 'AI_CONVERSATION_MESSAGE', projectId: 'project', conversationId: 'historical', message: { ...channelSpeechMessage(recommendation), senderUserId: null, recipientUserId: null } }), false)
  assert.equal(calls.length, 1)
})
test('channel switching retains the chat and voice components while controls wrap in narrow panels', async () => {
  const source = await readFile(new URL('./LiveTeamChatPanel.tsx', import.meta.url), 'utf8')
  const css = await readFile(new URL('./team-collaboration.css', import.meta.url), 'utf8')
  assert.match(source, /drafts\.current\.set\(activeChannelId, input\)/); assert.match(source, /selectChannel\(item.id\)/)
  assert.match(source, /<VoiceRoom key=\{projectId \+ ':' \+ token \+ ':' \+ selectedVoice\}/); assert.match(source, /hidden=\{view !== 'voice'\}/)
  assert.match(source, /composerRef\.current\?\.focus/); assert.match(source, /diagramId \}/)
  assert.match(css, /\.team-input-bar \{ flex-wrap: wrap/); assert.match(css, /\.team-input-form \{ flex-shrink: 0/)
})
