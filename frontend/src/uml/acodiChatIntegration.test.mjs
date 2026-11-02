import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { projectCanvasProposal } from './canvasProposalPreview.mjs'

test('team navigation has exactly two tabs and conversation histories are part of chat navigation', async () => {
  const code = await readFile(new URL('./LiveTeamChatPanel.tsx', import.meta.url), 'utf8')
  assert.equal((code.match(/className=\{'team-subtab-btn /g) || []).length, 2)
  assert.ok(!code.includes('Herramientas ACODI')); assert.ok(!code.includes("activeTab === 'ai'"))
  assert.match(code, /TeamChannelNavigation/); assert.match(code, /chatOnly navigationHidden/)
  assert.match(code, /team-content-split" hidden=\{activeTab !== 'chat'\}/)
})
test('chat conversation view keeps private/group navigation without proposal or memory panels', async () => {
  const bundle = await build({ entryPoints: [fileURLToPath(new URL('./AiConversationsPanel.tsx', import.meta.url))], bundle: true, platform: 'node', format: 'cjs', write: false, jsx: 'automatic', external: ['react', 'react/jsx-runtime'], define: { 'import.meta.env.VITE_API_BASE_URL': '""' } })
  const module = { exports: {} }; new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports)
  const html = renderToStaticMarkup(React.createElement(module.exports.AiConversationsPanel, { projectId: 'p', diagramId: 'd', token: 'fixture', currentUser: { name: 'Ana', email: 'ana@test', role: 'EDITOR' }, chatOnly: true }))
  assert.match(html, /Chats privados/); assert.match(html, /Historiales grupales/); assert.match(html, /Nuevo chat/)
  assert.ok(!html.includes('ai-proposals')); assert.ok(!html.includes('ai-proactive')); assert.ok(!html.includes('Memoria compartida confirmada')); assert.ok(!html.includes('role="dialog"'))
})
test('private preview requires explicit authorized selection and is never enabled for the shared overlay by default', () => {
  const element = { id: 'c', nombre: 'Venta', tipoElemento: 'CLASE', propiedadesJson: '{"attributes":[],"methods":[]}', posicionX: 10, posicionY: 20 }
  const before = { id: 'c', name: 'Venta', type: 'CLASE', properties: { attributes: [], methods: [] } }
  const proposal = { scope: 'PRIVATE', status: 'PENDIENTE', preview: { changes: [{ id: 'c', entity: 'CLASS', kind: 'DELETE', before, after: null }] } }
  assert.deepEqual(projectCanvasProposal([element], [], proposal).marks, {})
  assert.equal(projectCanvasProposal([element], [], proposal, true).marks.c.kind, 'DELETE')
})
test('private selection authenticates its GET and does not publish it through the shared canvas endpoint', async () => {
  const code = await readFile(new URL('./useCanvasProposal.ts', import.meta.url), 'utf8')
  assert.match(code, /proposalRequest<Proposal>\(authorized, privateSelection.current\)/)
  assert.match(code, /privateProposal.scope === 'PRIVATE'/); assert.match(code, /case:canvas-private-proposal/)
  assert.match(code, /result.proposal.scope === 'PRIVATE'\) privateSelection.current/)
})
test('conversation drafts and displayed messages are scoped to the selected authorized conversation', async () => {
  const code = await readFile(new URL('./AiConversationsPanel.tsx', import.meta.url), 'utf8')
  assert.match(code, /conversationDrafts.current.get\(activeId\)/)
  assert.match(code, /historyId === activeId/); assert.match(code, /const messages = conversationReady \? messageHistory : \[\]/)
  assert.match(code, /value=\{conversationReady \? input : ''\}/)
})

test('CU09 comparison selection is scoped to the canvas session and accompanies group and private chat requests', async () => {
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  const team = await readFile(new URL('./LiveTeamChatPanel.tsx', import.meta.url), 'utf8')
  const privateChat = await readFile(new URL('./AiConversationsPanel.tsx', import.meta.url), 'utf8')
  assert.match(canvas, /setComparisonVersionIds\(\[beforeVersion\.id, afterVersion\.id\]\)/)
  assert.match(canvas, /setComparisonVersionIds\(\[\]\).*\[project\.id, diagram\?\.id, auth\.token\]/s)
  assert.match(team, /comparisonVersionIds: comparisonVersionIds\.slice\(0, 2\)/)
  assert.match(privateChat, /comparisonVersionIds: comparisonVersionIds\.slice\(0, 2\)/)
  assert.match(privateChat, /messages\/\$\{message\.id\}\/retry[^\n]+comparisonVersionIds/)
  assert.equal((team.match(/className=\{'team-subtab-btn /g) || []).length, 2)
  assert.doesNotMatch(canvas, /Analizar con ACODI|selectedVersionStorage|localStorage.*comparison/i)
})
