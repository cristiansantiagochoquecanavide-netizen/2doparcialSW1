export function canEditSharedContext(role: string): boolean
export function analysisRequest<T = unknown>(context: { apiBase?: string; projectId: string; diagramId: string; token: string }, action: 'status' | 'policy' | 'review' | 'disposition', input?: unknown): Promise<T>
