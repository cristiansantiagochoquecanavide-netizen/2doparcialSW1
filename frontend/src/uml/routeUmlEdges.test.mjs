import test from 'node:test'
import assert from 'node:assert/strict'
import { layoutUmlGraph } from './autoLayout.mjs'
import { measureRoutedLayout, routeUmlEdges, scoreUmlCandidates } from './routeUmlEdges.mjs'

const fixture = [
  ['usuario', 250, 150], ['cliente', 280, 170], ['venta', 260, 210],
  ['detalle', 300, 185], ['producto', 250, 160], ['pago', 230, 150],
  ['pago-tarjeta', 260, 180], ['factura', 250, 145], ['inventario', 290, 190]
].map(([id, width, height]) => ({ id, x: 420, y: 260, width, height }))
const relations = [
  ['usuario-cliente', 'usuario', 'cliente', 'ASOCIACION'], ['cliente-venta', 'cliente', 'venta', 'ASOCIACION'],
  ['venta-detalle', 'venta', 'detalle', 'COMPOSICION'], ['detalle-producto', 'detalle', 'producto', 'ASOCIACION'],
  ['venta-pago', 'venta', 'pago', 'ASOCIACION'], ['tarjeta-pago', 'pago-tarjeta', 'pago', 'HERENCIA'],
  ['venta-factura', 'venta', 'factura', 'ASOCIACION'], ['inventario-producto', 'inventario', 'producto', 'AGREGACION']
].map(([id, source, target, type]) => ({ id, source, target, type, label: id === 'venta-detalle' ? '«contiene» 1 .. *' : '1 .. *' }))

test('scores deterministic orientation candidates with the same orthogonal routes used by the editor', () => {
  const positions = layoutUmlGraph(fixture, relations)
  const placed = fixture.map(node => ({ ...node, ...positions.find(position => position.id === node.id) }))
  const routes = routeUmlEdges(placed, relations)
  const metrics = measureRoutedLayout(placed, routes)
  const candidates = [0, 1, 2, 3].map(turn => positions.map(position => {
    const n = fixture.find(node => node.id === position.id)
    const cx = position.x + n.width / 2, cy = position.y + n.height / 2
    const p = turn === 0 ? { x: cx, y: cy } : turn === 1 ? { x: -cy, y: cx } : turn === 2 ? { x: -cx, y: -cy } : { x: cy, y: -cx }
    return { id: position.id, x: p.x - n.width / 2, y: p.y - n.height / 2 }
  }))
  const scored = scoreUmlCandidates(fixture, relations, candidates)
  assert.equal(routes.length, relations.length)
  assert.ok(routes.every(route => route.points?.length >= 2))
  assert.equal(metrics.nodeOverlaps, 0)
  assert.equal(metrics.edgeThroughNodes, 0)
  assert.equal(metrics.labelOverlaps, 0)
  assert.ok(routes.every(route => route.labelPoint && Number.isFinite(route.labelPoint.x) && Number.isFinite(route.labelPoint.y)))
  assert.ok(scored[0].score <= scored.at(-1).score)
  assert.deepEqual(routeUmlEdges(placed, relations), routes)
  assert.ok(routes.every(route => route.points.slice(1).every((point, i) => point.x === route.points[i].x || point.y === route.points[i].y)))
})

test('candidate ranking reports conflicts using rendered polylines, not node-center lines', () => {
  const candidates = [
    fixture.map((node, i) => ({ id: node.id, x: 100 + i % 3 * 320, y: 100 + Math.floor(i / 3) * 260 })),
    fixture.map((node, i) => ({ id: node.id, x: 100 + Math.floor(i / 3) * 340, y: 100 + i % 3 * 230 }))
  ]
  const ranked = scoreUmlCandidates(fixture, relations, candidates)
  assert.equal(ranked.length, 2)
  assert.ok(ranked.every(candidate => Number.isFinite(candidate.metrics.totalLength) && Number.isFinite(candidate.metrics.crossings)))
})
