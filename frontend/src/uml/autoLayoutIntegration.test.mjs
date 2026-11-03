import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('CU22 is available from existing tools and respects edit/history/proposal states', async () => {
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')

  const visibleActions = workspace.slice(workspace.indexOf('className="canvas-heading-actions"'), workspace.indexOf('<div className="canvas-frame"'))
  assert.match(visibleActions, /className="quiet-button organize-diagram-button"/)
  assert.match(visibleActions, /title="Organizar diagrama"/)
  assert.match(visibleActions, /onClick=\{p\.onArrangeDiagram\}/)
  assert.equal((workspace.match(/onClick=\{p\.onArrangeDiagram\}/g) ?? []).length, 1, 'the organizer action must not be duplicated in More tools')
  assert.doesNotMatch(workspace.slice(workspace.indexOf('<div className="project-menu">'), workspace.indexOf('</details>', workspace.indexOf('<div className="project-menu">'))), /Organizar diagrama/)
  assert.match(workspace, /disabled=\{isEditingDisabled \|\| p\.arrangingDiagram \|\| p\.arrangeDisabled \|\| p\.elements\.length < 2\}/)
  assert.match(canvas, /arrangeDisabled=\{Boolean\(historicalView \|\| canvasProposal\.active\)\}/)
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
