import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { build } from 'esbuild'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { projectCanvasProposal, greenPreviewEdge, overlayCanvasGraph, affectedPreviewNodeIds } from './canvasProposalPreview.mjs'

const attribute = { id: 'a', name: 'precio', type: 'Double', visibility: '-' }
const before = { id: 'venta', type: 'CLASE', name: 'Venta', x: 12, y: 20, properties: { attributes: [attribute], methods: [{ id: 'm', name: 'total', returnType: 'Double', parameters: '' }] } }
const element = { id: 'venta', nombre: 'Venta', posicionX: 12, posicionY: 20, tipoElemento: 'CLASE', propiedadesJson: JSON.stringify(before.properties) }
const make = changes => ({ id: 'p', diagramId: 'd', scope: 'GROUP', status: 'PENDIENTE', description: 'Cambios de Venta', requesterName: 'Ana', baseRevision: 2, preview: { changes, destructive: false, warnings: [], clarification: null } })
const change = (kind, entity, id, old, after) => ({ kind, entity, id, before: old, after })
test('attribute and method changes are projected without mutating canonical input', () => {
  const after = { ...before, properties: { attributes: [{ ...attribute, type: 'BigDecimal' }, { id: 'pago', name: 'metodoPago', type: 'String' }], methods: [] } }
  const elements = [element], snapshot = JSON.stringify(elements)
  const result = projectCanvasProposal(elements, [], make([change('UPDATE', 'CLASS', 'venta', before, after)]))
  const properties = JSON.parse(result.elements[0].propiedadesJson)
  assert.equal(JSON.stringify(elements), snapshot)
  assert.equal(properties.attributes[1].name, 'metodoPago')
  assert.equal(result.marks.venta.members.a, 'UPDATE'); assert.equal(result.marks.venta.members.pago, 'CREATE')
  assert.equal(result.marks.venta.members.m, 'DELETE'); assert.equal(properties.methods[0].id, 'm')
})
test('class and relation creation update and deletion remain visible as green ghosts', () => {
  const relation = { source: 'venta', destination: 'pago', type: 'ASOCIACION', sourceMultiplicity: '1', targetMultiplicity: '*', name: 'pagos' }
  const proposal = make([change('DELETE', 'CLASS', 'venta', before, null), change('CREATE', 'CLASS', 'pago', null, { ...before, name: 'Pago' }), change('CREATE', 'RELATION', 'r', null, relation)])
  const result = projectCanvasProposal([element], [], proposal)
  assert.equal(result.elements.length, 2); assert.equal(result.marks.venta.kind, 'DELETE'); assert.equal(result.marks.pago.kind, 'CREATE')
  assert.equal(result.relations[0].elementoDestino, 'pago'); assert.equal(result.relationMarks.r, 'CREATE')
  for (const kind of ['UPDATE', 'DELETE']) {
    const projected = projectCanvasProposal([element], [{ id: 'r', elementoOrigen: 'venta', elementoDestino: 'pago', multiplicidadDestino: '*' }], make([change(kind, 'RELATION', 'r', relation, kind === 'UPDATE' ? { ...relation, targetMultiplicity: '0..1' } : null)]))
    assert.equal(projected.relationMarks.r, kind); assert.equal(projected.relations.length, 1)
  }
})
test('class rename, attribute deletion, method addition and method update render their exact proposed values', () => {
  const after = { ...before, name: 'VentaConfirmada', properties: { attributes: [], methods: [{ ...before.properties.methods[0], returnType: 'BigDecimal' }, { id: 'newMethod', name: 'pagar', parameters: 'medio: String', returnType: 'void' }] } }
  const projected = projectCanvasProposal([element], [], make([change('UPDATE', 'CLASS', 'venta', before, after)]))
  assert.equal(projected.elements[0].nombre, 'VentaConfirmada')
  assert.equal(projected.marks.venta.members.a, 'DELETE'); assert.equal(projected.marks.venta.members.m, 'UPDATE'); assert.equal(projected.marks.venta.members.newMethod, 'CREATE')
  assert.equal(JSON.parse(projected.elements[0].propiedadesJson).methods[1].parameters, 'medio: String')
})
test('rejection approval and no active proposal restore the untouched canonical diagram', () => {
  for (const status of ['APLICADA', 'RECHAZADA', 'FALLIDA', 'ACLARACION']) {
    const result = projectCanvasProposal([element], [], { ...make([]), status })
    assert.deepEqual(result.elements, [element]); assert.deepEqual(result.marks, {})
  }
  assert.deepEqual(projectCanvasProposal([element], [], null).elements, [element])
})
test('private proposals cannot become a shared rendering layer', () => {
  const result = projectCanvasProposal([element], [], { ...make([change('DELETE', 'CLASS', 'venta', before, null)]), scope: 'PRIVATE' })
  assert.deepEqual(result.marks, {}); assert.equal(result.elements[0], element)
})
test('edge stroke markers and labels use only green; deletion is dashed and explicit', () => {
  const original = { id: 'r', label: 'pagos', style: { stroke: '#475569' }, markerEnd: { type: 'arrowclosed', color: '#475569' } }
  const edge = greenPreviewEdge(original, 'DELETE')
  assert.equal(edge.style.stroke, '#15803d'); assert.equal(edge.markerEnd.color, '#15803d')
  assert.match(edge.label, /Eliminar/); assert.equal(edge.style.strokeDasharray, '7 4'); assert.equal(original.style.stroke, '#475569')
})
async function bar() {
  const result = await build({ entryPoints: [new URL('./CanvasProposalBar.tsx', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1')], bundle: true, platform: 'node', format: 'cjs', write: false, jsx: 'automatic', external: ['react', 'react/jsx-runtime'] })
  const module = { exports: {} }; new Function('require', 'module', 'exports', result.outputFiles[0].text)(createRequire(import.meta.url), module, module.exports)
  return module.exports.CanvasProposalBar
}
test('inline bar contains approval rejection and change controls without a modal', async () => {
  const Component = await bar(), proposal = make([])
  const html = renderToStaticMarkup(React.createElement(Component, { active: proposal, pending: [proposal], editable: true, stale: false, busy: false, error: '' }))
  for (const label of ['Aprobar', 'Rechazar', 'Ver cambio']) assert.ok(html.includes(label))
  assert.ok(!html.includes('role="dialog"'))
})
test('readers and stale proposals cannot approve and conflict offers regeneration', async () => {
  const Component = await bar(), proposal = make([])
  const html = renderToStaticMarkup(React.createElement(Component, { active: proposal, pending: [proposal], editable: false, stale: true, busy: false, error: '' }))
  assert.match(html, /disabled="">Aprobar/); assert.match(html, /HTTP 409/); assert.match(html, /Regenerar propuesta/)
})
test('preview wiring only projects ReactFlow props and never persists temporary values', async () => {
  const source = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(source, /nodes=\{displayedGraph.nodes\}/); assert.match(source, /edges=\{displayedGraph.edges\}/)
  assert.ok(source.includes('if (!historicalView) return previewGraph'))
  assert.ok(!source.includes('setElementsRaw(projected')); assert.ok(!source.includes('setNodes(previewGraph'))
  const hook = await readFile(new URL('./useCanvasProposal.ts', import.meta.url), 'utf8')
  assert.match(hook, /case:canvas-proposal/); assert.ok(!hook.includes('offline')); assert.ok(!hook.includes('localStorage'))
})
test('adding clase:String to Producto keeps every other class and existing relation untouched', () => {
  const producto = { ...before, id: 'producto', name: 'Producto' }
  const productoElement = { ...element, id: 'producto', nombre: 'Producto', posicionX: 500 }
  const cliente = { ...element, id: 'cliente', nombre: 'Cliente', posicionY: 400 }
  const edges = [{ id: 'old', elementoOrigen: 'cliente', elementoDestino: 'venta' }]
  const result = projectCanvasProposal([element, productoElement, cliente], edges, make([change('UPDATE', 'CLASS', 'producto', producto, { ...producto, properties: { ...producto.properties, attributes: [...producto.properties.attributes, { id: 'clase', name: 'clase', type: 'String' }] } })]))
  assert.equal(result.elements.length, 3); assert.equal(result.elements[0], element); assert.equal(result.elements[2], cliente)
  assert.equal(result.elements[1].posicionX, 500); assert.equal(result.relations[0], edges[0])
  assert.deepEqual(result.marks.producto.fields, []); assert.deepEqual(result.marks.producto.members, { clase: 'CREATE' })
})
test('a diff uses current positions and preserves later unrelated members instead of replaying a snapshot', () => {
  const current = { ...element, posicionX: 900, posicionY: 700, propiedadesJson: JSON.stringify({ ...before.properties, attributes: [attribute, { id: 'later', name: 'otro', type: 'String' }] }) }
  const after = { ...before, properties: { ...before.properties, attributes: [attribute, { id: 'pago', name: 'metodoPago', type: 'String' }] } }
  const result = projectCanvasProposal([current], [], make([change('UPDATE', 'CLASS', 'venta', before, after)]))
  assert.equal(result.elements[0].posicionX, 900); assert.equal(result.elements[0].posicionY, 700)
  assert.deepEqual(JSON.parse(result.elements[0].propiedadesJson).attributes.map(a => a.id), ['a', 'later', 'pago'])
  assert.deepEqual(result.marks.venta.members, { pago: 'CREATE' })
})
test('overlay retains actual nodes, measured dimensions, positions and all unaffected edge identities', () => {
  const nodes = [{ id: 'venta', data: { name: 'Venta' }, position: { x: 800, y: 300 }, measured: { width: 220, height: 160 }, selected: true }, { id: 'producto', data: {}, position: { x: 1200, y: 300 } }]
  const edges = [{ id: 'old', source: 'venta', target: 'producto', sourceHandle: 'bottom-source-2', targetHandle: 'left-target-1' }]
  const overlay = overlayCanvasGraph(nodes, edges, [{ id: 'venta', data: { propertiesJson: 'preview' }, position: { x: 0, y: 0 } }], [])
  assert.equal(overlay.nodes.length, 2); assert.equal(overlay.nodes[1], nodes[1]); assert.equal(overlay.edges[0], edges[0])
  assert.equal(overlay.nodes[0].position, nodes[0].position); assert.equal(overlay.nodes[0].measured, nodes[0].measured); assert.equal(overlay.nodes[0].selected, true)
  assert.equal(nodes[0].data.propertiesJson, undefined)
  const changed = overlayCanvasGraph(nodes, edges, [], [greenPreviewEdge({ ...edges[0], sourceHandle: 'top-source-0' }, 'UPDATE')])
  assert.equal(changed.edges[0].sourceHandle, 'bottom-source-2')
})
test('new preview relations append to the actual edge set and rejecting restores the exact original arrays', () => {
  const nodes = [{ id: 'venta' }, { id: 'producto' }], edges = [{ id: 'existing', source: 'venta', target: 'producto' }]
  const graph = overlayCanvasGraph(nodes, edges, [], [greenPreviewEdge({ id: 'new', source: 'producto', target: 'venta' }, 'CREATE')])
  assert.equal(graph.edges.length, 2); assert.equal(graph.edges[0], edges[0]); assert.equal(graph.nodes[0], nodes[0])
  const original = projectCanvasProposal([element], edges, null)
  assert.equal(original.relations, edges); assert.equal(edges.length, 1)
})
test('outdated diffs cannot resurrect removed classes or relations', () => {
  const result = projectCanvasProposal([], [], make([change('UPDATE', 'CLASS', 'venta', before, before), change('DELETE', 'RELATION', 'removed', { source: 'venta', destination: 'producto' }, null)]))
  assert.equal(result.elements.length, 0); assert.equal(result.relations.length, 0)
})
test('an UPDATE arriving before the base graph must not render a partial diagram of affected nodes only', () => {
  const graph = overlayCanvasGraph([], [], [{ id: 'venta', data: { previewKind: 'UPDATE' }, position: { x: 12, y: 20 } }], [greenPreviewEdge({ id: 'r', source: 'venta', target: 'producto' }, 'UPDATE')])
  assert.equal(graph.nodes.length, 0); assert.equal(graph.edges.length, 0)
  const created = overlayCanvasGraph([], [], [{ id: 'new', data: { previewKind: 'CREATE' }, position: { x: 12, y: 20 } }], [])
  assert.equal(created.nodes.length, 1)
})
test('Ver cambio targets affected classes or both real relation endpoints without toggling a panel', async () => {
  assert.deepEqual(affectedPreviewNodeIds(make([change('UPDATE', 'CLASS', 'venta', before, before)]), [{ id: 'venta' }, { id: 'producto' }], []), ['venta'])
  assert.deepEqual(affectedPreviewNodeIds(make([change('CREATE', 'RELATION', 'r', null, { source: 'venta', destination: 'producto' })]), [{ id: 'venta' }, { id: 'producto' }], []), ['venta', 'producto'])
  const bar = await readFile(new URL('./CanvasProposalBar.tsx', import.meta.url), 'utf8')
  assert.match(bar, /onClick=\{onFocus\}>Ver cambio/); assert.ok(!bar.includes('preview.warnings')); assert.ok(!bar.includes('acodi-canvas-details'))
  const css = await readFile(new URL('./canvas-proposal.css', import.meta.url), 'utf8')
  assert.match(css.split('\n')[0], /background:#fff/); assert.ok(!css.includes('.acodi-preview .uml-node-header'))
})
