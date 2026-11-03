export type DiagramWriteResponse = { revision?: number | null };
export type DiagramRequestCoordinator = {
  observeRevision: (diagramId: string, revision: number | null | undefined) => number | null;
  confirmLocalWrite: (diagramId: string, revision: number | null | undefined) => number | null;
  expectedAfterEarlierLocalWrite: (diagramId: string, requestedRevision: number | null | undefined) => number | null | undefined;
  enqueueWrite: <T>(diagramId: string, operation: () => Promise<T>) => Promise<T>;
  revision: (diagramId: string) => number | null;
};
export function createDiagramRequestCoordinator(): DiagramRequestCoordinator;
export function isStaleDiagramRevision(currentRevision: number | null | undefined, incomingRevision: number | null | undefined): boolean;
export function mergePendingPositions<T extends { id: string; posicionX: number; posicionY: number }>(elements: T[], queue: Array<{ type: string; payload?: { positions?: Array<{ id: string; posicionX: number; posicionY: number }> } }>): T[];
export function markPendingQueueForReview<T extends { id: string }>(queue: T[]): Array<T & { requiresReview: true }>;
export function normalizeRelationEndpoint(handle: string | null | undefined, role: 'source' | 'target', preferredSide: 'top' | 'right' | 'bottom' | 'left', fallbackSlot: number): string;
