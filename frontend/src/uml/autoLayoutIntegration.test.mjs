import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('CU22 layout logic remains available internally but its toolbar button is hidden', async () => {
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')

  const visibleActions = workspace.slice(workspace.indexOf('className="canvas-heading-actions"'), workspace.indexOf('<div className="canvas-frame"'))
  assert.doesNotMatch(visibleActions, /Organizar|organize-diagram-button|p\.onArrangeDiagram/)
  assert.doesNotMatch(workspace, /Organizar diagrama/)
  assert.equal((workspace.match(/p\.onArrangeDiagram/g) ?? []).length, 0, 'the hidden organizer must not remain exposed by the workspace chrome')
  assert.doesNotMatch(workspace.slice(workspace.indexOf('<div className="project-menu">'), workspace.indexOf('</details>', workspace.indexOf('<div className="project-menu">'))), /Organizar diagrama/)
  assert.match(canvas, /const handleArrangeDiagram/)
})

test('CU22 uses measured React Flow bounds and the existing collaborative position save path', async () => {
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  const callback = canvas.slice(canvas.indexOf('const handleArrangeDiagram'), canvas.indexOf('const selectedNodeId'))

  assert.match(callback, /node\.measured\?\.width/)
  assert.match(callback, /node\.measured\?\.height/)
  assert.match(callback, /layoutUmlGraph\(/)
  assert.match(callback, /scoreUmlCandidates\(/)
  assert.match(callback, /routeUmlEdges\(/)
  assert.match(callback, /routeLabelPoint/)
  assert.match(canvas, /function UmlRoutedEdge\(/)
  assert.match(canvas, /type: 'umlRouted'/)
  assert.match(callback, /await handleNodeDragStop\(null, movedNodes\[0\], movedNodes\)/)
  assert.doesNotMatch(callback, /versions\/|snapshot|restore|handleCreate|setRelationsRaw/)
})
