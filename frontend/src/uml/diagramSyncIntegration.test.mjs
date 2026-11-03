import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('diagram mutations share a per-diagram serial queue and only rebase after this client confirms a write', async () => {
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(canvas, /diagramRequestCoordinator\.enqueueWrite\(diagramId, perform\)/)
  assert.match(canvas, /expectedAfterEarlierLocalWrite\(diagramId, suppliedRevision\)/)
  assert.match(canvas, /confirmLocalWrite\(diagramId, revision\)/)
  assert.match(canvas, /requestWithRevision\(options, rebasedRevision\)/)
})

test('initial load, WebSocket and polling accept only the newest server revision', async () => {
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(canvas, /if \(!acceptServerRevision\(def\.id, full\.diagram\.revision\)\) return/)
  assert.match(canvas, /if \(!acceptServerRevision\(currentDiagramId, full\.diagram\.revision\)\) return/)
  assert.match(canvas, /if \(!acceptServerRevision\(diagram\.id, full\.diagram\.revision\)\) return/)
  assert.match(canvas, /if \(isStaleDiagramRevision\(currentRevision, revision\)\) return false/)
})

test('409 on moving classes preserves their local positions as reviewable pending data', async () => {
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  const dragSave = canvas.slice(canvas.indexOf('const handleNodeDragStop'), canvas.indexOf('// Crear nuevo elemento'))
  assert.match(dragSave, /mergePendingPositions\(full\.elements, getOfflineQueue\(currentDiagram\.id\)\)/)
  assert.match(dragSave, /payload: \{ positions \}[\s\S]*?true/)
  assert.match(canvas, /queue\.some\(action => action\.requiresReview\)/)
  assert.match(canvas, /const retryQueue = queue\.map\(action => \(\{ \.\.\.action, requiresReview: false \}\)\)/)
  assert.match(canvas, /window\.confirm\([\s\S]*?la revisión actual del servidor/)
  assert.match(canvas, /const localPending = pendingQueue\.length > 0 \? loadLocalDiagramSnapshot\(def\.id\) : null/)
  assert.match(canvas, /const displayElements = localPending\?\.elements \?\? mergePendingPositions\(full\.elements, pendingQueue\)/)
})

test('the UI exposes explicit conflict retry and never drops the 409 guard', async () => {
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(workspace, /Revisar y reintentar/)
  assert.match(workspace, /p\.savingStatus\.includes\('Conflicto'\)/)
  assert.match(canvas, /error\.status === 409/)
})

test('arranged edges replace stale source and target sides after node movement', async () => {
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(canvas, /normalizeRelationEndpoint\(handle, type, fallbackPosition\.toLowerCase\(\)/)
})
