import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { applyProposal, canApplyProposal, describeChange, proposalRequest } from './umlProposalClient.mjs'

const config = { apiBase: '', projectId: 'project', diagramId: 'diagram', token: 'test-token' }
const proposal = { id: 'proposal', status: 'PENDIENTE', baseRevision: 7, preview: { destructive: false } }
const ok = value => ({ ok: true, status: 200, json: async () => value })
async function loadComponent(file) {
  const require = createRequire(import.meta.url)
  const bundled = await build({ entryPoints: [fileURLToPath(new URL(file, import.meta.url))], bundle: true, platform: 'node', format: 'cjs', write: false, jsx: 'automatic', external: ['react', 'react/jsx-runtime'], define: { 'import.meta.env.VITE_API_BASE_URL': '""' } })
  const module = { exports: {} }; new Function('require', 'module', 'exports', bundled.outputFiles[0].text)(require, module, module.exports)
  return module.exports
}

test('reading a saved proposal uses only GET and never applies or generates', async () => {
  const calls = []
  await proposalRequest(config, 'proposal', undefined, async (url, init) => { calls.push({ url, init }); return ok(proposal) })
  assert.equal(calls.length, 1); assert.equal(calls[0].init.method, 'GET'); assert.equal(calls[0].url, '/api/projects/project/diagrams/diagram/ai/proposals/proposal')
  assert.equal(calls[0].init.body, undefined)
})
test('conversation list uses the correct scoped URL without trailing slash', async () => {
  await proposalRequest(config, '?conversationId=conversation', undefined, async url => { assert.equal(url, '/api/projects/project/diagrams/diagram/ai/proposals?conversationId=conversation'); return ok([]) })
})
test('approval sends the exact persisted base revision and request key', async () => {
  await applyProposal(config, proposal, 'EDITOR', false, 'key', async (url, init) => {
    assert.ok(url.endsWith('/proposal/apply')); assert.equal(init.method, 'POST')
    assert.deepEqual(JSON.parse(init.body), { expectedRevision: 7, clientRequestId: 'key', confirmDestructive: false }); return ok({ ...proposal, status: 'APLICADA' })
  })
})
test('readers cannot apply even if a caller invokes the action directly', () => {
  let called = false
  assert.throws(() => applyProposal(config, proposal, 'LECTOR', true, 'key', () => { called = true }), /confirma/)
  assert.equal(called, false); assert.equal(canApplyProposal(proposal, 'LECTOR', true), false)
})
test('destructive patch requires a separate explicit confirmation', () => {
  const destructive = { ...proposal, preview: { destructive: true } }
  assert.equal(canApplyProposal(destructive, 'PROPIETARIO', false), false)
  assert.equal(canApplyProposal(destructive, 'PROPIETARIO', true), true)
})
test('applied rejected failed clarification and conflict states cannot be applied', () => {
  for (const status of ['APLICADA','RECHAZADA','FALLIDA','ACLARACION','CONFLICTO']) assert.equal(canApplyProposal({ ...proposal, status }, 'EDITOR', true), false)
})
test('conflicts preserve HTTP 409 so callers can retain and regenerate the preview', async () => {
  await assert.rejects(proposalRequest(config, 'proposal/apply', {}, async () => ({ ok: false, status: 409, json: async () => ({ message: 'La revisión cambió' }) })), error => error.status === 409 && error.message === 'La revisión cambió')
})
test('rejecting a proposal has its own endpoint without UML mutation requests', async () => {
  await proposalRequest(config, 'proposal/reject', {}, async (url, init) => { assert.ok(url.endsWith('/proposal/reject')); assert.equal(init.method, 'POST'); return ok({ status: 'RECHAZADA' }) })
})
test('provider errors and authentication failures produce safe actionable errors', async () => {
  await assert.rejects(proposalRequest(config, '', {}, async () => ({ ok: false, status: 422, json: async () => ({ errorCode: 'PROVIDER_ERROR' }) })), /PROVIDER_ERROR/)
  await assert.rejects(proposalRequest(config, '', undefined, async () => ({ ok: false, status: 401, json: async () => ({ message: 'Autenticación requerida' }) })), /Autenticación/)
})
test('changes are visibly distinguished and React escapes untrusted model strings', async () => {
  const changes = [
    { id: 'a', kind: 'CREATE', entity: 'CLASS', before: null, after: { id: 'a', name: '<script>Pago</script>', properties: { attributes: [{ name: 'monto', type: 'BigDecimal' }] } } },
    { id: 'b', kind: 'UPDATE', entity: 'RELATION', before: { sourceMultiplicity: '1', targetMultiplicity: '1' }, after: { sourceMultiplicity: '1', targetMultiplicity: '0..*' } },
    { id: 'c', kind: 'DELETE', entity: 'CLASS', before: { name: 'Antigua' }, after: null }
  ]
  const { UmlProposalChanges } = await loadComponent('./UmlProposalChanges.tsx')
  const html = renderToStaticMarkup(React.createElement(UmlProposalChanges, { changes }))
  assert.match(html, /ai-proposal-change create/); assert.match(html, /ai-proposal-change update/); assert.match(html, /ai-proposal-change delete/)
  assert.match(html, /Antes/); assert.match(html, /Después/); assert.match(html, /BigDecimal/); assert.match(html, /0\.\.\*/)
  assert.ok(!html.includes('<script>')); assert.match(html, /&lt;script&gt;/); assert.match(html, /Eliminado/)
  assert.match(describeChange(changes[0]), /Añadido/)
})
test('preview integration keeps the canvas mounted and does not edit UML state directly', async () => {
  const panel = await readFile(new URL('./UmlProposalsPanel.tsx', import.meta.url), 'utf8')
  const parent = await readFile(new URL('./AiConversationsPanel.tsx', import.meta.url), 'utf8')
  assert.ok(parent.includes('<UmlProposalsPanel'))
  assert.ok(!panel.includes('setElementsRaw')); assert.ok(!panel.includes('setNodes(')); assert.ok(!panel.includes('UmlCanvas'))
  assert.ok(panel.includes('role="dialog"')); assert.ok(panel.includes('Aplicar cambios aprobados'))
})
test('proposal form requires a selected conversation so private drafts cannot default to group scope', async () => {
  const { UmlProposalsPanel } = await loadComponent('./UmlProposalsPanel.tsx')
  const html = renderToStaticMarkup(React.createElement(UmlProposalsPanel, { ...config, conversationId: '', role: 'EDITOR', active: false, refreshKey: '' }))
  assert.match(html, /Selecciona o crea una conversación/)
  assert.match(html, /<textarea[^>]*disabled=""/)
  assert.match(html, /<button disabled="">Preparar propuesta/)
})
test('reader sees proposal tools but preparation stays disabled in rendered React markup', async () => {
  const { UmlProposalsPanel } = await loadComponent('./UmlProposalsPanel.tsx')
  const html = renderToStaticMarkup(React.createElement(UmlProposalsPanel, { ...config, conversationId: 'conversation', role: 'LECTOR', active: false, refreshKey: '' }))
  assert.match(html, /Propuestas UML/); assert.match(html, /<textarea[^>]*disabled=""/)
  assert.match(html, /<button disabled="">Preparar propuesta/)
})
