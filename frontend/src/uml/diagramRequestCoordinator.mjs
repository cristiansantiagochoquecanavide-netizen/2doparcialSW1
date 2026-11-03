export function createDiagramRequestCoordinator() {
  const latestRevisions = new Map()
  const localWriteRevisions = new Map()
  const writeTails = new Map()

  function observeRevision(diagramId, revision) {
    const value = Number(revision)
    if (!diagramId || !Number.isSafeInteger(value) || value < 0) return latestRevisions.get(diagramId) ?? null
    const latest = latestRevisions.get(diagramId)
    const confirmed = latest == null ? value : Math.max(latest, value)
    latestRevisions.set(diagramId, confirmed)
    return confirmed
  }

  function confirmLocalWrite(diagramId, revision) {
    const confirmed = observeRevision(diagramId, revision)
    if (confirmed == null) return null
    const previous = localWriteRevisions.get(diagramId)
    localWriteRevisions.set(diagramId, previous == null ? confirmed : Math.max(previous, confirmed))
    return confirmed
  }

  function expectedAfterEarlierLocalWrite(diagramId, requestedRevision) {
    if (requestedRevision == null) return requestedRevision
    const requested = Number(requestedRevision)
    const local = localWriteRevisions.get(diagramId)
    if (!Number.isSafeInteger(requested) || local == null || requested >= local) return requestedRevision
    return local
  }

  function enqueueWrite(diagramId, operation) {
    const previous = writeTails.get(diagramId) ?? Promise.resolve()
    const task = previous.catch(() => undefined).then(operation)
    writeTails.set(diagramId, task.then(() => undefined, () => undefined))
    return task
  }

  return {
    observeRevision,
    confirmLocalWrite,
    expectedAfterEarlierLocalWrite,
    enqueueWrite,
    revision: (diagramId) => latestRevisions.get(diagramId) ?? null
  }
}

export function isStaleDiagramRevision(currentRevision, incomingRevision) {
  const current = Number(currentRevision)
  const incoming = Number(incomingRevision)
  return Number.isSafeInteger(current) && Number.isSafeInteger(incoming) && incoming < current
}

export function mergePendingPositions(elements, queue) {
  const positions = new Map()
  for (const action of queue) {
    if (action.type !== 'SAVE_POSITIONS' || !Array.isArray(action.payload?.positions)) continue
    for (const position of action.payload.positions) {
      if (position && typeof position.id === 'string') positions.set(position.id, position)
    }
  }
  return elements.map(element => {
    const position = positions.get(element.id)
    return position ? { ...element, posicionX: position.posicionX, posicionY: position.posicionY } : element
  })
}

export function markPendingQueueForReview(queue) {
  return queue.map(action => ({ ...action, requiresReview: true }))
}

export function normalizeRelationEndpoint(handle, role, preferredSide, fallbackSlot) {
  const match = typeof handle === 'string' ? /^(top|right|bottom|left)-(source|target)(?:-(0|1|2))?$/.exec(handle) : null
  if (!match || match[2] !== role || match[1] !== preferredSide) return `${preferredSide}-${role}-${fallbackSlot}`
  return `${match[1]}-${role}-${match[3] ?? '0'}`
}
