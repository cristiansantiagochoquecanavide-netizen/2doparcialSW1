import test from 'node:test'
import assert from 'node:assert/strict'
import { analysisRequest, canEditSharedContext } from './agentAnalysisClient.mjs'
const context = { apiBase: '', projectId: 'project-one', diagramId: 'diagram-one', token: 'test-jwt' }
test('project owner and editor can edit shared context; readers cannot', () => {
  assert.equal(canEditSharedContext('PROPIETARIO'), true)
  assert.equal(canEditSharedContext('EDITOR'), true)
  assert.equal(canEditSharedContext('LECTOR'), false)
})
test('toggle and manual review call authorized project endpoints, without sending private messages or editable identities', async () => {
  const calls = []
  const oldFetch = globalThis.fetch
  globalThis.fetch = async (path, init) => { calls.push({ path, ...init }); return new Response(null, { status: 204 }) }
  try {
    await analysisRequest(context, 'policy', { enabled: false })
    await analysisRequest(context, 'review')
    assert.equal(calls[0].path, '/api/projects/project-one/ai/analysis/policy')
    assert.deepEqual(JSON.parse(calls[0].body), { enabled: false })
    assert.equal(calls[0].headers.Authorization, 'Bearer test-jwt')
    assert.equal(calls[1].method, 'POST')
    assert.equal(calls[1].body, undefined)
    assert.match(calls[1].path, /diagrams\/diagram-one\/review$/)
  } finally { globalThis.fetch = oldFetch }
})
test('recommendations are marked handled or dismissed only through their diagram route', async () => {
  const oldFetch = globalThis.fetch; let body, path
  globalThis.fetch = async (url, init) => { path=url; body=JSON.parse(init.body); return new Response(null, { status: 204 }) }
  try { await analysisRequest(context, 'disposition', { id: 'recommendation-one', status: 'DISMISSED', senderType: 'AGENT' }); assert.match(path, /diagram-one\/recommendations\/recommendation-one$/); assert.deepEqual(body, { status: 'DISMISSED' }) }
  finally { globalThis.fetch = oldFetch }
})
test('empty status and temporary errors are exposed without accessing canvas or WebRTC', async () => {
  const oldFetch = globalThis.fetch
  globalThis.fetch = async () => Response.json({ recommendations: [], needsContext: true, job: null })
  try {
    const status = await analysisRequest(context, 'status')
    assert.deepEqual(status.recommendations, [])
    assert.equal(status.needsContext, true)
    globalThis.fetch = async () => Response.json({ message: 'Servicio temporalmente no disponible' }, { status: 503 })
    await assert.rejects(analysisRequest(context, 'status'), /temporalmente no disponible/)
  } finally { globalThis.fetch = oldFetch }
})
