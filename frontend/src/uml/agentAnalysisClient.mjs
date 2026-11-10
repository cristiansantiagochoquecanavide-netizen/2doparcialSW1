export function canEditSharedContext(role) { return ['PROPIETARIO', 'OWNER', 'EDITOR'].includes(String(role).toUpperCase()) }
export async function analysisRequest({ apiBase = '', projectId, diagramId, token }, action, input) {
  const cleanBase = String(apiBase || '').replace(/\/+$/, '')
  const base = `${cleanBase}/api/projects/${encodeURIComponent(projectId)}/ai/analysis`
  let path = `${base}/diagrams/${encodeURIComponent(diagramId)}`
  let method = 'GET'
  let body
  if (action === 'policy') { path = `${base}/policy`; method = 'PATCH'; body = input }
  else if (action === 'review') { path += '/review'; method = 'POST' }
  else if (action === 'disposition') { path += `/recommendations/${encodeURIComponent(input.id)}`; method = 'PATCH'; body = { status: input.status } }
  else if (action !== 'status') throw new Error('Operación de análisis no válida.')
  const response = await fetch(path, { method, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) })
  const text = await response.text()
  let result = null
  try { result = text ? JSON.parse(text) : null } catch { /* A safe generic message handles invalid error bodies. */ }
  if (!response.ok) {
    if (response.status === 401 && typeof window !== 'undefined') window.dispatchEvent(new Event('case:unauthorized'))
    throw new Error(result?.message || `No se pudo completar el análisis (${response.status}).`)
  }
  return result
}
