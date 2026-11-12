import test from 'node:test'
import assert from 'node:assert/strict'
import { exportXmiFile } from './xmiExport.mjs'

function fixtureResponse(xml, contentType = 'application/xml', status = 200) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => contentType }, text: async () => xml }
}

function downloadHarness() {
  const downloads = []
  const revoked = []
  const documentRef = {
    body: { appendChild: (anchor) => downloads.push(anchor), removeChild() {} },
    createElement: () => ({ click() { this.clicked = true } })
  }
  const urlApi = { createObjectURL: (blob) => { downloads.push({ mimeType: blob.type }); return 'blob:xmi' }, revokeObjectURL: (url) => revoked.push(url) }
  return { downloads, revoked, documentRef, urlApi }
}

const parseXml = (xml) => {
  // The production parser is the browser's XML DOMParser. Backend JAXP round-trip
  // tests validate complete generated documents; this adapter checks the UI contract.
  const tags = xml.match(/<\/?[\w:.-]+(?:\s[^<>]*?)?\s*\/?>/g) || []
  const stack = []
  let root
  let parserError = !tags.length
  for (const tag of tags) {
    if (tag.startsWith('<?') || tag.startsWith('<!')) continue
    const name = tag.match(/^<\/?([\w:.-]+)/)?.[1]
    if (!name) { parserError = true; break }
    if (!root && !tag.startsWith('</')) root = name
    if (tag.startsWith('</')) {
      if (stack.pop() !== name) { parserError = true; break }
    } else if (!tag.endsWith('/>')) stack.push(name)
  }
  return { rootName: root?.split(':').pop(), parserError: parserError || stack.length > 0 }
}

for (const ext of ['xmi', 'xml']) {
  test(`exporta una respuesta XMI validada con extensión .${ext}`, async () => {
    const harness = downloadHarness()
    let requestedUrl = ''
    await exportXmiFile({
      apiBase: 'https://case-backend.example/', projectId: 'p1', diagramId: 'd1', token: 'jwt',
      diagramName: 'Diagrama Principal', ext, parseXml,
      fetchImpl: async (url, init) => {
        requestedUrl = url
        assert.equal(init.headers.Authorization, 'Bearer jwt')
        return fixtureResponse('<?xml version="1.0"?><XMI><UML:Model/></XMI>')
      },
      ...harness
    })

    assert.equal(requestedUrl, 'https://case-backend.example/api/projects/p1/diagrams/d1/xmi/export')
    assert.equal(harness.downloads[0].mimeType, `${ext === 'xmi' ? 'application/xmi+xml' : 'application/xml'};charset=utf-8`)
    assert.equal(harness.downloads[1].download, `diagrama_principal.${ext}`)
    assert.equal(harness.downloads[1].clicked, true)
    assert.deepEqual(harness.revoked, ['blob:xmi'])
  })
}

test('rechaza HTML de fallback aunque el servidor responda 200', async () => {
  await assert.rejects(exportXmiFile({
    projectId: 'p', diagramId: 'd', token: 'jwt', diagramName: 'D', ext: 'xmi', parseXml,
    fetchImpl: async () => fixtureResponse('<!doctype html><html><body>SPA</body></html>', 'text/html')
  }), /HTML/)
})

test('rechaza respuestas vacías y XML mal formado', async () => {
  const harness = downloadHarness()
  const args = { projectId: 'p', diagramId: 'd', token: 'jwt', diagramName: 'D', ext: 'xml', parseXml, ...harness, fetchImpl: async () => fixtureResponse('') }
  await assert.rejects(exportXmiFile(args), /vacío/)
  await assert.rejects(exportXmiFile({ ...args, fetchImpl: async () => fixtureResponse('<XMI><Model></XMI>') }), /mal formado/)
})
