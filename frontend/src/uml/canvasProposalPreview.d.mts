import type { ElementDetail, RelationDetail } from './types'
import type { Proposal } from './umlProposalClient.mjs'
import type { Edge } from '@xyflow/react'
import type { UmlNodeType } from './UmlClassNode'
export type PreviewMark = { kind: string; members: Record<string, string>; fields: string[] }
export function projectCanvasProposal(elements: ElementDetail[], relations: RelationDetail[], proposal: Proposal | null, authorizedPrivate?: boolean): { elements: ElementDetail[]; relations: RelationDetail[]; marks: Record<string, PreviewMark>; relationMarks: Record<string, string> }
export function greenPreviewEdge(edge: Edge, kind?: string): Edge
export function overlayCanvasGraph(nodes: UmlNodeType[], edges: Edge[], changedNodes: UmlNodeType[], changedEdges: Edge[]): { nodes: UmlNodeType[]; edges: Edge[] }
export function affectedPreviewNodeIds(proposal: Proposal | null, nodes: UmlNodeType[], edges: Edge[]): string[]
