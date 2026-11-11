import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { readFile } from 'node:fs/promises'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'

async function component(file, name) {
  const bundle = await build({ entryPoints: [fileURLToPath(new URL(file, import.meta.url))], bundle: true, platform: 'node', format: 'cjs', write: false, jsx: 'automatic', external: ['react', 'react/jsx-runtime'], define: { 'import.meta.env.VITE_API_BASE_URL': '""' } })
  const module = { exports: {} }
  new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports)
  return module.exports[name]
}

test('one sidebar contains three sections, private titles, shared channels and old group histories', async () => {
  const Navigation = await component('./TeamChannelNavigation.tsx', 'TeamChannelNavigation')
  const html = renderToStaticMarkup(React.createElement(Navigation, { items: [{ id: '1', name: 'general', kind: 'text', protected: true }, { id: '2', name: 'Audio', kind: 'voice' }, { id: '3', name: 'Mis decisiones', kind: 'private' }, { id: '4', name: 'Historial anterior', kind: 'legacy' }], selected: 'private:3', busy: false, onSelect() {}, onCreate() {}, onRename() {}, onDelete() {} }))
  assert.equal((html.match(/team-nav-section/g) || []).length, 3)
  for (const label of ['Canales de texto', 'Canales de voz', 'Mis chats con COIN', 'Mis decisiones', 'Historial anterior']) assert.ok(html.includes(label))
  assert.equal((html.match(/aria-label="Crear /g) || []).length, 3)
  assert.equal((html.match(/team-nav-options/g) || []).length, 4)
  assert.match(html, /aria-label="Opciones de Mis decisiones"/)
  assert.ok(!html.includes('<select')); assert.ok(!html.includes('<form'))
})

test('selected AI conversation uses the existing isolated composer with no redundant controls', async () => {
  const Panel = await component('./AiConversationsPanel.tsx', 'AiConversationsPanel')
  const html = renderToStaticMarkup(React.createElement(Panel, { projectId: 'p', diagramId: 'd', token: 'fixture', currentUser: { name: 'Ana', email: 'ana@test', role: 'EDITOR' }, chatOnly: true, navigationHidden: true, selectedConversationId: 'mine', selectedConversationTitle: 'Diseño privado' }))
  assert.ok(html.includes('Diseño privado')); assert.ok(html.includes('ai-compose'))
  for (const forbidden of ['<select', 'Nuevo chat', 'Renombrar', 'Archivar', 'ai-proposals', 'ai-memory-editor']) assert.ok(!html.includes(forbidden), forbidden)
})

test('long responses collapse only their display and expose an expansion button', async () => {
  const Message = await component('./ExpandableMessage.tsx', 'ExpandableMessage')
  const content = 'Texto extenso '.repeat(100) + 'FINAL_PRIVADO'
  const html = renderToStaticMarkup(React.createElement(Message, { content }))
  assert.ok(html.includes('Ver respuesta completa')); assert.ok(!html.includes('FINAL_PRIVADO'))
  assert.ok(!renderToStaticMarkup(React.createElement(Message, { content: 'Breve' })).includes('<button'))
})

test('channel administration uses authenticated APIs, confirms deletion and creates private chats without querying Groq', async () => {
  const source = await readFile(new URL('./LiveTeamChatPanel.tsx', import.meta.url), 'utf8')
  assert.match(source, /window.confirm/); assert.match(source, /confirmDisconnect=true/)
  assert.match(source, /scope: 'PRIVATE'/); assert.match(source, /PROJECT_CHANNELS_CHANGED/)
  assert.match(source, /title: 'New Chat'/)
  assert.match(source, /lastDraftChannel.current/); assert.match(source, /refreshNavigation\(\).then\(\(\) => refresh\(\)\)/)
  assert.ok(!source.includes('groq.com'))
  const voice = await readFile(new URL('./VoiceRoom.tsx', import.meta.url), 'utf8')
  assert.match(voice, /VOICE_JOIN', .*roomId/); assert.match(voice, /VOICE_ROOM_DELETED/); assert.match(voice, /event.code === 4004/)
})
