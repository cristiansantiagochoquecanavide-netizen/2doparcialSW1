import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('UML workspace exposes the centered project header and participant menu without retired controls', async () => {
  const source = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')
  assert.match(source, /className="topbar-title"[\s\S]*\{p\.projectName\}/)
  assert.match(source, /className="participants-menu"[\s\S]*Participantes/)
  assert.match(source, /className=\{`workspace-state /)
  assert.match(source, /Asistente COIN/)
  assert.doesNotMatch(source, /Crear comentario|Colaboradores \{|Espacio de Equipo|workspace-footer|AgentSpeechControls/)
  assert.doesNotMatch(source, /Organizar|Colaboración/)
  assert.match(source, /<AgentSpeechProvider/)
})

test('UML canvas theme gives classes and relationship labels high contrast on the dark work surface', async () => {
  const styles = await readFile(new URL('../styles.css', import.meta.url), 'utf8')
  const canvas = await readFile(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(styles, /\.uml-node-card[\s\S]*background: #171e2a; color: #f4f6fb/)
  assert.match(styles, /\.react-flow__edge-text[\s\S]*background: #171e2a; color: #f2f4fa/)
  assert.match(canvas, /color="#31394a"/)
  assert.match(canvas, /stroke: isComposition \? '#b9a9ff' : isAggregation \? '#43d9c5' : '#c4cde0'/)
})
