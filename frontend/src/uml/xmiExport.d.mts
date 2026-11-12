export function exportXmiFile(options: {
  apiBase?: string
  projectId: string
  diagramId: string
  token: string
  diagramName: string
  ext: 'xmi' | 'xml'
}): Promise<void>
