import test from 'node:test'
import assert from 'node:assert/strict'
import { createDiagramRequestCoordinator, isStaleDiagramRevision, markPendingQueueForReview, mergePendingPositions, normalizeRelationEndpoint } from './diagramRequestCoordinator.mjs'

test('serializes same-client writes per diagram and continues after a failed request', async () => {
  const coordinator = createDiagramRequestCoordinator()
  const events = []
  let releaseFirst
  const first = coordinator.enqueueWrite('diagram-a', async () => {
    events.push('first-start')
    await new Promise(resolve => { releaseFirst = resolve })
    events.push('first-end')
    return 1
  })
  const second = coordinator.enqueueWrite('diagram-a', async () => {
    events.push('second-start')
    throw new Error('real conflict')
  })
  const otherDiagram = coordinator.enqueueWrite('diagram-b', async () => {
    events.push('other-diagram')
  })

  await otherDiagram
  assert.deepEqual(events, ['first-start', 'other-diagram'])
  releaseFirst()
  await first
  await assert.rejects(second, /real conflict/)
  assert.deepEqual(events, ['first-start', 'other-diagram', 'first-end', 'second-start'])
})

test('rapid same-user drag saves are serialized and consume each confirmed revision', async () => {
  const coordinator = createDiagramRequestCoordinator()
  const sentRevisions = []
  coordinator.observeRevision('diagram-a', 4)
  const save = () => coordinator.enqueueWrite('diagram-a', async () => {
    const requested = coordinator.expectedAfterEarlierLocalWrite('diagram-a', 4)
    sentRevisions.push(requested)
    const confirmed = requested + 1
    coordinator.confirmLocalWrite('diagram-a', confirmed)
    return confirmed
  })

  await Promise.all(Array.from({ length: 10 }, save))
  assert.deepEqual(sentRevisions, Array.from({ length: 10 }, (_, index) => index + 4))
  assert.equal(coordinator.revision('diagram-a'), 14)
})

test('only a confirmed earlier local write rebases this client; another collaborator remains a conflict', () => {
  const firstClient = createDiagramRequestCoordinator()
  const secondClient = createDiagramRequestCoordinator()
  firstClient.observeRevision('diagram-a', 8)
  secondClient.observeRevision('diagram-a', 8)

  firstClient.confirmLocalWrite('diagram-a', 9)
  assert.equal(firstClient.expectedAfterEarlierLocalWrite('diagram-a', 8), 9)
  assert.equal(secondClient.expectedAfterEarlierLocalWrite('diagram-a', 8), 8)

  secondClient.observeRevision('diagram-a', 10)
  assert.equal(secondClient.expectedAfterEarlierLocalWrite('diagram-a', 8), 8)
  assert.equal(secondClient.revision('diagram-a'), 10)
})

test('a collaborator that submits an old revision still receives a protected 409', async () => {
  const firstClient = createDiagramRequestCoordinator()
  const secondClient = createDiagramRequestCoordinator()
  let serverRevision = 3
  firstClient.observeRevision('diagram-a', serverRevision)
  secondClient.observeRevision('diagram-a', serverRevision)
  const serverWrite = expected => {
    if (expected !== serverRevision) {
      const error = new Error('optimistic revision conflict')
      error.status = 409
      throw error
    }
    serverRevision += 1
    return serverRevision
  }
  await firstClient.enqueueWrite('diagram-a', async () => {
    const expected = firstClient.expectedAfterEarlierLocalWrite('diagram-a', 3)
    const confirmed = serverWrite(expected)
    firstClient.confirmLocalWrite('diagram-a', confirmed)
  })

  await assert.rejects(secondClient.enqueueWrite('diagram-a', async () => {
    const expected = secondClient.expectedAfterEarlierLocalWrite('diagram-a', 3)
    assert.equal(expected, 3)
    return serverWrite(expected)
  }), error => error.status === 409)
  assert.notEqual(secondClient.expectedAfterEarlierLocalWrite('diagram-a', 3), serverRevision)
  assert.equal(serverRevision, 4, 'stale collaborator write did not advance or overwrite server state')
})

test('an older WebSocket or poll response cannot replace a newer diagram revision', () => {
  assert.equal(isStaleDiagramRevision(12, 11), true)
  assert.equal(isStaleDiagramRevision(12, 12), false)
  assert.equal(isStaleDiagramRevision(null, 1), false)
})

test('a real conflict marks local actions for explicit review and rebases only their positions onto fresh server data', () => {
  const serverElements = [
    { id: 'a', nombre: 'A', posicionX: 400, posicionY: 200 },
    { id: 'b', nombre: 'B', posicionX: 700, posicionY: 300 }
  ]
  const pending = [{ id: 'q1', type: 'SAVE_POSITIONS', payload: { positions: [{ id: 'a', posicionX: 100, posicionY: 80 }] } }]
  const reviewed = markPendingQueueForReview(pending)
  const merged = mergePendingPositions(serverElements, reviewed)

  assert.equal(reviewed[0].requiresReview, true)
  assert.deepEqual(merged, [
    { id: 'a', nombre: 'A', posicionX: 100, posicionY: 80 },
    serverElements[1]
  ])
  assert.equal(serverElements[0].posicionX, 400, 'the fresh server snapshot is not mutated')
})

test('relation endpoints follow the new class geometry after auto-layout instead of reusing an obsolete side', () => {
  assert.equal(normalizeRelationEndpoint('right-source-2', 'source', 'bottom', 1), 'bottom-source-1')
  assert.equal(normalizeRelationEndpoint('top-target-0', 'target', 'left', 2), 'left-target-2')
  assert.equal(normalizeRelationEndpoint('bottom-source-2', 'source', 'bottom', 0), 'bottom-source-2')
})
