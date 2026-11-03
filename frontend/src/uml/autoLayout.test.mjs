import test from 'node:test'
import assert from 'node:assert/strict'
import { layoutUmlGraph } from './autoLayout.mjs'

function overlaps(a, b, sizes, gap = 0) {
  const aw = sizes.get(a.id).width, ah = sizes.get(a.id).height
  const bw = sizes.get(b.id).width, bh = sizes.get(b.id).height
  return a.x < b.x + bw + gap && a.x + aw + gap > b.x && a.y < b.y + bh + gap && a.y + ah + gap > b.y
}

test('organizes a UML inheritance graph with measured node sizes and no overlaps', () => {
  const nodes = [
    { id: 'abstract', x: 400, y: 400, width: 250, height: 170 },
    { id: 'service', x: 400, y: 400, width: 300, height: 190 },
    { id: 'repository', x: 400, y: 400, width: 230, height: 130 },
    { id: 'memory', x: 400, y: 400, width: 260, height: 150 },
    { id: 'isolated', x: 400, y: 400, width: 210, height: 110 }
  ]
  const edges = [
    { source: 'service', target: 'abstract', type: 'HERENCIA' },
    { source: 'service', target: 'repository', type: 'ASOCIACION' },
    { source: 'repository', target: 'memory', type: 'DEPENDENCIA' }
  ]
  const result = layoutUmlGraph(nodes, edges)
  const sizes = new Map(nodes.map(node => [node.id, node]))

  assert.equal(result.length, nodes.length)
  assert.equal(new Set(result.map(node => node.id)).size, nodes.length)
  for (const node of result) assert.ok(Number.isFinite(node.x) && Number.isFinite(node.y))
  for (let i = 0; i < result.length; i += 1) for (let j = i + 1; j < result.length; j += 1) assert.equal(overlaps(result[i], result[j], sizes, 0), false)
  assert.ok(result.find(node => node.id === 'abstract').y < result.find(node => node.id === 'service').y)
  assert.deepEqual(nodes.map(node => [node.x, node.y]), Array.from({ length: nodes.length }, () => [400, 400]))
})

test('produces deterministic positions and preserves disconnected component separation', () => {
  const nodes = Array.from({ length: 8 }, (_, index) => ({ id: `node-${index}`, x: 0, y: 0, width: 220 + index % 2 * 30, height: 120 + index % 3 * 25 }))
  const edges = [
    { source: 'node-0', target: 'node-1', type: 'ASOCIACION' },
    { source: 'node-1', target: 'node-2', type: 'COMPOSICION' },
    { source: 'node-2', target: 'node-0', type: 'DEPENDENCIA' },
    { source: 'node-4', target: 'node-5', type: 'AGREGACION' }
  ]
  const first = layoutUmlGraph(nodes, edges)
  const second = layoutUmlGraph(nodes, edges)
  assert.deepEqual(first, second)
  const sizes = new Map(nodes.map(node => [node.id, node]))
  for (let i = 0; i < first.length; i += 1) for (let j = i + 1; j < first.length; j += 1) assert.equal(overlaps(first[i], first[j], sizes, 12), false)
})

test('handles empty, singleton, reflexive and cyclic UML relations safely', () => {
  assert.deepEqual(layoutUmlGraph([], []), [])
  assert.equal(layoutUmlGraph([{ id: 'only', x: 9, y: 11, width: 200, height: 100 }], []).length, 1)
  const cyclic = layoutUmlGraph(
    [{ id: 'a', x: 0, y: 0, width: 200, height: 100 }, { id: 'b', x: 0, y: 0, width: 240, height: 130 }],
    [{ source: 'a', target: 'b', type: 'HERENCIA' }, { source: 'b', target: 'a', type: 'HERENCIA' }, { source: 'a', target: 'a', type: 'ASOCIACION' }]
  )
  assert.equal(cyclic.length, 2)
  assert.ok(cyclic.every(node => Number.isFinite(node.x) && Number.isFinite(node.y)))
})
