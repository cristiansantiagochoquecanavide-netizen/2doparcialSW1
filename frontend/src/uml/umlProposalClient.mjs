export async function proposalRequest(config, action = '', body, fetcher = fetch) {
  const url = `${config.apiBase || ''}/api/projects/${encodeURIComponent(config.projectId)}/diagrams/${encodeURIComponent(config.diagramId)}/ai/proposals${action ? (action.startsWith('?') ? '' : '/') + action : ''}`
  const response = await fetcher(url, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${config.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const data = await response.json().catch(() => ({}))
  if (!response.ok) { const error = new Error(data.message || (data.errorCode ? `No se pudo preparar la propuesta (${data.errorCode}).` : `Error ${response.status}`)); error.status = response.status; throw error }
  return data
}

export function canApplyProposal(proposal, role, confirmed) {
  return ['PROPIETARIO', 'EDITOR'].includes(role) && proposal?.status === 'PENDIENTE' && (!proposal.preview.destructive || confirmed)
}

export function applyProposal(config, proposal, role, confirmed, requestId, fetcher = fetch) {
  if (!canApplyProposal(proposal, role, confirmed)) throw new Error('Revisa y confirma la propuesta antes de aplicarla.')
  return proposalRequest(config, `${proposal.id}/apply`, { clientRequestId: requestId, expectedRevision: proposal.baseRevision, confirmDestructive: confirmed }, fetcher)
}

export function describeChange(change) {
  const node = change.after || change.before
  const action = { CREATE: 'Añadido', UPDATE: 'Modificado', DELETE: 'Eliminado' }[change.kind] || change.kind
  return `${action} · ${change.entity === 'CLASS' ? 'Clase' : 'Relación'} · ${node?.name || node?.type || change.id}`
}
