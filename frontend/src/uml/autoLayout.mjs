const NODE_GAP = 42
const COMPONENT_GAP = 96
const SPRING_LENGTH = 245

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function getComponents(nodes, edges) {
  const adjacent = new Map(nodes.map(node => [node.id, new Set()]))
  for (const edge of edges) {
    if (edge.source === edge.target || !adjacent.has(edge.source) || !adjacent.has(edge.target)) continue
    adjacent.get(edge.source).add(edge.target)
    adjacent.get(edge.target).add(edge.source)
  }
  const components = []
  const visited = new Set()
  for (const node of nodes) {
    if (visited.has(node.id)) continue
    const queue = [node.id]
    const ids = []
    visited.add(node.id)
    for (let cursor = 0; cursor < queue.length; cursor += 1) {
      const id = queue[cursor]
      ids.push(id)
      for (const next of adjacent.get(id)) {
        if (visited.has(next)) continue
        visited.add(next)
        queue.push(next)
      }
    }
    components.push({ ids, adjacent })
  }
  return components
}

function inheritanceSeed(componentNodes, edges) {
  const ids = new Set(componentNodes.map(node => node.id))
  const parents = new Map(componentNodes.map(node => [node.id, new Set()]))
  const children = new Map(componentNodes.map(node => [node.id, new Set()]))
  for (const edge of edges) {
    if (edge.type !== 'HERENCIA' || !ids.has(edge.source) || !ids.has(edge.target) || edge.source === edge.target) continue
    // En el diagrama el extremo de HERENCIA apunta a la clase padre.
    parents.get(edge.source).add(edge.target)
    children.get(edge.target).add(edge.source)
  }
  if (![...children.values()].some(set => set.size)) return null

  const rank = new Map(componentNodes.map(node => [node.id, 0]))
  const indegree = new Map(componentNodes.map(node => [node.id, parents.get(node.id).size]))
  const queue = componentNodes.filter(node => indegree.get(node.id) === 0).map(node => node.id).sort()
  const processed = new Set()
  while (queue.length) {
    const id = queue.shift()
    processed.add(id)
    for (const child of children.get(id)) {
      rank.set(child, Math.max(rank.get(child), rank.get(id) + 1))
      indegree.set(child, indegree.get(child) - 1)
      if (indegree.get(child) === 0) queue.push(child)
    }
  }
  // Una herencia cíclica no debe bloquear la organización ni cambiar el modelo.
  const cyclic = componentNodes.filter(node => !processed.has(node.id)).map(node => node.id)
  if (cyclic.length) {
    const base = Math.max(0, ...[...rank.values()]) + 1
    cyclic.sort().forEach((id, index) => rank.set(id, base + index % 2))
  }

  // Las clases conectadas por relaciones comunes se acercan a la jerarquía más próxima.
  const allAdjacent = new Map(componentNodes.map(node => [node.id, new Set()]))
  for (const edge of edges) {
    if (!ids.has(edge.source) || !ids.has(edge.target) || edge.source === edge.target) continue
    allAdjacent.get(edge.source).add(edge.target)
    allAdjacent.get(edge.target).add(edge.source)
  }
  const known = new Set([...parents].filter(([, value]) => value.size).map(([id]) => id))
  for (const [id, value] of children) if (value.size) known.add(id)
  let frontier = [...known]
  const assigned = new Set(frontier)
  for (let depth = 1; depth <= componentNodes.length && frontier.length; depth += 1) {
    const next = new Set()
    for (const id of frontier) {
      for (const neighbor of allAdjacent.get(id)) {
        if (assigned.has(neighbor)) continue
        assigned.add(neighbor)
        rank.set(neighbor, rank.get(id) + 1)
        next.add(neighbor)
      }
    }
    frontier = [...next]
  }
  return { rank, parents, children }
}

function layeredSeed(componentNodes, edges, hierarchy) {
  const ids = new Set(componentNodes.map(node => node.id))
  const neighbors = new Map(componentNodes.map(node => [node.id, new Set()]))
  for (const edge of edges) {
    if (ids.has(edge.source) && ids.has(edge.target) && edge.source !== edge.target) {
      neighbors.get(edge.source).add(edge.target)
      neighbors.get(edge.target).add(edge.source)
    }
  }
  let rank
  if (hierarchy) rank = hierarchy.rank
  else {
    const root = [...componentNodes].sort((a, b) => neighbors.get(b.id).size - neighbors.get(a.id).size || a.id.localeCompare(b.id))[0]
    rank = new Map(componentNodes.map(node => [node.id, 0]))
    const queue = root ? [root.id] : []
    const seen = new Set(queue)
    for (let cursor = 0; cursor < queue.length; cursor += 1) {
      const id = queue[cursor]
      for (const next of neighbors.get(id)) {
        if (seen.has(next)) continue
        seen.add(next)
        rank.set(next, rank.get(id) + 1)
        queue.push(next)
      }
    }
  }

  const layers = new Map()
  for (const node of componentNodes) {
    const value = rank.get(node.id) ?? 0
    if (!layers.has(value)) layers.set(value, [])
    layers.get(value).push(node)
  }
  const orderedRanks = [...layers.keys()].sort((a, b) => a - b)
  const order = new Map()
  for (const key of orderedRanks) {
    layers.get(key).sort((a, b) => a.y - b.y || a.id.localeCompare(b.id))
    layers.get(key).forEach((node, index) => order.set(node.id, index))
  }

  for (let sweep = 0; sweep < 8; sweep += 1) {
    const ranks = sweep % 2 === 0 ? orderedRanks : [...orderedRanks].reverse()
    for (const key of ranks) {
      const layer = layers.get(key)
      const previousRank = sweep % 2 === 0 ? key - 1 : key + 1
      const previous = layers.get(previousRank)
      if (!previous) continue
      const barycenter = node => {
        const values = [...neighbors.get(node.id)].map(id => order.get(id)).filter(Number.isFinite)
        return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : order.get(node.id)
      }
      layer.sort((a, b) => barycenter(a) - barycenter(b) || a.id.localeCompare(b.id))
      layer.forEach((node, index) => order.set(node.id, index))
    }
  }

  const rankWidths = orderedRanks.map(key => layers.get(key).reduce((sum, node) => sum + node.width, 0) + NODE_GAP * Math.max(0, layers.get(key).length - 1))
  const rankHeights = orderedRanks.map(key => Math.max(...layers.get(key).map(node => node.height)))
  const maxRankWidth = Math.max(0, ...rankWidths)
  let y = 0
  orderedRanks.forEach((key, rankIndex) => {
    const layer = layers.get(key)
    let x = (maxRankWidth - rankWidths[rankIndex]) / 2
    for (const node of layer) {
      node.x = x
      node.y = y
      x += node.width + NODE_GAP
    }
    y += rankHeights[rankIndex] + 100
  })
}

function forceRefine(componentNodes, edges, hierarchy) {
  const byId = new Map(componentNodes.map(node => [node.id, node]))
  const localEdges = edges.filter(edge => byId.has(edge.source) && byId.has(edge.target) && edge.source !== edge.target)
  if (componentNodes.length < 2) return
  const iterations = Math.min(220, 120 + componentNodes.length)
  const anchorX = componentNodes.reduce((sum, node) => sum + node.x, 0) / componentNodes.length
  const anchorY = componentNodes.reduce((sum, node) => sum + node.y, 0) / componentNodes.length
  const parentChild = hierarchy ? localEdges.filter(edge => edge.type === 'HERENCIA') : []

  for (let iteration = 0; iteration < iterations; iteration += 1) {
    const force = new Map(componentNodes.map(node => [node.id, { x: 0, y: 0 }]))
    for (let i = 0; i < componentNodes.length; i += 1) {
      const a = componentNodes[i]
      for (let j = i + 1; j < componentNodes.length; j += 1) {
        const b = componentNodes[j]
        let dx = a.x + a.width / 2 - (b.x + b.width / 2)
        let dy = a.y + a.height / 2 - (b.y + b.height / 2)
        let d = Math.hypot(dx, dy)
        if (d < 0.01) {
          const seed = ((i + 1) * 37 + (j + 1) * 19) % 360
          dx = Math.cos(seed * Math.PI / 180) * 0.1
          dy = Math.sin(seed * Math.PI / 180) * 0.1
          d = 0.1
        }
        const requiredX = (a.width + b.width) / 2 + NODE_GAP
        const requiredY = (a.height + b.height) / 2 + NODE_GAP
        const overlapX = requiredX - Math.abs(dx)
        const overlapY = requiredY - Math.abs(dy)
        let magnitude = 0
        if (overlapX > 0 && overlapY > 0) {
          magnitude = (Math.min(overlapX, overlapY) + 4) * 0.11
        } else {
          const desired = Math.hypot(requiredX, requiredY) * 0.82
          if (d < desired) magnitude = (desired - d) * 0.025
        }
        if (magnitude) {
          const fx = dx / d * magnitude
          const fy = dy / d * magnitude
          force.get(a.id).x += fx; force.get(a.id).y += fy
          force.get(b.id).x -= fx; force.get(b.id).y -= fy
        }
      }
    }

    for (const edge of localEdges) {
      const source = byId.get(edge.source), target = byId.get(edge.target)
      const sx = source.x + source.width / 2, sy = source.y + source.height / 2
      const tx = target.x + target.width / 2, ty = target.y + target.height / 2
      const dx = tx - sx, dy = ty - sy
      const d = Math.max(1, Math.hypot(dx, dy))
      const weight = edge.type === 'HERENCIA' ? 1.3 : edge.type === 'ASOCIACION' ? 1 : edge.type === 'AGREGACION' || edge.type === 'COMPOSICION' ? 0.9 : 0.72
      const spring = (d - Math.max(SPRING_LENGTH, (source.width + target.width) * 0.42)) * 0.009 * weight
      const fx = dx / d * spring, fy = dy / d * spring
      force.get(source.id).x += fx; force.get(source.id).y += fy
      force.get(target.id).x -= fx; force.get(target.id).y -= fy
    }

    for (const edge of parentChild) {
      const child = byId.get(edge.source), parent = byId.get(edge.target)
      const desiredY = parent.y + parent.height / 2 + child.height / 2 + NODE_GAP
      const violation = desiredY - (child.y + child.height / 2)
      if (violation > 0) {
        const amount = violation * 0.035
        force.get(parent.id).y -= amount
        force.get(child.id).y += amount
      }
    }

    const cooling = 1 - iteration / iterations
    const maxMove = 3.4 + cooling * 8
    for (const node of componentNodes) {
      const f = force.get(node.id)
      f.x += (anchorX - (node.x + node.width / 2)) * 0.003
      f.y += (anchorY - (node.y + node.height / 2)) * 0.003
      const magnitude = Math.hypot(f.x, f.y)
      if (!magnitude) continue
      const scale = Math.min(maxMove, magnitude) / magnitude
      node.x += f.x * scale
      node.y += f.y * scale
    }
  }
  resolveOverlaps(componentNodes)
  reduceCrossings(componentNodes, localEdges, parentChild)
  resolveOverlaps(componentNodes)
}

function resolveOverlaps(nodes) {
  for (let pass = 0; pass < 180; pass += 1) {
    let changed = false
    for (let i = 0; i < nodes.length; i += 1) {
      const a = nodes[i]
      for (let j = i + 1; j < nodes.length; j += 1) {
        const b = nodes[j]
        const dx = a.x + a.width / 2 - (b.x + b.width / 2)
        const dy = a.y + a.height / 2 - (b.y + b.height / 2)
        const overlapX = (a.width + b.width) / 2 + NODE_GAP - Math.abs(dx)
        const overlapY = (a.height + b.height) / 2 + NODE_GAP - Math.abs(dy)
        if (overlapX <= 0 || overlapY <= 0) continue
        changed = true
        const directionX = dx === 0 ? (a.id.localeCompare(b.id) < 0 ? -1 : 1) : Math.sign(dx)
        const directionY = dy === 0 ? (a.id.localeCompare(b.id) < 0 ? -1 : 1) : Math.sign(dy)
        if (overlapX < overlapY) {
          const move = overlapX / 2 + 1
          a.x += directionX * move; b.x -= directionX * move
        } else {
          const move = overlapY / 2 + 1
          a.y += directionY * move; b.y -= directionY * move
        }
      }
    }
    if (!changed) break
  }
}

function segmentsCross(a, b, c, d) {
  const orient = (p, q, r) => (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
  const abC = orient(a, b, c), abD = orient(a, b, d)
  const cdA = orient(c, d, a), cdB = orient(c, d, b)
  return abC * abD < -0.01 && cdA * cdB < -0.01
}

function nodeMoveCost(node, position, nodes, byId, incidentByNode, edges, hierarchyEdges) {
  const next = { ...node, ...position }
  let cost = 0
  for (const other of nodes) {
    if (other.id === node.id) continue
    const overlapX = (next.width + other.width) / 2 + NODE_GAP - Math.abs(next.x + next.width / 2 - other.x - other.width / 2)
    const overlapY = (next.height + other.height) / 2 + NODE_GAP - Math.abs(next.y + next.height / 2 - other.y - other.height / 2)
    if (overlapX > 0 && overlapY > 0) cost += overlapX * overlapY * 100
  }
  const pointForId = id => {
    if (id === node.id) return { x: next.x + next.width / 2, y: next.y + next.height / 2 }
    const value = byId.get(id)
    return { x: value.x + value.width / 2, y: value.y + value.height / 2 }
  }
  const incident = incidentByNode.get(node.id)
  for (const edge of incident) {
    cost += distance(pointForId(edge.source), pointForId(edge.target)) * 0.035
  }
  for (const edge of incident) {
    const ap = pointForId(edge.source), bp = pointForId(edge.target)
    for (const other of edges) {
      if (other === edge || [other.source, other.target].some(id => id === edge.source || id === edge.target)) continue
      if (segmentsCross(ap, bp, pointForId(other.source), pointForId(other.target))) cost += 180
    }
  }
  for (const edge of hierarchyEdges) {
    if (edge.source !== node.id && edge.target !== node.id) continue
    const child = byId.get(edge.source), parent = byId.get(edge.target)
    const childY = child.id === node.id ? next.y + next.height / 2 : child.y + child.height / 2
    const parentY = parent.id === node.id ? next.y + next.height / 2 : parent.y + parent.height / 2
    cost += Math.max(0, parentY + NODE_GAP - childY) * 1.8
  }
  return cost
}

function reduceCrossings(nodes, edges, hierarchyEdges) {
  if (edges.length < 2 || nodes.length > 70 || edges.length > 180) return
  const directions = Array.from({ length: 8 }, (_, index) => index * Math.PI / 4)
  const byId = new Map(nodes.map(node => [node.id, node]))
  const incidentByNode = new Map(nodes.map(node => [node.id, []]))
  for (const edge of edges) {
    incidentByNode.get(edge.source)?.push(edge)
    incidentByNode.get(edge.target)?.push(edge)
  }
  for (let pass = 0; pass < 2; pass += 1) {
    for (const node of [...nodes].sort((a, b) => b.degree - a.degree || a.id.localeCompare(b.id))) {
      const current = { x: node.x, y: node.y }
      let best = current
      let bestCost = nodeMoveCost(node, current, nodes, byId, incidentByNode, edges, hierarchyEdges)
      for (const radius of [28, 56, 92]) {
        for (const angle of directions) {
          const candidate = { x: current.x + Math.cos(angle) * radius, y: current.y + Math.sin(angle) * radius }
          const cost = nodeMoveCost(node, candidate, nodes, byId, incidentByNode, edges, hierarchyEdges)
          if (cost < bestCost - 0.1) { best = candidate; bestCost = cost }
        }
      }
      node.x = best.x; node.y = best.y
    }
  }
}

function packComponents(layouts) {
  const placed = []
  const gap = COMPONENT_GAP
  const ordered = [...layouts].sort((a, b) => b.width * b.height - a.width * a.height || a.nodes[0].id.localeCompare(b.nodes[0].id))
  for (const layout of ordered) {
    const candidates = [{ x: 0, y: 0 }]
    for (const item of placed) {
      candidates.push({ x: item.x + item.width + gap, y: item.y })
      candidates.push({ x: item.x, y: item.y + item.height + gap })
    }
    const valid = candidates.filter(candidate => placed.every(item =>
      candidate.x + layout.width + gap <= item.x || item.x + item.width + gap <= candidate.x ||
      candidate.y + layout.height + gap <= item.y || item.y + item.height + gap <= candidate.y
    ))
    let best = null
    for (const candidate of valid) {
      const maxX = Math.max(candidate.x + layout.width, ...placed.map(item => item.x + item.width))
      const maxY = Math.max(candidate.y + layout.height, ...placed.map(item => item.y + item.height))
      const aspectPenalty = Math.abs(Math.log(Math.max(maxX, 1) / Math.max(maxY, 1))) * 0.12
      const score = maxX * maxY * (1 + aspectPenalty) + (candidate.x + candidate.y) * 12
      if (!best || score < best.score) best = { ...candidate, score }
    }
    const position = best || { x: 0, y: placed.reduce((sum, item) => Math.max(sum, item.y + item.height + gap), 0) }
    for (const node of layout.nodes) { node.x += position.x; node.y += position.y }
    placed.push({ x: position.x, y: position.y, width: layout.width, height: layout.height })
  }
}

const relationshipWeight = type => type === 'COMPOSICION' ? 1.6 : type === 'HERENCIA' ? 1.4 : type === 'AGREGACION' ? 1.25 : type === 'DEPENDENCIA' ? 0.8 : 1

/** Infers semantic tiers from the dominant hub and directed UML relationship roles. */
export function getUmlSemanticRanks(inputNodes, inputEdges) {
  const ids = new Set(inputNodes.map(node => String(node.id)))
  const valid = inputEdges.filter(edge => ids.has(String(edge.source)) && ids.has(String(edge.target)) && edge.source !== edge.target)
  if (!inputNodes.length) return new Map()
  const adjacency = new Map(inputNodes.map(node => [String(node.id), new Set()]))
  const degree = new Map(inputNodes.map(node => [String(node.id), 0]))
  for (const edge of valid) {
    const source = String(edge.source), target = String(edge.target)
    adjacency.get(source).add(target); adjacency.get(target).add(source)
    const weight = relationshipWeight(edge.type)
    degree.set(source, degree.get(source) + weight); degree.set(target, degree.get(target) + weight)
  }
  // Approximate betweenness with shortest-path usage, then favor the best connected domain axis.
  const betweenness = new Map(inputNodes.map(node => [String(node.id), 0]))
  for (const start of adjacency.keys()) {
    const queue = [start], parent = new Map(), dist = new Map([[start, 0]])
    for (let i = 0; i < queue.length; i += 1) for (const next of adjacency.get(queue[i])) {
      if (dist.has(next)) continue
      dist.set(next, dist.get(queue[i]) + 1); parent.set(next, queue[i]); queue.push(next)
    }
    for (const target of queue) {
      if (target === start) continue
      for (let cursor = parent.get(target); cursor && cursor !== start; cursor = parent.get(cursor)) betweenness.set(cursor, betweenness.get(cursor) + 1)
    }
  }
  const core = [...degree.keys()].sort((a, b) => degree.get(b) * 10 + betweenness.get(b) - degree.get(a) * 10 - betweenness.get(a) || a.localeCompare(b))[0]
  const flow = valid.map(edge => {
    const source = String(edge.source), target = String(edge.target)
    if (edge.type === 'HERENCIA' || edge.type === 'DEPENDENCIA') return { source: target, target: source, type: edge.type }
    return { source, target, type: edge.type }
  })
  const incoming = new Map(inputNodes.map(node => [String(node.id), []]))
  const outgoing = new Map(inputNodes.map(node => [String(node.id), []]))
  for (const edge of flow) { incoming.get(edge.target).push(edge); outgoing.get(edge.source).push(edge) }
  const ranks = new Map([[core, 0]])
  const walk = (initial, nextEdges, rankDelta, preserve = false) => {
    const queue = [initial]
    for (let index = 0; index < queue.length; index += 1) for (const edge of nextEdges(queue[index])) {
      const id = edge.target ?? edge.source
      if (ranks.has(id)) continue
      ranks.set(id, ranks.get(queue[index]) + rankDelta)
      queue.push(id)
    }
  }
  walk(core, id => incoming.get(id).map(edge => ({ target: edge.source })), -1)
  walk(core, id => outgoing.get(id), 1)

  // A sink fed by multiple entities is usually a shared master/context class.
  for (const node of inputNodes) {
    const id = String(node.id)
    if (id === core || incoming.get(id).length < 2 || outgoing.get(id).length !== 0) continue
    ranks.set(id, -1)
  }
  for (const node of inputNodes) {
    const id = String(node.id)
    if (id === core || ranks.has(id)) continue
    const dependentMaster = outgoing.get(id).find(edge => ranks.get(edge.target) === -1)
    if (dependentMaster) ranks.set(id, 2)
  }
  // Direct, single-use outputs read better on the outer tier than beside the hub.
  for (const edge of flow) {
    if (edge.source === core && edge.type === 'ASOCIACION' && degree.get(edge.target) <= 1.01) ranks.set(edge.target, 2)
  }
  for (const node of inputNodes) if (!ranks.has(String(node.id))) ranks.set(String(node.id), 1)
  return ranks
}

function semanticLayeredSeed(nodes, edges) {
  const ranks = getUmlSemanticRanks(nodes, edges)
  const adjacent = new Map(nodes.map(node => [node.id, []]))
  for (const edge of edges) {
    adjacent.get(edge.source)?.push(edge.target)
    adjacent.get(edge.target)?.push(edge.source)
  }
  const layers = new Map()
  for (const node of nodes) {
    const rank = ranks.get(node.id) ?? 0
    if (!layers.has(rank)) layers.set(rank, [])
    layers.get(rank).push(node)
  }
  const levels = [...layers.keys()].sort((a, b) => a - b)
  const order = new Map()
  for (const rank of levels) {
    layers.get(rank).sort((a, b) => a.id.localeCompare(b.id))
    layers.get(rank).forEach((node, index) => order.set(node.id, index))
  }
  for (let sweep = 0; sweep < 6; sweep += 1) {
    const ranksToVisit = sweep % 2 ? [...levels].reverse() : levels
    for (const rank of ranksToVisit) {
      const layer = layers.get(rank)
      layer.sort((a, b) => {
        const barycenter = node => {
          const neighbors = adjacent.get(node.id).map(id => order.get(id)).filter(Number.isFinite)
          return neighbors.length ? neighbors.reduce((sum, value) => sum + value, 0) / neighbors.length : order.get(node.id)
        }
        return barycenter(a) - barycenter(b) || a.id.localeCompare(b.id)
      })
      layer.forEach((node, index) => order.set(node.id, index))
    }
  }
  const rowWidths = levels.map(rank => layers.get(rank).reduce((sum, node) => sum + node.width, 0) + NODE_GAP * Math.max(0, layers.get(rank).length - 1))
  const widest = Math.max(...rowWidths)
  let y = 0
  levels.forEach((rank, index) => {
    const layer = layers.get(rank)
    let x = (widest - rowWidths[index]) / 2
    for (const node of layer) { node.x = x; node.y = y; x += node.width + NODE_GAP }
    y += Math.max(...layer.map(node => node.height)) + 104
  })
}

/** Topology-aware, size-aware arrangement; it returns positions only and never edits UML data. */
export function layoutUmlGraph(inputNodes, inputEdges) {
  const nodes = inputNodes.map(node => ({
    id: String(node.id),
    x: Number.isFinite(node.x) ? node.x : 0,
    y: Number.isFinite(node.y) ? node.y : 0,
    width: Number.isFinite(node.width) && node.width > 0 ? node.width : 240,
    height: Number.isFinite(node.height) && node.height > 0 ? node.height : 120,
    degree: 0
  })).sort((a, b) => a.id.localeCompare(b.id))
  if (!nodes.length) return []
  const ids = new Set(nodes.map(node => node.id))
  const edges = inputEdges.filter(edge => ids.has(edge.source) && ids.has(edge.target)).map(edge => ({ ...edge, source: String(edge.source), target: String(edge.target) }))
  for (const edge of edges) {
    const source = nodes.find(node => node.id === edge.source), target = nodes.find(node => node.id === edge.target)
    if (source && target && source.id !== target.id) { source.degree += 1; target.degree += 1 }
  }

  const layouts = getComponents(nodes, edges).map(component => {
    const idsInComponent = new Set(component.ids)
    const localNodes = nodes.filter(node => idsInComponent.has(node.id)).map(node => ({ ...node }))
    const localEdges = edges.filter(edge => idsInComponent.has(edge.source) && idsInComponent.has(edge.target))
    if (localNodes.length > 1) {
      semanticLayeredSeed(localNodes, localEdges)
      resolveOverlaps(localNodes)
    }
    const minX = Math.min(...localNodes.map(node => node.x)), minY = Math.min(...localNodes.map(node => node.y))
    for (const node of localNodes) { node.x -= minX; node.y -= minY }
    return {
      nodes: localNodes,
      width: Math.max(...localNodes.map(node => node.x + node.width)),
      height: Math.max(...localNodes.map(node => node.y + node.height))
    }
  })

  packComponents(layouts)
  const all = layouts.flatMap(layout => layout.nodes)
  const margin = 80
  const minX = Math.min(...all.map(node => node.x)), minY = Math.min(...all.map(node => node.y))
  return all.map(node => ({ id: node.id, x: Math.round(node.x - minX + margin), y: Math.round(node.y - minY + margin) }))
}
