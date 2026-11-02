import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compareVersions } from './versionDiff.mjs'

const item = (id, nombre, props = '{}', x = 0) => ({ id, nombre, propiedadesJson: props, posicionX: x, posicionY: 0, tipoElemento: 'CLASE' })
const rel = (id, multiplicidadDestino = '1') => ({ id, tipoRelacion: 'ASOCIACION', elementoOrigen: 'a', elementoDestino: 'b', multiplicidadOrigen: '*', multiplicidadDestino: multiplicidadDestino })

test('compara clases, atributos, métodos y relaciones de forma determinista', () => {
  const before = { elements: [item('a', 'Cliente', JSON.stringify({ attributes: [{ id: 'x', nombre: 'nombre', tipo: 'String' }], methods: [{ id: 'm', nombre: 'buscar' }] }))], relations: [rel('r')] }
  const after = { elements: [item('a', 'Cliente', JSON.stringify({ attributes: [{ id: 'x', nombre: 'nombre', tipo: 'Text' }, { id: 'y', nombre: 'activo' }], methods: [{ id: 'm', nombre: 'buscar', tipo: 'void' }] })), item('b', 'Venta')], relations: [rel('r', '0..*')] }
  const diff = compareVersions(before, after)
  assert.deepEqual(diff.map(change => `${change.entity}:${change.action}`).sort(), [
    'ATRIBUTO:AGREGADO', 'ATRIBUTO:MODIFICADO', 'CLASE:AGREGADO', 'MÉTODO:MODIFICADO', 'RELACIÓN:MODIFICADO'
  ])
  assert.deepEqual(compareVersions(before, after), diff)
})

test('reports deleted classes and relations', () => {
  const changes = compareVersions({ elements: [item('a', 'Cliente')], relations: [rel('r')] }, { elements: [], relations: [] })
  assert.deepEqual(changes.map(change => change.action), ['ELIMINADO', 'ELIMINADO'])
})

test('historical canvas is a local read-only projection and returning fetches the latest model', () => {
  const source = readFileSync(new URL('./UmlCanvas.tsx', import.meta.url), 'utf8')
  assert.match(source, /historicalView !== null/)
  assert.match(source, /if \(!historicalView\) return previewGraph/)
  assert.match(source, /onNodesChange=\{canvasProposal\.active && !historicalView \? undefined : handleFlowNodesChange\}/)
  assert.match(source, /const dimensions = changes\.filter\(change => change\.type === 'dimensions'\)/)
  assert.match(source, /setHistoricalNodeMeasurements\(current =>/)
  assert.match(source, /!historicalView && <MiniMap/)
  assert.match(source, /historicalView \? 'is-historical-view' : ''/)
  assert.match(source, /if \(historicalView\) return/)
  assert.match(source, /async function returnToCurrentVersion\(\)[\s\S]*?request<DiagramFullView>\(/)
  assert.match(source, /setHistoricalView\(null\)/)
})

test('history UI reads snapshots, requires restore confirmation and uses an idempotency key', () => {
  const source = readFileSync(new URL('./VersionHistoryModal.tsx', import.meta.url), 'utf8')
  assert.match(source, /versions\/\$\{version\.id\}/)
  assert.match(source, /!confirm\(/)
  assert.match(source, /'Idempotency-Key': key/)
  assert.match(source, /onCompare\(before, pair\[0\], after, pair\[1\]\)/)
})
