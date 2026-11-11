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
  assert.match(source, /<AgentSpeechProvider/)
})
