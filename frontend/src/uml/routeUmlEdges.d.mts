export type RouteNode = { id: string; x: number; y: number; width: number; height: number }
export type RouteEdge = { id: string; source: string; target: string; label?: string }
export type UmlRoute = { id: string; source?: string; target?: string; points: Array<{ x: number; y: number }> | null; distance?: number; bends?: number; label?: string; labelPoint?: { x: number; y: number } | null }
export function routeUmlEdges(nodes: RouteNode[], edges: RouteEdge[]): UmlRoute[]
export function measureRoutedLayout(nodes: RouteNode[], routes: UmlRoute[]): { nodeOverlaps: number; edgeThroughNodes: number; labelOverlaps: number; totalLength: number; bends: number }
export function scoreUmlCandidates(nodes: RouteNode[], edges: RouteEdge[], candidates: Array<Array<{ id: string; x: number; y: number }>>): Array<{ index: number; positions: Array<{ id: string; x: number; y: number }>; routes: UmlRoute[]; metrics: { nodeOverlaps: number; edgeThroughNodes: number; labelOverlaps: number; totalLength: number; bends: number; crossings: number }; score: number }>
