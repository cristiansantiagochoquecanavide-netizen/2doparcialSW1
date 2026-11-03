import test from 'node:test'
import assert from 'node:assert/strict'
import { getUmlSemanticRanks, layoutUmlGraph } from './autoLayout.mjs'
import { measureRoutedLayout, scoreUmlCandidates } from './routeUmlEdges.mjs'

const nodes = [
  ['Usuario', 220, 130], ['Cliente', 250, 155], ['Producto', 260, 170],
  ['Venta', 260, 200], ['Pago', 230, 145], ['DetalleVenta', 300, 190],
  ['Factura', 240, 140], ['PagoTarjeta', 255, 170], ['Inventario', 280, 180]
].map(([id, width, height]) => ({ id, x: 400, y: 300, width, height }))
const edges = [
  ['Usuario', 'Cliente', 'ASOCIACION'], ['Cliente', 'Venta', 'ASOCIACION'],
  ['Venta', 'DetalleVenta', 'COMPOSICION'], ['DetalleVenta', 'Producto', 'ASOCIACION'],
  ['Venta', 'Pago', 'ASOCIACION'], ['PagoTarjeta', 'Pago', 'HERENCIA'],
  ['Venta', 'Factura', 'ASOCIACION'], ['Inventario', 'Producto', 'AGREGACION']
].map(([source, target, type], index) => ({ id: `r${index}`, source, target, type, label: '1 .. *' }))

test('sales fixture receives semantic tiers from context through hub to dependent classes', () => {
  const ranks = getUmlSemanticRanks(nodes, edges)
  assert.equal(ranks.get('Venta'), 0)
  assert.ok(ranks.get('Usuario') < ranks.get('Cliente'))
  assert.equal(ranks.get('Cliente'), ranks.get('Producto'))
  assert.ok(ranks.get('Cliente') < ranks.get('Venta'))
  assert.equal(ranks.get('DetalleVenta'), ranks.get('Pago'))
  assert.ok(ranks.get('Pago') < ranks.get('PagoTarjeta'))
  assert.equal(ranks.get('Factura'), ranks.get('Inventario'))
  assert.equal(ranks.get('Factura'), ranks.get('PagoTarjeta'))

  const first = layoutUmlGraph(nodes, edges)
  const y = new Map(first.map(position => [position.id, position.y]))
  assert.ok(y.get('Usuario') < y.get('Cliente'))
  assert.ok(y.get('Cliente') < y.get('Venta'))
  assert.ok(y.get('Producto') < y.get('Venta'))
  assert.ok(y.get('Venta') < y.get('Pago'))
  assert.ok(y.get('Venta') < y.get('DetalleVenta'))
  assert.ok(y.get('Pago') < y.get('Factura'))
  assert.ok(y.get('PagoTarjeta') >= y.get('Factura'))
  assert.ok(y.get('Inventario') >= y.get('Factura'))
})

test('semantic orientation wins routing alternatives and repeated organization is stable', () => {
  const first = layoutUmlGraph(nodes, edges)
  const candidates = [0, 1, 2, 3].map(turn => first.map(position => {
    const node = nodes.find(item => item.id === position.id)
    const cx = position.x + node.width / 2, cy = position.y + node.height / 2
    const point = turn === 0 ? { x: cx, y: cy } : turn === 1 ? { x: -cy, y: cx } : turn === 2 ? { x: -cx, y: -cy } : { x: cy, y: -cx }
    return { id: position.id, x: point.x - node.width / 2, y: point.y - node.height / 2 }
  }))
  const best = scoreUmlCandidates(nodes, edges, candidates)[0]
  assert.equal(best.index, 0)
  assert.deepEqual(measureRoutedLayout(nodes.map(node => ({ ...node, ...best.positions.find(position => position.id === node.id) })), best.routes), {
    nodeOverlaps: 0, edgeThroughNodes: 0, labelOverlaps: 0,
    totalLength: best.metrics.totalLength, bends: best.metrics.bends
  })
  const repeated = layoutUmlGraph(nodes.map(node => ({ ...node, ...first.find(position => position.id === node.id) })), edges)
  assert.deepEqual(repeated, first)
  assert.deepEqual(layoutUmlGraph(nodes, edges), first)
  const manuallyMoved = nodes.map((node, index) => ({ ...node, x: index * 173, y: index * 91 }))
  assert.deepEqual(layoutUmlGraph(manuallyMoved, edges), first)
})
