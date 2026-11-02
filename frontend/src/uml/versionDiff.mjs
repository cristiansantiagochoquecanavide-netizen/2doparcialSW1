function parseProperties(element) {
  try { return JSON.parse(element.propiedadesJson || '{}') } catch { return {} }
}

function keyed(items, kind, className, changes) {
  const key = item => item.id || `${item.nombre || item.name || ''}:${item.tipo || item.type || ''}`
  const oldMap = new Map((items.before || []).map(item => [key(item), item]))
  const newMap = new Map((items.after || []).map(item => [key(item), item]))
  for (const id of [...oldMap.keys()].filter(id => !newMap.has(id)).sort())
    changes.push({ entity: kind, action: 'ELIMINADO', id: `${className}:${id}`, label: itemLabel(oldMap.get(id)) })
  for (const id of [...newMap.keys()].filter(id => !oldMap.has(id)).sort())
    changes.push({ entity: kind, action: 'AGREGADO', id: `${className}:${id}`, label: itemLabel(newMap.get(id)) })
  for (const id of [...newMap.keys()].filter(id => oldMap.has(id)).sort())
    if (stable(oldMap.get(id)) !== stable(newMap.get(id)))
      changes.push({ entity: kind, action: 'MODIFICADO', id: `${className}:${id}`, label: itemLabel(newMap.get(id)) })
}

const itemLabel = item => item.nombre || item.name || item.id || 'Elemento'
const stable = value => JSON.stringify(sortDeep(value))
function sortDeep(value) {
  if (Array.isArray(value)) return value.map(sortDeep)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.keys(value).sort().map(key => [key, sortDeep(value[key])]))
}

export function compareVersions(before, after) {
  const changes = []
  if (before?.diagram?.nombre !== after?.diagram?.nombre || before?.diagram?.descripcion !== after?.diagram?.descripcion)
    changes.push({ entity: 'DIAGRAMA', action: 'MODIFICADO', id: 'diagram', label: after?.diagram?.nombre || 'Diagrama' })
  const oldClasses = new Map((before?.elements || []).map(item => [item.id, item]))
  const newClasses = new Map((after?.elements || []).map(item => [item.id, item]))
  for (const id of [...oldClasses.keys()].filter(id => !newClasses.has(id)).sort())
    changes.push({ entity: 'CLASE', action: 'ELIMINADO', id, label: oldClasses.get(id).nombre })
  for (const id of [...newClasses.keys()].filter(id => !oldClasses.has(id)).sort())
    changes.push({ entity: 'CLASE', action: 'AGREGADO', id, label: newClasses.get(id).nombre })
  for (const id of [...newClasses.keys()].filter(id => oldClasses.has(id)).sort()) {
    const oldItem = oldClasses.get(id), newItem = newClasses.get(id)
    if (oldItem.nombre !== newItem.nombre || oldItem.tipoElemento !== newItem.tipoElemento ||
        oldItem.posicionX !== newItem.posicionX || oldItem.posicionY !== newItem.posicionY) {
      changes.push({ entity: 'CLASE', action: 'MODIFICADO', id, label: newItem.nombre })
    }
    const oldProps = parseProperties(oldItem), newProps = parseProperties(newItem)
    keyed({ before: oldProps.attributes, after: newProps.attributes }, 'ATRIBUTO', id, changes)
    keyed({ before: oldProps.methods, after: newProps.methods }, 'MÉTODO', id, changes)
    const oldRest = { ...oldProps }; delete oldRest.attributes; delete oldRest.methods
    const newRest = { ...newProps }; delete newRest.attributes; delete newRest.methods
    if (stable(oldRest) !== stable(newRest)) changes.push({ entity: 'PROPIEDADES', action: 'MODIFICADO', id, label: newItem.nombre })
  }
  const oldRelations = new Map((before?.relations || []).map(item => [item.id, item]))
  const newRelations = new Map((after?.relations || []).map(item => [item.id, item]))
  for (const id of [...oldRelations.keys()].filter(id => !newRelations.has(id)).sort())
    changes.push({ entity: 'RELACIÓN', action: 'ELIMINADO', id, label: relationLabel(oldRelations.get(id)) })
  for (const id of [...newRelations.keys()].filter(id => !oldRelations.has(id)).sort())
    changes.push({ entity: 'RELACIÓN', action: 'AGREGADO', id, label: relationLabel(newRelations.get(id)) })
  for (const id of [...newRelations.keys()].filter(id => oldRelations.has(id)).sort())
    if (stable(oldRelations.get(id)) !== stable(newRelations.get(id)))
      changes.push({ entity: 'RELACIÓN', action: 'MODIFICADO', id, label: relationLabel(newRelations.get(id)) })
  return changes
}

function relationLabel(relation) {
  return `${relation.nombreRelacion || relation.tipoRelacion} (${relation.multiplicidadOrigen || '*'} .. ${relation.multiplicidadDestino || '*'})`
}
