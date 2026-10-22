export type ProposalConfig = { apiBase?: string; projectId: string; diagramId: string; token: string }
export type ProposalChange = { kind: 'CREATE' | 'UPDATE' | 'DELETE'; entity: 'CLASS' | 'RELATION'; id: string; before: Record<string, unknown> | null; after: Record<string, unknown> | null }
export type Proposal = { id: string; projectId: string; diagramId: string; conversationId: string | null; scope: string; requesterName: string; description: string; status: string; baseRevision: number; currentRevision: number; createdAt: string; deciderName: string | null; appliedRevision: number | null; resultCode: string | null; preview: { changes: ProposalChange[]; warnings: { severity: string; mensaje: string }[]; destructive: boolean; clarification: string | null } }
export function proposalRequest<T>(config: ProposalConfig, action?: string, body?: unknown, fetcher?: typeof fetch): Promise<T>
export function canApplyProposal(proposal: Proposal | null, role: string, confirmed: boolean): boolean
export function applyProposal(config: ProposalConfig, proposal: Proposal, role: string, confirmed: boolean, requestId: string, fetcher?: typeof fetch): Promise<Proposal>
export function describeChange(change: ProposalChange): string
