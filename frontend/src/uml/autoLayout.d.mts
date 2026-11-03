export type UmlLayoutNodeInput = {
  id: string
  x: number
  y: number
  width: number
  height: number
}

export type UmlLayoutEdgeInput = {
  source: string
  target: string
  type?: string
}

export type UmlLayoutPosition = { id: string; x: number; y: number }

export function layoutUmlGraph(nodes: UmlLayoutNodeInput[], edges: UmlLayoutEdgeInput[]): UmlLayoutPosition[]
