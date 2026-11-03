import { getUmlSemanticRanks } from './autoLayout.mjs'

const CLEARANCE = 24

function sidePoint(node, side) {
  if (side === 'left') return { x: node.x, y: node.y + node.height / 2 }
  if (side === 'right') return { x: node.x + node.width, y: node.y + node.height / 2 }
  if (side === 'top') return { x: node.x + node.width / 2, y: node.y }
  return { x: node.x + node.width / 2, y: node.y + node.height }
}

function crossesRect(a, b, rect) {
  if (a.x === b.x) return a.x > rect.left && a.x < rect.right && Math.max(Math.min(a.y, b.y), rect.top) < Math.min(Math.max(a.y, b.y), rect.bottom)
  if (a.y === b.y) return a.y > rect.top && a.y < rect.bottom && Math.max(Math.min(a.x, b.x), rect.left) < Math.min(Math.max(a.x, b.x), rect.right)
  return false
}

function length(a, b) { return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) }

function simplify(points) {
  const result = []
  for (const point of points) {
    const prev = result.at(-1)
    if (prev && prev.x === point.x && prev.y === point.y) continue
    if (result.length > 1) {
      const before = result[result.length - 2]
      if ((before.x === prev.x && prev.x === point.x) || (before.y === prev.y && prev.y === point.y)) result.pop()
    }
    result.push(point)
  }
  return result
}

function pathCandidates(a, b, obstacles, bounds) {
  const midX = Math.round((a.x + b.x) / 2), midY = Math.round((a.y + b.y) / 2)
  const candidates = [
    [a, { x: midX, y: a.y }, { x: midX, y: b.y }, b],
    [a, { x: a.x, y: midY }, { x: b.x, y: midY }, b],
    [a, { x: bounds.left, y: a.y }, { x: bounds.left, y: b.y }, b],
    [a, { x: bounds.right, y: a.y }, { x: bounds.right, y: b.y }, b],
    [a, { x: a.x, y: bounds.top }, { x: b.x, y: bounds.top }, b],
    [a, { x: a.x, y: bounds.bottom }, { x: b.x, y: bounds.bottom }, b]
  ].map(simplify)
  return candidates.map(points => {
    let score = 0, distance = 0, bends = Math.max(0, points.length - 2)
    for (let i = 1; i < points.length; i += 1) {
      distance += length(points[i - 1], points[i])
      for (const obstacle of obstacles) if (crossesRect(points[i - 1], points[i], obstacle)) score += 100000
    }
    score += distance + bends * 38
    return { points, score, distance, bends }
  })
}

function pushHeap(heap, item) {
  heap.push(item)
  let i = heap.length - 1
  while (i > 0) {
    const parent = (i - 1) >> 1
    if (heap[parent].cost <= item.cost) break
    heap[i] = heap[parent]; i = parent
  }
  heap[i] = item
}

function popHeap(heap) {
  const first = heap[0], last = heap.pop()
  if (heap.length && last) {
    let i = 0
    while (true) {
      const left = i * 2 + 1, right = left + 1
      if (left >= heap.length) break
      const child = right < heap.length && heap[right].cost < heap[left].cost ? right : left
      if (heap[child].cost >= last.cost) break
      heap[i] = heap[child]; i = child
    }
    heap[i] = last
  }
  return first
}

function shortestOrthogonalPath(start, end, obstacles, bounds, priorRoutes) {
  const xs = [...new Set([start.x, end.x, bounds.left, bounds.right, ...obstacles.flatMap(r => [r.left, r.right])])].sort((a, b) => a - b)
  const ys = [...new Set([start.y, end.y, bounds.top, bounds.bottom, ...obstacles.flatMap(r => [r.top, r.bottom])])].sort((a, b) => a - b)
  const points = [], indexByKey = new Map()
  for (let yi = 0; yi < ys.length; yi += 1) for (let xi = 0; xi < xs.length; xi += 1) {
    const point = { x: xs[xi], y: ys[yi] }
    if (obstacles.some(r => point.x > r.left && point.x < r.right && point.y > r.top && point.y < r.bottom)) continue
    indexByKey.set(`${point.x}:${point.y}`, points.length); points.push(point)
  }
  const startIndex = indexByKey.get(`${start.x}:${start.y}`), endIndex = indexByKey.get(`${end.x}:${end.y}`)
  if (startIndex == null || endIndex == null) return null
  const neighbors = points.map(() => [])
  for (const [yi, y] of ys.entries()) for (const [xi, x] of xs.entries()) {
    const index = indexByKey.get(`${x}:${y}`)
    if (index == null) continue
    for (const [nx, ny, dir] of [[xi + 1, yi, 1], [xi - 1, yi, 1], [xi, yi + 1, 2], [xi, yi - 1, 2]]) {
      if (nx < 0 || nx >= xs.length || ny < 0 || ny >= ys.length) continue
      const next = indexByKey.get(`${xs[nx]}:${ys[ny]}`)
      if (next == null) continue
      const a = points[index], b = points[next]
      if (obstacles.some(rect => crossesRect(a, b, rect))) continue
      let crossingPenalty = 0
      for (const route of priorRoutes) if (route.points) for (let i = 1; i < route.points.length; i += 1) {
        const c = route.points[i - 1], d = route.points[i]
        if ((a.x === b.x && c.y === d.y && c.x < a.x && a.x < d.x && Math.min(a.y, b.y) < c.y && c.y < Math.max(a.y, b.y)) ||
            (a.y === b.y && c.x === d.x && Math.min(a.x, b.x) < c.x && c.x < Math.max(a.x, b.x) && c.y < a.y && a.y < d.y)) crossingPenalty += 500
      }
      neighbors[index].push({ next, dir, weight: length(a, b) + crossingPenalty })
    }
  }
  const stateCount = points.length * 3, distances = new Float64Array(stateCount).fill(Infinity), previous = new Int32Array(stateCount).fill(-1), heap = []
  const initialState = startIndex * 3
  distances[initialState] = 0; pushHeap(heap, { state: initialState, cost: 0 })
  let finalState = -1
  while (heap.length) {
    const current = popHeap(heap)
    if (current.cost !== distances[current.state]) continue
    const pointIndex = Math.floor(current.state / 3), oldDir = current.state % 3
    if (pointIndex === endIndex) { finalState = current.state; break }
    for (const edge of neighbors[pointIndex]) {
      const state = edge.next * 3 + edge.dir
      const cost = current.cost + edge.weight + (oldDir && oldDir !== edge.dir ? 38 : 0)
      if (cost >= distances[state]) continue
      distances[state] = cost; previous[state] = current.state; pushHeap(heap, { state, cost })
    }
  }
  if (finalState < 0) return null
  const path = []
  for (let state = finalState; state >= 0; state = previous[state]) path.push(points[Math.floor(state / 3)])
  return simplify(path.reverse())
}

function placeLabel(points, text, nodes, priorRoutes) {
  if (!text) return null
  const width = Math.max(36, String(text).length * 7 + 16), height = 24
  let longest = null
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1], b = points[i], distance = length(a, b)
    if (!longest || distance > longest.distance) longest = { a, b, distance }
  }
  if (!longest) return null
  const horizontal = longest.a.y === longest.b.y
  const center = horizontal
    ? { x: (longest.a.x + longest.b.x) / 2, y: longest.a.y }
    : { x: longest.a.x, y: (longest.a.y + longest.b.y) / 2 }
  const candidates = []
  const along = longest.distance >= width + 24 ? [0.2, 0.35, 0.5, 0.65, 0.8] : [0.5]
  for (const fraction of along) {
    const base = horizontal
      ? { x: longest.a.x + (longest.b.x - longest.a.x) * fraction, y: center.y }
      : { x: center.x, y: longest.a.y + (longest.b.y - longest.a.y) * fraction }
    for (const dx of [-100, -70, -40, -20, 0, 20, 40, 70, 100]) for (const dy of [-100, -70, -40, -20, 0, 20, 40, 70, 100]) candidates.push({ x: base.x + dx, y: base.y + dy })
  }
  const box = point => ({ left: point.x - width / 2, right: point.x + width / 2, top: point.y - height / 2, bottom: point.y + height / 2 })
  const intersects = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
  return candidates.map((point, order) => {
    const rect = box(point)
    let score = Math.hypot(point.x - center.x, point.y - center.y) * 0.05 + order * 0.0001
    for (const node of nodes) if (intersects(rect, { left: node.x, right: node.x + node.width, top: node.y, bottom: node.y + node.height })) score += 100000
    for (const route of priorRoutes) if (route.labelPoint) {
      const priorWidth = Math.max(36, String(route.label || '').length * 7 + 16)
      if (intersects(rect, { left: route.labelPoint.x - priorWidth / 2, right: route.labelPoint.x + priorWidth / 2, top: route.labelPoint.y - 12, bottom: route.labelPoint.y + 12 })) score += 50000
    }
    return { point, score }
  }).sort((a, b) => a.score - b.score)[0].point
}

/** Deterministic orthogonal routes for the exact arranged positions, used by the edge renderer. */
export function routeUmlEdges(inputNodes, inputEdges) {
  const nodes = inputNodes.map(node => ({ ...node, width: node.width || 240, height: node.height || 120 }))
  const byId = new Map(nodes.map(node => [String(node.id), node]))
  const bounds = {
    left: Math.min(...nodes.map(node => node.x)) - 90,
    right: Math.max(...nodes.map(node => node.x + node.width)) + 90,
    top: Math.min(...nodes.map(node => node.y)) - 90,
    bottom: Math.max(...nodes.map(node => node.y + node.height)) + 90
  }
  const routed = []
  for (const edge of [...inputEdges].sort((a, b) => String(a.id).localeCompare(String(b.id)))) {
    const source = byId.get(String(edge.source)), target = byId.get(String(edge.target))
    if (!source || !target || source.id === target.id) { routed.push({ id: edge.id, source: edge.source, target: edge.target, points: null }); continue }
    const dx = target.x + target.width / 2 - source.x - source.width / 2
    const dy = target.y + target.height / 2 - source.y - source.height / 2
    const sourceSide = Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? 'right' : 'left') : (dy >= 0 ? 'bottom' : 'top')
    const targetSide = sourceSide === 'right' ? 'left' : sourceSide === 'left' ? 'right' : sourceSide === 'bottom' ? 'top' : 'bottom'
    const start = sidePoint(source, sourceSide), end = sidePoint(target, targetSide)
    const obstacles = nodes.filter(node => node !== source && node !== target).map(node => ({ left: node.x - CLEARANCE, right: node.x + node.width + CLEARANCE, top: node.y - CLEARANCE, bottom: node.y + node.height + CLEARANCE }))
    const points = shortestOrthogonalPath(start, end, obstacles, bounds, routed) ?? pathCandidates(start, end, obstacles, bounds).sort((a, b) => a.score - b.score)[0].points
    let distance = 0
    for (let i = 1; i < points.length; i += 1) distance += length(points[i - 1], points[i])
    routed.push({ id: edge.id, source: edge.source, target: edge.target, points, distance, bends: Math.max(0, points.length - 2), label: edge.label, labelPoint: placeLabel(points, edge.label, nodes, routed) })
  }
  return routed
}

export function measureRoutedLayout(nodes, routes) {
  const byId = new Map(nodes.map(node => [String(node.id), node]))
  let nodeOverlaps = 0, edgeThroughNodes = 0, labelOverlaps = 0, totalLength = 0, bends = 0
  for (let i = 0; i < nodes.length; i += 1) for (let j = i + 1; j < nodes.length; j += 1) {
    const a = nodes[i], b = nodes[j]
    if (a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y) nodeOverlaps += 1
  }
  for (const route of routes) {
    if (!route.points) continue
    totalLength += route.distance || 0; bends += route.bends || 0
    for (const node of byId.values()) {
      if (String(node.id) === String(route.source) || String(node.id) === String(route.target)) continue
      for (let i = 1; i < route.points.length; i += 1) if (crossesRect(route.points[i - 1], route.points[i], { left: node.x, right: node.x + node.width, top: node.y, bottom: node.y + node.height })) { edgeThroughNodes += 1; break }
    }
    if (route.labelPoint) {
      const width = Math.max(36, String(route.label || '').length * 7 + 16)
      const label = { left: route.labelPoint.x - width / 2, right: route.labelPoint.x + width / 2, top: route.labelPoint.y - 12, bottom: route.labelPoint.y + 12 }
      for (const node of byId.values()) if (label.left < node.x + node.width && label.right > node.x && label.top < node.y + node.height && label.bottom > node.y) { labelOverlaps += 1; break }
      for (const other of routes) if (other.id !== route.id && other.labelPoint) {
        const otherWidth = Math.max(36, String(other.label || '').length * 7 + 16)
        if (label.left < other.labelPoint.x + otherWidth / 2 && label.right > other.labelPoint.x - otherWidth / 2 && label.top < other.labelPoint.y + 12 && label.bottom > other.labelPoint.y - 12) { labelOverlaps += 1; break }
      }
    }
  }
  return { nodeOverlaps, edgeThroughNodes, labelOverlaps, totalLength: Math.round(totalLength), bends }
}

export function scoreUmlCandidates(nodes, edges, candidates) {
  return candidates.map((positions, index) => {
    const byId = new Map(positions.map(position => [String(position.id), position]))
    const placed = nodes.map(node => ({ ...node, ...byId.get(String(node.id)) }))
    const routes = routeUmlEdges(placed, edges)
    const metrics = measureRoutedLayout(placed, routes)
    const ranks = getUmlSemanticRanks(placed, edges)
    const rows = new Map()
    for (const node of placed) {
      const rank = ranks.get(String(node.id)) ?? 0
      if (!rows.has(rank)) rows.set(rank, [])
      rows.get(rank).push(node)
    }
    const orderedRows = [...rows.entries()].sort((a, b) => a[0] - b[0]).map(([rank, row]) => ({
      rank,
      centerY: row.reduce((sum, node) => sum + node.y + node.height / 2, 0) / row.length,
      centerX: row.reduce((sum, node) => sum + node.x + node.width / 2, 0) / row.length
    }))
    let hierarchyPenalty = 0
    for (let i = 1; i < orderedRows.length; i += 1) {
      const gap = orderedRows[i].centerY - orderedRows[i - 1].centerY
      if (gap <= 0) hierarchyPenalty += 1e7 + Math.abs(gap) * 10000
      else if (gap < 90) hierarchyPenalty += (90 - gap) * 5000
    }
    const minX = Math.min(...placed.map(node => node.x)), maxX = Math.max(...placed.map(node => node.x + node.width))
    const centerX = (minX + maxX) / 2
    const symmetryPenalty = orderedRows.reduce((sum, row) => sum + Math.abs(row.centerX - centerX), 0)
    let crossings = 0
    for (let i = 0; i < routes.length; i += 1) for (let j = i + 1; j < routes.length; j += 1) {
      const a = routes[i].points, b = routes[j].points
      if (!a || !b) continue
      for (let ai = 1; ai < a.length; ai += 1) for (let bi = 1; bi < b.length; bi += 1) {
        const p = a[ai - 1], q = a[ai], r = b[bi - 1], s = b[bi]
        if ((p.x === q.x && r.y === s.y && r.x < p.x && p.x < s.x && p.y > r.y && p.y < s.y) || (p.y === q.y && r.x === s.x && p.x > r.x && p.x < s.x && p.y > r.y && p.y < s.y)) crossings += 1
      }
    }
    const weighted = metrics.nodeOverlaps * 1e9 + metrics.edgeThroughNodes * 1e8 + metrics.labelOverlaps * 5e6 + hierarchyPenalty + symmetryPenalty * 3 + crossings * 1e5 + metrics.totalLength + metrics.bends * 36
    return { index, positions, routes, metrics: { ...metrics, crossings, hierarchyPenalty, symmetryPenalty }, score: weighted }
  }).sort((a, b) => a.score - b.score || a.index - b.index)
}
