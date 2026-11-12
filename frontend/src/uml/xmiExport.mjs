const xmlMimeType = 'application/xml'

function getXmlRoot(xml, parseXml) {
  const parsed = parseXml(xml)
  if (parsed?.parserError) throw new Error('El servidor devolvió XML mal formado; no se descargó el archivo.')
  const rootName = parsed?.rootName?.toLowerCase()
  if (rootName !== 'xmi') throw new Error('La respuesta no contiene un documento XMI válido; verifica la URL configurada del backend.')
}

export async function exportXmiFile({
  apiBase = '', projectId, diagramId, token, diagramName, ext,
  fetchImpl = fetch,
  parseXml = (xml) => {
    const parsed = new DOMParser().parseFromString(xml, 'application/xml')
    return { rootName: parsed.documentElement?.localName, parserError: Boolean(parsed.querySelector('parsererror')) }
  },
  documentRef = globalThis.document,
  urlApi = URL
}) {
  const path = `/api/projects/${projectId}/diagrams/${diagramId}/xmi/export`
  const response = await fetchImpl(`${apiBase.replace(/\/+$/, '')}${path}`, {
    headers: { Authorization: `Bearer ${token}` }
  })

  if (!response.ok) {
    const body = await response.text()
    let message = ''
    try { message = JSON.parse(body).message || '' } catch { message = body.trim() }
    throw new Error(message || `Error al exportar archivo XMI (${response.status}).`)
  }

  const contentType = response.headers?.get?.('content-type')?.toLowerCase() || ''
  if (contentType.includes('text/html')) {
    throw new Error('El backend devolvió la página HTML del frontend. Verifica VITE_API_BASE_URL en el despliegue.')
  }

  const xml = await response.text()
  if (!xml.trim()) throw new Error('El backend devolvió un archivo XMI vacío.')
  if (/^\s*(?:<!doctype\s+html|<html\b)/i.test(xml)) {
    throw new Error('El backend devolvió HTML en lugar de XMI. Verifica VITE_API_BASE_URL en el despliegue.')
  }
  if (contentType && !contentType.includes('xml') && !contentType.includes('text/plain')) {
    throw new Error(`Tipo de respuesta inesperado para XMI: ${contentType}.`)
  }

  getXmlRoot(xml, parseXml)
  const mimeType = ext === 'xmi' ? 'application/xmi+xml' : xmlMimeType
  const blob = new Blob([xml], { type: `${mimeType};charset=utf-8` })
  const objectUrl = urlApi.createObjectURL(blob)
  const anchor = documentRef.createElement('a')
  anchor.href = objectUrl
  anchor.download = `${diagramName.toLowerCase().replace(/\s+/g, '_')}.${ext}`
  documentRef.body.appendChild(anchor)
  anchor.click()
  documentRef.body.removeChild(anchor)
  urlApi.revokeObjectURL(objectUrl)
}
