/** Render-only diff over the CURRENT diagram. Never send this projection to persistence. */
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const propertiesOf = element => { try { return JSON.parse(element?.propiedadesJson || '{}') } catch { return {} } }
export function projectCanvasProposal(elements, relations, proposal, authorizedPrivate = false) {
  const classes = new Map(elements.map(e => [e.id, e])), edges = new Map(relations.map(e => [e.id, e]))
  const marks = {}, relationMarks = {}
  if (!proposal || (proposal.scope !== 'GROUP' && !(authorizedPrivate && proposal.scope === 'PRIVATE')) || !['PENDIENTE', 'CONFLICTO'].includes(proposal.status)) return { elements, relations, marks, relationMarks }
  for (const change of proposal.preview.changes) {
    const value = change.after || change.before
    if (!value) continue
    const current = (change.entity === 'CLASS' ? classes : edges).get(change.id)
    // Never resurrect missing targets or overwrite an existing UUID with an outdated CREATE.
    if ((change.kind !== 'CREATE' && !current) || (change.kind === 'CREATE' && current)) continue
    if (change.entity === 'CLASS') {
      const before = change.before?.properties || {}, after = change.after?.properties || {}
      const properties = structuredClone(change.kind === 'CREATE' ? after : propertiesOf(current))
      const members = {}, fields = []
      for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
        if (key === 'attributes' || key === 'methods' || equal(before[key], after[key]) || change.kind === 'DELETE') continue
        if (after[key] === undefined) delete properties[key]
        else properties[key] = structuredClone(after[key])
        fields.push(key)
      }
      for (const group of ['attributes', 'methods']) {
        const old = new Map((before[group] || []).map(m => [m.id, m]))
        const next = new Map((after[group] || []).map(m => [m.id, m]))
        properties[group] ||= []
        properties[group] = properties[group].map(member => {
          if (change.kind === 'DELETE') { members[member.id] = 'DELETE'; return member }
          if (change.kind === 'CREATE') { members[member.id] = 'CREATE'; return member }
          if (old.has(member.id) && !next.has(member.id)) { members[member.id] = 'DELETE'; return member }
          if (old.has(member.id) && !equal(old.get(member.id), next.get(member.id))) { members[member.id] = 'UPDATE'; return structuredClone(next.get(member.id)) }
          return member
        })
        for (const member of after[group] || []) if (!old.has(member.id) && !properties[group].some(m => m.id === member.id)) {
          properties[group].push(structuredClone(member)); members[member.id] = 'CREATE'
        }
      }
      const result = current ? { ...current } : { id: change.id, diagramaId: proposal.diagramId, posicionX: value.x, posicionY: value.y }
      for (const [key, target] of Object.entries({ name: 'nombre', type: 'tipoElemento' })) if (change.kind === 'CREATE' || (change.kind === 'UPDATE' && !equal(change.before?.[key], change.after?.[key]))) {
        result[target] = value[key]; fields.push(key)
      }
      result.propiedadesJson = JSON.stringify(properties)
      classes.set(change.id, result)
      marks[change.id] = { kind: change.kind, members, fields }
    } else {
      const result = current ? { ...current } : { id: change.id, diagramaId: proposal.diagramId }
      const fields = { source: 'elementoOrigen', destination: 'elementoDestino', name: 'nombreRelacion', type: 'tipoRelacion', sourceMultiplicity: 'multiplicidadOrigen', targetMultiplicity: 'multiplicidadDestino' }
      for (const [key, target] of Object.entries(fields)) if (change.kind === 'CREATE' || (change.kind === 'UPDATE' && !equal(change.before?.[key], change.after?.[key]))) result[target] = value[key]
      edges.set(change.id, result)
      relationMarks[change.id] = change.kind
    }
  }
  return { elements: [...classes.values()], relations: [...edges.values()], marks, relationMarks }
}

/** Keep all existing React Flow objects, geometry, measurements and connection handles. */
export function overlayCanvasGraph(nodes, edges, changedNodes, changedEdges) {
  const nodeDiff = new Map(changedNodes.map(n => [n.id, n])), edgeDiff = new Map(changedEdges.map(e => [e.id, e]))
  const resultNodes = nodes.map(node => {
    const overlay = nodeDiff.get(node.id)
    if (!overlay) return node
    nodeDiff.delete(node.id)
    return { ...node, data: { ...node.data, ...overlay.data }, position: node.position }
  })
  const resultEdges = edges.map(edge => {
    const overlay = edgeDiff.get(edge.id)
    if (!overlay) return edge
    edgeDiff.delete(edge.id)
    return { ...edge, ...overlay, sourceHandle: overlay.source === edge.source ? edge.sourceHandle : overlay.sourceHandle, targetHandle: overlay.target === edge.target ? edge.targetHandle : overlay.targetHandle }
  })
  return { nodes: [...resultNodes, ...[...nodeDiff.values()].filter(n => n.data.previewKind === 'CREATE')], edges: [...resultEdges, ...[...edgeDiff.values()].filter(e => e.data?.previewKind === 'CREATE')] }
}

export function affectedPreviewNodeIds(proposal, nodes, edges) {
  const ids = new Set(), present = new Set(nodes.map(n => n.id))
  for (const change of proposal?.preview.changes || []) {
    if (change.entity === 'CLASS') ids.add(change.id)
    else {
      const edge = edges.find(e => e.id === change.id), value = change.after || change.before
      for (const id of [edge?.source, edge?.target, value?.source, value?.destination]) if (id) ids.add(id)
    }
  }
  return [...ids].filter(id => present.has(id))
}

export function greenPreviewEdge(edge, kind) {
  if (!kind) return edge
  const marker = value => typeof value === 'object' && value ? { ...value, color: '#15803d' } : value
  return { ...edge, data: { ...edge.data, previewKind: kind }, className: 'acodi-preview-edge', label: `${kind === 'DELETE' ? 'Eliminar · ' : ''}${edge.label || ''}`, style: { ...edge.style, stroke: '#15803d', strokeWidth: 3, strokeDasharray: kind === 'DELETE' ? '7 4' : edge.style?.strokeDasharray }, labelStyle: { fill: '#15803d' }, markerStart: marker(edge.markerStart), markerEnd: marker(edge.markerEnd) }
}
