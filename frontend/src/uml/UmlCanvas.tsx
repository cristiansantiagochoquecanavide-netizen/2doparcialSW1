import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  Connection,
  Edge,
  NodeChange,
  BackgroundVariant,
  MarkerType,
  SelectionMode,
  Position,
  reconnectEdge,
  BaseEdge,
  StepEdge,
  EdgeLabelRenderer,
  ViewportPortal,
  type EdgeProps,
  type ReactFlowInstance
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import {
  DiagramFullView,
  DiagramSummary,
  ElementDetail,
  RelationDetail,
  Role,
  TipoElementoUML,
  TipoRelacionUML,
  UmlProperties,
  ValidationReport,
  RelationalSchema
} from './types'
import { UmlClassNode, UmlNodeType } from './UmlClassNode'
import { ClassEditorModal } from './ClassEditorModal'
import { RelationModal } from './RelationModal'
import { ValidationModal } from './ValidationModal'
import { RelationalModal } from './RelationalModal'
import { CodeGeneratorModal } from './CodeGeneratorModal'
import { AiAssistantModal } from './AiAssistantModal'
import { VersionHistoryModal, type VersionSummary } from './VersionHistoryModal'
import { ActivityLogModal } from './ActivityLogModal'
import { compareVersions, type VersionChange } from './versionDiff.mjs'
import { layoutUmlGraph } from './autoLayout.mjs'
import { measureRoutedLayout, routeUmlEdges, scoreUmlCandidates } from './routeUmlEdges.mjs'
import { createDiagramRequestCoordinator, isStaleDiagramRevision, markPendingQueueForReview, mergePendingPositions, normalizeRelationEndpoint } from './diagramRequestCoordinator.mjs'
import { XmiModal } from './XmiModal'
import { ApiTesterModal } from './ApiTesterModal'
import { WorkspaceChrome } from './WorkspaceChrome'
import { ConfirmModal } from './ConfirmModal'
import { SparklesIcon, CodeIcon, ExchangeIcon } from './icons'
import { normalizePendingOperations, replayPendingQueue, snapshotContentMatches, type OfflineAction } from './offlineQueue.mjs'
import { useCanvasProposal } from './useCanvasProposal'
import { CanvasProposalBar } from './CanvasProposalBar'
import { projectCanvasProposal, greenPreviewEdge, overlayCanvasGraph, affectedPreviewNodeIds } from './canvasProposalPreview.mjs'
import './canvas-proposal.css'

const base = import.meta.env.VITE_API_BASE_URL !== undefined ? import.meta.env.VITE_API_BASE_URL : ''
const NODE_TYPES = { umlClass: UmlClassNode }
const diagramRequestCoordinator = createDiagramRequestCoordinator()

type RelationHandleOverride = {
  sourceHandle: string | null
  targetHandle: string | null
}

type RelationWithHandles = RelationDetail & Partial<RelationHandleOverride>

type PendingClassDeletion = {
  elementIds: string[]
  operation: 'single' | 'selected'
  title: string
  message: string
}

function relationHandleStorageKey(diagramId: string) {
  return `case.uml.relation-handles.${diagramId}`
}

function loadRelationHandleOverrides(diagramId: string) {
  try {
    const raw = localStorage.getItem(relationHandleStorageKey(diagramId))
    const parsed = raw ? JSON.parse(raw) : {}
    return new Map<string, RelationHandleOverride>(Object.entries(parsed))
  } catch {
    return new Map<string, RelationHandleOverride>()
  }
}

function saveRelationHandleOverrides(diagramId: string, overrides: Map<string, RelationHandleOverride>) {
  try {
    localStorage.setItem(relationHandleStorageKey(diagramId), JSON.stringify(Object.fromEntries(overrides)))
  } catch {
    // La persistencia visual local no debe bloquear el diagrama.
  }
}

function normalizeRelationHandle(
  handle: string | null | undefined,
  type: 'source' | 'target',
  fallbackPosition: Position,
  fallbackSlot: number
) {
  return normalizeRelationEndpoint(handle, type, fallbackPosition.toLowerCase() as 'top' | 'right' | 'bottom' | 'left', fallbackSlot)
}

function positionFromRelationHandle(handle: string | null | undefined, fallback: Position) {
  const side = handle?.split('-')[0]
  if (side === Position.Top || side === Position.Right || side === Position.Bottom || side === Position.Left) {
    return side
  }
  return fallback
}

function ensureReflexiveHandlePair(connection: Connection): Connection {
  const sourceHandle = connection.sourceHandle ?? 'right-source-0'
  const targetHandle = connection.targetHandle ?? 'left-target-0'
  const sourceSide = sourceHandle.split('-')[0]
  const targetSide = targetHandle.split('-')[0]
  if (sourceSide !== targetSide) return { ...connection, sourceHandle, targetHandle }

  const alternateTargetSide = sourceSide === 'right' ? 'left' : sourceSide === 'left' ? 'right' : sourceSide === 'top' ? 'bottom' : 'top'
  return {
    ...connection,
    sourceHandle,
    targetHandle: `${alternateTargetSide}-target-0`
  }
}

function buildReflexiveOrthogonalPath(
  sourceX: number,
  sourceY: number,
  targetX: number,
  targetY: number,
  sourcePosition: Position,
  targetPosition: Position
) {
  const margin = 48
  const left = Math.min(sourceX, targetX) - margin
  const right = Math.max(sourceX, targetX) + margin
  const top = Math.min(sourceY, targetY) - margin
  const bottom = Math.max(sourceY, targetY) + margin
  const source = [sourceX, sourceY] as const
  const target = [targetX, targetY] as const
  let points: Array<readonly [number, number]>

  if (sourcePosition === Position.Right && targetPosition === Position.Left) {
    points = [source, [right, sourceY], [right, top], [left, top], [left, targetY], target]
  } else if (sourcePosition === Position.Left && targetPosition === Position.Right) {
    points = [source, [left, sourceY], [left, bottom], [right, bottom], [right, targetY], target]
  } else if (sourcePosition === Position.Top && targetPosition === Position.Bottom) {
    points = [source, [sourceX, top], [right, top], [right, bottom], [targetX, bottom], target]
  } else if (sourcePosition === Position.Bottom && targetPosition === Position.Top) {
    points = [source, [sourceX, bottom], [left, bottom], [left, top], [targetX, top], target]
  } else if (sourcePosition === Position.Top) {
    points = [source, [sourceX, top], [right, top], [right, targetY], target]
  } else if (sourcePosition === Position.Bottom) {
    points = [source, [sourceX, bottom], [right, bottom], [right, targetY], target]
  } else if (sourcePosition === Position.Left) {
    points = [source, [left, sourceY], [left, targetY], target]
  } else {
    points = [source, [right, sourceY], [right, targetY], target]
  }

  const path = points.map(([x, y], index) => `${index === 0 ? 'M' : 'L'} ${x} ${y}`).join(' ')
  let labelPoint = points[Math.floor(points.length / 2)]
  let longestSegment = -1
  for (let index = 1; index < points.length; index += 1) {
    const [x1, y1] = points[index - 1]
    const [x2, y2] = points[index]
    const length = Math.abs(x2 - x1) + Math.abs(y2 - y1)
    if (length > longestSegment) {
      longestSegment = length
      labelPoint = [(x1 + x2) / 2, (y1 + y2) / 2]
    }
  }

  return { path, labelX: labelPoint[0], labelY: labelPoint[1] }
}

type UmlEdgeData = { tipoRelacion?: TipoRelacionUML; routePoints?: Array<{ x: number; y: number }> | null; routeLabelPoint?: { x: number; y: number } | null }

function UmlDiamondMarker({ x, y, filled, color }: { x: number; y: number; filled: boolean; color: string }) {
  return (
    <EdgeLabelRenderer>
      <span
        aria-hidden="true"
        style={{
          position: 'absolute',
          left: x,
          top: y,
          width: 12,
          height: 12,
          transform: 'translate(-50%, -50%) rotate(45deg)',
          background: filled ? color : '#ffffff',
          border: `2px solid ${color}`,
          boxSizing: 'border-box',
          pointerEvents: 'none',
          zIndex: 1
        }}
      />
    </EdgeLabelRenderer>
  )
}

function UmlOrthogonalEdge(props: EdgeProps) {
  const relationType = (props.data as UmlEdgeData | undefined)?.tipoRelacion
  const isComposition = relationType === 'COMPOSICION'
  const color = typeof props.style?.stroke === 'string' ? props.style.stroke : '#475569'

  return (
    <>
      <StepEdge {...props} markerStart={undefined} markerEnd={undefined} />
      <UmlDiamondMarker x={props.sourceX} y={props.sourceY} filled={isComposition} color={color} />
    </>
  )
}

function UmlRoutedEdge(props: EdgeProps) {
  const data = props.data as UmlEdgeData | undefined
  const route = data?.routePoints
  if (!route || route.length < 2) return data?.tipoRelacion === 'AGREGACION' || data?.tipoRelacion === 'COMPOSICION'
    ? <UmlOrthogonalEdge {...props} />
    : <StepEdge {...props} />
  let points = route.map(point => ({ ...point }))
  points[0] = { x: props.sourceX, y: props.sourceY }
  points[points.length - 1] = { x: props.targetX, y: props.targetY }
  if (points.length > 2) {
    const first = points[1]
    const sourceStub = props.sourcePosition === Position.Left || props.sourcePosition === Position.Right
      ? { x: first.x, y: props.sourceY }
      : { x: props.sourceX, y: first.y }
    const last = points[points.length - 2]
    const targetStub = props.targetPosition === Position.Left || props.targetPosition === Position.Right
      ? { x: last.x, y: props.targetY }
      : { x: props.targetX, y: last.y }
    points = [points[0], sourceStub, ...points.slice(1, -1), targetStub, points.at(-1)!]
  }
  const path = points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' ')
  const labelPoint = data?.routeLabelPoint ?? points[Math.floor((points.length - 1) / 2)]
  const color = typeof props.style?.stroke === 'string' ? props.style.stroke : '#475569'
  return <>
    <BaseEdge path={path} markerStart={props.markerStart} markerEnd={props.markerEnd} style={props.style} className={props.animated ? 'animated' : undefined} interactionWidth={props.interactionWidth} />
    {props.label && <EdgeLabelRenderer><div className="react-flow__edge-text" style={{ position: 'absolute', transform: `translate(-50%, -50%) translate(${labelPoint.x}px,${labelPoint.y}px)`, pointerEvents: 'all' }}>{props.label}</div></EdgeLabelRenderer>}
    {(data?.tipoRelacion === 'AGREGACION' || data?.tipoRelacion === 'COMPOSICION') && <UmlDiamondMarker x={props.sourceX} y={props.sourceY} filled={data.tipoRelacion === 'COMPOSICION'} color={color} />}
  </>
}

function ReflexiveOrthogonalEdge({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  label,
  markerStart,
  markerEnd,
  style,
  animated,
  interactionWidth,
  data
}: EdgeProps) {
  const { path, labelX, labelY } = buildReflexiveOrthogonalPath(
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition
  )

  return (
    <>
      <BaseEdge
        path={path}
        markerStart={markerStart}
        markerEnd={markerEnd}
        style={style}
        className={animated ? 'animated' : undefined}
        interactionWidth={interactionWidth}
      />
      {label && (
        <EdgeLabelRenderer>
          <div
            className="react-flow__edge-text"
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              pointerEvents: 'all'
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
      {(data as UmlEdgeData | undefined)?.tipoRelacion === 'AGREGACION' && (
        <UmlDiamondMarker x={sourceX} y={sourceY} filled={false} color={typeof style?.stroke === 'string' ? style.stroke : '#0284c7'} />
      )}
      {(data as UmlEdgeData | undefined)?.tipoRelacion === 'COMPOSICION' && (
        <UmlDiamondMarker x={sourceX} y={sourceY} filled color={typeof style?.stroke === 'string' ? style.stroke : '#1e293b'} />
      )}
    </>
  )
}

const EDGE_TYPES = { umlReflexive: ReflexiveOrthogonalEdge, umlOrthogonal: UmlOrthogonalEdge, umlRouted: UmlRoutedEdge }

interface ApiResponse<T> {
  data: T
  revision: number | null
}

function diagramIdFromPath(path: string) {
  try {
    const pathname = new URL(path, window.location.origin).pathname
    const match = pathname.match(/\/diagrams\/([^/]+)/)
    return match && match[1] !== 'default' ? match[1] : null
  } catch {
    return null
  }
}

function expectedRevisionFromRequest(options: RequestInit) {
  const headers = new Headers(options.headers)
  const header = headers.get('X-Diagram-Revision') ?? headers.get('If-Match')?.replaceAll('"', '')
  if (header && Number.isFinite(Number(header))) return Number(header)
  if (typeof options.body === 'string') {
    try {
      const body = JSON.parse(options.body) as { expectedRevision?: unknown }
      if (body.expectedRevision != null && Number.isSafeInteger(Number(body.expectedRevision))) return Number(body.expectedRevision)
    } catch { /* non-JSON mutation body */ }
  }
  return null
}

function requestWithRevision(options: RequestInit, revision: number): RequestInit {
  const headers = new Headers(options.headers)
  headers.set('X-Diagram-Revision', String(revision))
  let body = options.body
  if (typeof body === 'string') {
    try {
      const parsed = JSON.parse(body)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && 'expectedRevision' in parsed) {
        body = JSON.stringify({ ...parsed, expectedRevision: revision })
      }
    } catch { /* header remains authoritative */ }
  }
  return { ...options, headers, body }
}

async function requestFull<T>(path: string, token: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  const method = (options.method ?? 'GET').toUpperCase()
  const diagramId = diagramIdFromPath(path)
  const perform = async (): Promise<ApiResponse<T>> => {
    let requestOptions = options
    if (method !== 'GET' && diagramId) {
      const suppliedRevision = expectedRevisionFromRequest(options)
      const rebasedRevision = diagramRequestCoordinator.expectedAfterEarlierLocalWrite(diagramId, suppliedRevision)
      if (rebasedRevision !== suppliedRevision && rebasedRevision != null) {
        requestOptions = requestWithRevision(options, rebasedRevision)
      }
    }
  const url = base ? `${base}${path}` : path
  const response = await fetch(url, {
    ...requestOptions,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...Object.fromEntries(new Headers(requestOptions.headers).entries())
    }
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiRequestError(response.status, body.message ?? 'Error al comunicar con el servidor.')
  }
  const revHeader = response.headers.get('X-Diagram-Revision')
  const revision = revHeader ? parseInt(revHeader, 10) : null
  const data = response.status === 204 ? (undefined as T) : ((await response.json()) as T)
  if (diagramId && revision != null && Number.isSafeInteger(revision)) {
    if (method === 'GET') diagramRequestCoordinator.observeRevision(diagramId, revision)
    else diagramRequestCoordinator.confirmLocalWrite(diagramId, revision)
  }
  if (method === 'GET' && diagramId && data && typeof data === 'object') {
    const fullRevision = (data as { diagram?: { revision?: number } }).diagram?.revision
    if (typeof fullRevision === 'number' && Number.isSafeInteger(fullRevision)) diagramRequestCoordinator.observeRevision(diagramId, fullRevision)
  }
  return { data, revision }
  }

  return method === 'GET' || !diagramId
    ? perform()
    : diagramRequestCoordinator.enqueueWrite(diagramId, perform)
}

async function request<T>(path: string, token: string, options: RequestInit = {}): Promise<T> {
  const res = await requestFull<T>(path, token, options)
  return res.data
}

class ApiRequestError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

function pendingOperationStatus(error: unknown): string {
  if (error instanceof ApiRequestError && error.status >= 400 && error.status < 500) {
    return `El servidor rechazó la operación (HTTP ${error.status}): ${error.message}. Se conserva localmente como pendiente.`
  }
  if (error instanceof ApiRequestError) {
    return `El servidor respondió HTTP ${error.status} sin confirmar el guardado. Se conserva localmente como pendiente.`
  }
  return 'No se recibió confirmación del servidor. El cambio se conserva localmente como pendiente.'
}

interface PendingSyncAction extends OfflineAction {
  type: 'SAVE_ELEMENT' | 'UPDATE_ELEMENT' | 'DELETE_ELEMENT' | 'SAVE_POSITIONS' | 'SAVE_RELATION' | 'UPDATE_RELATION' | 'DELETE_RELATION'
  requiresReview?: boolean
}

function getOfflineQueue(diagramId: string): PendingSyncAction[] {
  try {
    const raw = localStorage.getItem(`case.pending_sync.${diagramId}`)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function setOfflineQueue(diagramId: string, queue: PendingSyncAction[]) {
  try {
    localStorage.setItem(`case.pending_sync.${diagramId}`, JSON.stringify(queue))
  } catch {
    // Quota exceeded
  }
}

function appendOfflineQueue(
  diagramId: string,
  action: Omit<PendingSyncAction, 'id' | 'timestamp'>,
  expectedRevision?: number | null,
  requiresReview = false
): number {
  const queue = getOfflineQueue(diagramId)
  queue.push({
    ...action,
    id: crypto.randomUUID(),
    timestamp: Date.now(),
    expectedRevision: expectedRevision !== undefined ? expectedRevision : ((action as any).expectedRevision ?? null),
    ...(requiresReview ? { requiresReview: true } : {})
  })
  setOfflineQueue(diagramId, queue)
  return queue.length
}

function saveLocalDiagramSnapshot(
  diagramId: string,
  diagram: DiagramSummary | null,
  elements: ElementDetail[],
  relations: RelationDetail[]
) {
  try {
    localStorage.setItem(
      `case.offline.${diagramId}`,
      JSON.stringify({ diagram, elements, relations, savedAt: Date.now() })
    )
  } catch {
    // Quota exceeded
  }
}

function saveServerDiagramSnapshot(diagramId: string, snapshot: DiagramFullView) {
  try {
    localStorage.setItem(`case.server_snapshot.${diagramId}`, JSON.stringify(snapshot))
  } catch {
    // The server baseline is optional if browser storage is unavailable.
  }
}

function estimateUmlNodeHeight(propertiesJson: string) {
  try {
    const properties = JSON.parse(propertiesJson || '{}') as { attributes?: unknown[]; methods?: unknown[]; description?: string; stereotype?: string }
    const attributes = Array.isArray(properties.attributes) ? properties.attributes.length : 0
    const methods = Array.isArray(properties.methods) ? properties.methods.length : 0
    const descriptionHeight = typeof properties.description === 'string' && properties.description.trim() ? 22 : 0
    const stereotypeHeight = typeof properties.stereotype === 'string' && properties.stereotype.trim() ? 14 : 0
    const attributeRows = Math.max(1, attributes), methodRows = Math.max(1, methods)
    return 36 + descriptionHeight + stereotypeHeight + 24 + Math.max(18, attributeRows * 16.5) + 24 + Math.max(18, methodRows * 16.5)
  } catch {
    return 130
  }
}

function loadServerDiagramSnapshot(diagramId: string): DiagramFullView | null {
  try {
    const raw = localStorage.getItem(`case.server_snapshot.${diagramId}`)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function loadLocalDiagramSnapshot(
  diagramId: string
): { diagram: DiagramSummary | null; elements: ElementDetail[]; relations: RelationDetail[] } | null {
  try {
    const raw = localStorage.getItem(`case.offline.${diagramId}`)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

type Props = {
  project: { id: string; name: string; role: Role }
  auth: { token: string; name: string; email: string }
  onError: (e: unknown) => void
  members?: { id: string; userId: string; name: string; email: string; role: string }[]
  onManageMembers?: () => void
  onBack?: () => void
}

type CanvasComment = { id: string; posicionX: number | null; posicionY: number | null; canvasWidth: number; canvasHeight: number; content: string; status: 'OPEN' | 'RESOLVED'; authorName: string }

export function UmlCanvas({ project, auth, onError, members = [], onManageMembers, onBack }: Props) {
  const [historicalView, setHistoricalView] = useState<{ snapshot: DiagramFullView; version: VersionSummary; changes?: VersionChange[]; before?: DiagramFullView; beforeVersion?: VersionSummary } | null>(null)
  const [comparisonVersionIds, setComparisonVersionIds] = useState<string[]>([])
  const [historicalNodeMeasurements, setHistoricalNodeMeasurements] = useState<Record<string, { width?: number; height?: number }>>({})
  const isReadOnly = project.role === 'LECTOR' || historicalView !== null

  const [diagram, setDiagram] = useState<DiagramSummary | null>(null)
  useEffect(() => { setComparisonVersionIds([]) }, [project.id, diagram?.id, auth.token])
  const [elementsRaw, setElementsRaw] = useState<ElementDetail[]>([])
  const [relationsRaw, setRelationsRaw] = useState<RelationDetail[]>([])
  const [loadedBaseDiagramId, setLoadedBaseDiagramId] = useState('')

  const [nodes, setNodes, onNodesChange] = useNodesState<UmlNodeType>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const flowInstance = useRef<ReactFlowInstance<UmlNodeType, Edge> | null>(null)

  const [savingStatus, setSavingStatus] = useState<string>('Guardado')
  const [arrangingDiagram, setArrangingDiagram] = useState(false)
  const [isOnline, setIsOnline] = useState<boolean>(navigator.onLine)
  const [isSyncingQueue, setIsSyncingQueue] = useState<boolean>(false)
  const [pendingQueueCount, setPendingQueueCount] = useState<number>(0)
  const canvasProposal = useCanvasProposal({ apiBase: base, projectId: project.id, diagramId: diagram?.id || '', token: auth.token }, project.role, async () => {
    if (!diagram || getOfflineQueue(diagram.id).length > 0) return
    const full = await request<DiagramFullView>(`/api/projects/${project.id}/diagrams/${diagram.id}`, auth.token)
    handleFullDiagramUpdated(full)
  })

  // Modales
  const [editingElement, setEditingElement] = useState<ElementDetail | null>(null)
  const [pendingClassDeletion, setPendingClassDeletion] = useState<PendingClassDeletion | null>(null)
  const [pendingConnection, setPendingConnection] = useState<Connection | null>(null)
  const [pendingRelationType, setPendingRelationType] = useState<TipoRelacionUML>('ASOCIACION')
  const [editingRelation, setEditingRelation] = useState<RelationDetail | null>(null)
  const [validationReport, setValidationReport] = useState<ValidationReport | null>(null)
  const [relationalSchema, setRelationalSchema] = useState<RelationalSchema | null>(null)
  const [validating, setValidating] = useState(false)
  const [generatingRelational, setGeneratingRelational] = useState(false)

  const [showGenerator, setShowGenerator] = useState(false)
  const [generatorWithFlutter, setGeneratorWithFlutter] = useState(false)
  const [showAi, setShowAi] = useState(false)
  const [aiInitialMode, setAiInitialMode] = useState<'command' | 'image' | 'chat'>('command')
  const [imageImportFile, setImageImportFile] = useState<File | null>(null)
  const [showVersions, setShowVersions] = useState(false)
  const [showActivity, setShowActivity] = useState(false)
  const [commentMode, setCommentMode] = useState(false)
  const [draftComment, setDraftComment] = useState<{ x: number; y: number; content: string } | null>(null)
  const [canvasComments, setCanvasComments] = useState<CanvasComment[]>([])
  const draggingComment = useRef<{ id: string; offsetX: number; offsetY: number } | null>(null)
  const resizingComment = useRef<{ id: string; pointerX: number; pointerY: number; width: number; height: number } | null>(null)
  const [selectedCommentId, setSelectedCommentId] = useState<string | null>(null)
  const [editingCommentId, setEditingCommentId] = useState<string | null>(null)
  const [editingCommentText, setEditingCommentText] = useState('')
  const [showXmi, setShowXmi] = useState(false)
  const [showApiTester, setShowApiTester] = useState(false)

  const loadCanvasComments = useCallback(async () => {
    if (!diagram) return
    try {
      const comments = await request<CanvasComment[]>(`/api/projects/${project.id}/diagrams/${diagram.id}/comments`, auth.token)
      setCanvasComments(comments.filter(comment => comment.posicionX != null && comment.posicionY != null))
    } catch (error) {
      onError(error)
    }
  }, [auth.token, diagram, onError, project.id])

  useEffect(() => { void loadCanvasComments() }, [loadCanvasComments])

  async function moveCanvasComment(commentId: string, position: { x: number; y: number }) {
    if (!diagram) return
    try {
      await request(`/api/projects/${project.id}/diagrams/${diagram.id}/comments/${commentId}`, auth.token, {
        method: 'PATCH',
        body: JSON.stringify({ posicionX: position.x, posicionY: position.y })
      })
    } catch (error) {
      onError(error)
      void loadCanvasComments()
    }
  }

  async function resizeCanvasComment(commentId: string, size: { width: number; height: number }) {
    if (!diagram) return
    try {
      await request(`/api/projects/${project.id}/diagrams/${diagram.id}/comments/${commentId}`, auth.token, {
        method: 'PATCH',
        body: JSON.stringify({ canvasWidth: size.width, canvasHeight: size.height })
      })
    } catch (error) {
      onError(error)
      void loadCanvasComments()
    }
  }

  async function deleteCanvasComment(commentId: string) {
    if (!diagram || !window.confirm('¿Eliminar este comentario del lienzo?')) return
    try {
      await request(`/api/projects/${project.id}/diagrams/${diagram.id}/comments/${commentId}`, auth.token, { method: 'DELETE' })
      setCanvasComments(comments => comments.filter(comment => comment.id !== commentId))
      setSelectedCommentId(current => current === commentId ? null : current)
      setEditingCommentId(current => current === commentId ? null : current)
    } catch (error) {
      onError(error)
    }
  }

  async function updateCanvasComment(commentId: string) {
    if (!diagram || !editingCommentText.trim()) return
    try {
      await request(`/api/projects/${project.id}/diagrams/${diagram.id}/comments/${commentId}`, auth.token, {
        method: 'PATCH',
        body: JSON.stringify({ content: editingCommentText.trim() })
      })
      setCanvasComments(comments => comments.map(comment => comment.id === commentId ? { ...comment, content: editingCommentText.trim() } : comment))
      setEditingCommentId(null)
      setEditingCommentText('')
    } catch (error) {
      onError(error)
    }
  }

  async function saveDraftComment() {
    if (!diagram || !draftComment?.content.trim()) return
    try {
      await request(`/api/projects/${project.id}/diagrams/${diagram.id}/comments`, auth.token, {
        method: 'POST',
        body: JSON.stringify({ content: draftComment.content.trim(), elementoId: null, relacionId: null, parentId: null, posicionX: draftComment.x, posicionY: draftComment.y })
      })
      setDraftComment(null)
      await loadCanvasComments()
    } catch (error) {
      onError(error)
    }
  }

  // Estado y ref para la expansión manual del área de trabajo del lienzo UML
  const viewportRef = useRef<HTMLDivElement>(null)
  const [canvasCustomSize, setCanvasCustomSize] = useState<{ width: number | null; height: number | null }>({ width: null, height: null })
  const [isResizingCanvas, setIsResizingCanvas] = useState(false)

  const handleMouseDownCanvasResizer = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    if (!viewportRef.current) return
    const startX = e.clientX
    const startY = e.clientY
    const startWidth = viewportRef.current.clientWidth
    const startHeight = viewportRef.current.clientHeight

    setIsResizingCanvas(true)

    const handleMouseMove = (moveEvent: MouseEvent) => {
      const deltaX = moveEvent.clientX - startX
      const deltaY = moveEvent.clientY - startY
      const newWidth = Math.max(600, startWidth + deltaX)
      const newHeight = Math.max(400, startHeight + deltaY)
      setCanvasCustomSize({ width: newWidth, height: newHeight })
    }

    const handleMouseUp = () => {
      setIsResizingCanvas(false)
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }

    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)
  }, [])

  // Referencias mutables para evitar dependencias circulares en callbacks y efectos
  const elementsRef = useRef(elementsRaw)
  elementsRef.current = elementsRaw
  const relationsRef = useRef(relationsRaw)
  relationsRef.current = relationsRaw
  const relationHandleOverridesRef = useRef(new Map<string, RelationHandleOverride>())
  const diagramRef = useRef(diagram)
  diagramRef.current = diagram
  const nodesRef = useRef(nodes)
  nodesRef.current = nodes
  const activeQueueFlushesRef = useRef(new Set<string>())
  const acceptServerRevision = useCallback((diagramId: string, revision: number | null | undefined) => {
    if (revision == null || !Number.isSafeInteger(revision)) return true
    const currentRevision = diagramRequestCoordinator.revision(diagramId)
    if (isStaleDiagramRevision(currentRevision, revision)) return false
    diagramRequestCoordinator.observeRevision(diagramId, revision)
    setDiagram(previous => previous?.id === diagramId && (previous.revision ?? 0) < revision
      ? { ...previous, revision }
      : previous)
    return true
  }, [])
  const applyConfirmedDiagramRevision = useCallback((diagramId: string) => {
    const revision = diagramRequestCoordinator.revision(diagramId)
    if (revision != null) acceptServerRevision(diagramId, revision)
  }, [acceptServerRevision])

  // Callback para abrir edición de elemento (referencia estable)
  const handleEditElement = useCallback((elementId: string) => {
    const el = elementsRef.current.find((e) => e.id === elementId)
    if (el) setEditingElement(el)
  }, [])

  // Callback para eliminar elemento individual (con soporte Offline CU10)
  const handleDeleteElement = useCallback(
    async (elementId: string) => {
      const currentDiagram = diagramRef.current
      if (isReadOnly || !currentDiagram) return

      const nextElements = elementsRef.current.filter((e) => e.id !== elementId)
      const nextRelations = relationsRef.current.filter(
        (r) => r.elementoOrigen !== elementId && r.elementoDestino !== elementId
      )

      if (!navigator.onLine || getOfflineQueue(currentDiagram.id).length > 0) {
        setElementsRaw(nextElements)
        setRelationsRaw(nextRelations)
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
        const count = appendOfflineQueue(
          currentDiagram.id,
          {
            type: 'DELETE_ELEMENT',
            payload: { elementId }
          },
          currentDiagram.revision
        )
        setPendingQueueCount(count)
        setSavingStatus('Guardado localmente (Offline)')
        return
      }

      try {
        setSavingStatus('Guardando...')
        const headers: Record<string, string> =
          currentDiagram.revision != null ? { 'X-Diagram-Revision': String(currentDiagram.revision) } : {}
        await request(`/api/projects/${project.id}/diagrams/${currentDiagram.id}/elements/${elementId}`, auth.token, {
          method: 'DELETE',
          headers
        })
        setElementsRaw(nextElements)
        setRelationsRaw(nextRelations)
        applyConfirmedDiagramRevision(currentDiagram.id)
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
        setSavingStatus('Guardado')
      } catch (err) {
        if (err instanceof ApiRequestError && err.status === 409) {
          const full = await request<DiagramFullView>(
            `/api/projects/${project.id}/diagrams/${currentDiagram.id}`,
            auth.token
          ).catch(() => null)
          if (full && acceptServerRevision(currentDiagram.id, full.diagram.revision)) {
            setDiagram(full.diagram)
            setElementsRaw(full.elements)
            setRelationsRaw(full.relations)
            saveServerDiagramSnapshot(currentDiagram.id, full)
          }
          setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
          return
        }
        setElementsRaw(nextElements)
        setRelationsRaw(nextRelations)
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
        const count = appendOfflineQueue(
          currentDiagram.id,
          {
            type: 'DELETE_ELEMENT',
            payload: { elementId }
          },
          currentDiagram.revision
        )
        setPendingQueueCount(count)
        setSavingStatus('Guardado localmente (Offline)')
      }
    },
    [isReadOnly, project.id, auth.token, applyConfirmedDiagramRevision, acceptServerRevision]
  )

  const requestDeleteElement = useCallback((elementId: string) => {
    const el = elementsRef.current.find((element) => element.id === elementId)
    setPendingClassDeletion({
      elementIds: [elementId],
      operation: 'single',
      title: 'Eliminar clase',
      message: `¿Deseas eliminar «${el?.nombre ?? ''}»? También se eliminarán sus relaciones asociadas.`
    })
  }, [])

  // Eliminar elementos seleccionados en lote (Requerimiento 2, 10 y 13)
  const performDeleteSelected = useCallback(async (selectedIds: string[]) => {
    const currentDiagram = diagramRef.current
    if (isReadOnly || !currentDiagram || selectedIds.length === 0) return
    const selectedIdSet = new Set(selectedIds)
    const nextElements = elementsRef.current.filter((e) => !selectedIdSet.has(e.id))
    const nextRelations = relationsRef.current.filter(
      (r) => !selectedIdSet.has(r.elementoOrigen) && !selectedIdSet.has(r.elementoDestino)
    )

    if (!navigator.onLine || getOfflineQueue(currentDiagram.id).length > 0) {
      setElementsRaw(nextElements)
      setRelationsRaw(nextRelations)
      saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
      let count = 0
      for (const elementId of selectedIds) {
        count = appendOfflineQueue(
          currentDiagram.id,
          {
            type: 'DELETE_ELEMENT',
            payload: { elementId }
          },
          currentDiagram.revision
        )
      }
      setPendingQueueCount(count)
      setSavingStatus('Guardado localmente (Offline)')
      return
    }

    try {
      setSavingStatus('Eliminando seleccionados...')
      const headers: Record<string, string> =
        currentDiagram.revision != null ? { 'X-Diagram-Revision': String(currentDiagram.revision) } : {}
      await request(
        `/api/projects/${project.id}/diagrams/${currentDiagram.id}/elements`,
        auth.token,
        { method: 'DELETE', headers, body: JSON.stringify({ elementIds: selectedIds }) }
      )
      setElementsRaw(nextElements)
      setRelationsRaw(nextRelations)
      applyConfirmedDiagramRevision(currentDiagram.id)
      saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${currentDiagram.id}`,
          auth.token
        ).catch(() => null)
        if (full && acceptServerRevision(currentDiagram.id, full.diagram.revision)) {
          setDiagram(full.diagram)
          setElementsRaw(full.elements)
          setRelationsRaw(full.relations)
          saveServerDiagramSnapshot(currentDiagram.id, full)
        }
        setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
        return
      }
      setElementsRaw(nextElements)
      setRelationsRaw(nextRelations)
      saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
      let count = 0
      for (const elementId of selectedIds) {
        count = appendOfflineQueue(
          currentDiagram.id,
          {
            type: 'DELETE_ELEMENT',
            payload: { elementId }
          },
          currentDiagram.revision
        )
      }
      setPendingQueueCount(count)
      setSavingStatus('Guardado localmente (Offline)')
    }
  }, [isReadOnly, project.id, auth.token, applyConfirmedDiagramRevision, acceptServerRevision])

  const handleDeleteSelected = useCallback(() => {
    const selectedIds = nodesRef.current.filter((node) => node.selected).map((node) => node.id)
    if (selectedIds.length === 0) return

    const names = selectedIds
      .map((id) => elementsRef.current.find((element) => element.id === id)?.nombre ?? '')
      .slice(0, 3)
      .join(', ') + (selectedIds.length > 3 ? '...' : '')
    const message = selectedIds.length === 1
      ? `¿Deseas eliminar «${names}»? También se eliminarán sus relaciones asociadas.`
      : `¿Deseas eliminar las ${selectedIds.length} clases seleccionadas («${names}»)? También se eliminarán sus relaciones asociadas.`

    setPendingClassDeletion({ elementIds: selectedIds, operation: 'selected', title: 'Eliminar clases', message })
  }, [])

  // Transformar datos del backend a nodos de React Flow preservando selección
  const buildNodes = useCallback(
    (elements: ElementDetail[], previousNodes?: UmlNodeType[]): UmlNodeType[] => {
      const previousById = new Map((previousNodes ?? nodesRef.current ?? []).map((node) => [node.id, node]))
      return elements.map((el) => {
        const previous = previousById.get(el.id)
        const measured = historicalNodeMeasurements[el.id] ?? previous?.measured
        return {
          ...(previous ? {
            width: previous.width,
            height: previous.height,
            initialWidth: previous.initialWidth,
            initialHeight: previous.initialHeight
          } : {}),
          ...(measured ? { measured } : {}),
          id: el.id,
          type: 'umlClass',
          position: { x: el.posicionX, y: el.posicionY },
          selected: previous?.selected ?? false,
          data: {
            elementId: el.id,
            name: el.nombre,
            elementType: el.tipoElemento,
            propertiesJson: el.propiedadesJson,
            readOnly: isReadOnly,
            onEdit: handleEditElement,
            onDelete: requestDeleteElement
          }
        }
      })
    },
    [isReadOnly, handleEditElement, requestDeleteElement, historicalNodeMeasurements]
  )

  // Transformar datos de relaciones a aristas de React Flow
  const buildEdges = useCallback((relations: RelationDetail[], elements: ElementDetail[] = elementsRaw): Edge[] => {
    const plannedEdges = relations.map((rel) => {
      const sourceElement = elements.find((element) => element.id === rel.elementoOrigen)
      const targetElement = elements.find((element) => element.id === rel.elementoDestino)
      const isReflexive = rel.elementoOrigen === rel.elementoDestino
      const deltaX = (targetElement?.posicionX ?? 0) - (sourceElement?.posicionX ?? 0)
      const deltaY = (targetElement?.posicionY ?? 0) - (sourceElement?.posicionY ?? 0)
      const horizontal = isReflexive || Math.abs(deltaX) >= Math.abs(deltaY)
      const sourcePosition = horizontal
        ? (isReflexive ? Position.Right : deltaX >= 0 ? Position.Right : Position.Left)
        : (deltaY >= 0 ? Position.Bottom : Position.Top)
      const targetPosition = horizontal
        ? (isReflexive ? Position.Left : deltaX >= 0 ? Position.Left : Position.Right)
        : (deltaY >= 0 ? Position.Top : Position.Bottom)

      return { rel, sourcePosition, targetPosition, isReflexive }
    })

    const sourceSlots = new Map<string, number>()
    const targetSlots = new Map<string, number>()

    return plannedEdges.map(({ rel, sourcePosition, targetPosition, isReflexive }) => {
      const sourceSlotKey = `${rel.elementoOrigen}:${sourcePosition}`
      const targetSlotKey = `${rel.elementoDestino}:${targetPosition}`
      const sourceSlot = sourceSlots.get(sourceSlotKey) ?? 0
      const targetSlot = targetSlots.get(targetSlotKey) ?? 0
      sourceSlots.set(sourceSlotKey, (sourceSlot + 1) % 3)
      targetSlots.set(targetSlotKey, (targetSlot + 1) % 3)

      const relationWithHandles = rel as RelationWithHandles
      const override = relationHandleOverridesRef.current.get(rel.id)
      const sourceHandle = normalizeRelationHandle(
        override?.sourceHandle ?? relationWithHandles.sourceHandle,
        'source',
        sourcePosition,
        sourceSlot
      )
      const targetHandle = normalizeRelationHandle(
        override?.targetHandle ?? relationWithHandles.targetHandle,
        'target',
        targetPosition,
        targetSlot
      )

      const isHeritage = rel.tipoRelacion === 'HERENCIA'
      const isDependency = rel.tipoRelacion === 'DEPENDENCIA'
      const isAggregation = rel.tipoRelacion === 'AGREGACION'
      const isComposition = rel.tipoRelacion === 'COMPOSICION'

      const label = [
        rel.nombreRelacion ? `«${rel.nombreRelacion}»` : '',
        rel.multiplicidadOrigen && rel.multiplicidadDestino
          ? `${rel.multiplicidadOrigen} .. ${rel.multiplicidadDestino}`
          : rel.multiplicidadOrigen || rel.multiplicidadDestino || ''
      ]
        .filter(Boolean)
        .join(' ')

      return {
        id: rel.id,
        source: rel.elementoOrigen,
        target: rel.elementoDestino,
        type: isReflexive
          ? 'umlReflexive'
          : isAggregation || isComposition
          ? 'umlOrthogonal'
          : 'step',
        sourcePosition: positionFromRelationHandle(sourceHandle, sourcePosition),
        targetPosition: positionFromRelationHandle(targetHandle, targetPosition),
        sourceHandle,
        targetHandle,
        data: { tipoRelacion: rel.tipoRelacion },
        pathOptions: { offset: 18, stepPosition: 0.5 },
        label: label || undefined,
        animated: isDependency,
        style: {
          stroke: isComposition ? '#1e293b' : isAggregation ? '#0284c7' : '#475569',
          strokeWidth: isComposition || isAggregation ? 2.5 : 2,
          strokeDasharray: isDependency ? '5,5' : undefined
        },
        markerEnd: isHeritage
          ? { type: MarkerType.ArrowClosed, color: '#475569', width: 22, height: 22 }
          : isDependency
          ? { type: MarkerType.Arrow, color: '#475569' }
          : undefined,
        markerStart: undefined
      }
    })
  }, [elementsRaw])

  const rememberRelationHandles = useCallback((relationId: string, connection: Connection) => {
    const currentDiagram = diagramRef.current
    if (!currentDiagram) return
    relationHandleOverridesRef.current.set(relationId, {
      sourceHandle: connection.sourceHandle ?? null,
      targetHandle: connection.targetHandle ?? null
    })
    saveRelationHandleOverrides(currentDiagram.id, relationHandleOverridesRef.current)
  }, [])

  const handleFullDiagramUpdated = useCallback(
    (full: DiagramFullView) => {
      if (!acceptServerRevision(full.diagram.id, full.diagram.revision)) return
      setHistoricalView(null)
      setLoadedBaseDiagramId(full.diagram.id)
      setDiagram(full.diagram)
      setElementsRaw(full.elements)
      setRelationsRaw(full.relations)
      saveServerDiagramSnapshot(full.diagram.id, full)
      setNodes(buildNodes(full.elements))
      setEdges(buildEdges(full.relations, full.elements))
    },
    [buildNodes, buildEdges, setNodes, setEdges, acceptServerRevision]
  )

  const fitUpdatedDiagram = useCallback(() => {
    window.setTimeout(() => {
      void flowInstance.current?.fitView({ padding: 0.25, maxZoom: 1.15, duration: 180 })
    }, 180)
  }, [])

  const handleRestoredDiagram = useCallback((full: DiagramFullView) => {
    handleFullDiagramUpdated(full)
    fitUpdatedDiagram()
  }, [fitUpdatedDiagram, handleFullDiagramUpdated])

  // Sincronizar cola de operaciones pendientes al reconectar a internet (CU10)
  const flushPendingQueue = useCallback(
    async (diagramId: string) => {
      if (historicalView) return
      const queue = getOfflineQueue(diagramId)
      if (queue.length === 0) return
      if (queue.some(action => action.requiresReview)) {
        setPendingQueueCount(queue.length)
        setSavingStatus('Conflicto de concurrencia: hay cambios locales pendientes de revisión; confirma su reintento.')
        return
      }
      if (activeQueueFlushesRef.current.has(diagramId)) return
      activeQueueFlushesRef.current.add(diagramId)

      setIsSyncingQueue(true)
      setSavingStatus('Sincronizando...')

      try {
        const serverBefore = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagramId}`,
          auth.token
        )
        const baseline = loadServerDiagramSnapshot(diagramId)
        if (!acceptServerRevision(diagramId, serverBefore.diagram.revision)) return
        if (!snapshotContentMatches(baseline, serverBefore)) {
          const optimistic = loadLocalDiagramSnapshot(diagramId)
          if (optimistic && snapshotContentMatches(optimistic, serverBefore)) {
            setOfflineQueue(diagramId, [])
            setPendingQueueCount(0)
            setElementsRaw(serverBefore.elements)
            setRelationsRaw(serverBefore.relations)
            saveServerDiagramSnapshot(diagramId, serverBefore)
            saveLocalDiagramSnapshot(diagramId, serverBefore.diagram, serverBefore.elements, serverBefore.relations)
            setSavingStatus('Guardado')
            return
          }
          const reviewQueue = markPendingQueueForReview(getOfflineQueue(diagramId))
          setOfflineQueue(diagramId, reviewQueue)
          setPendingQueueCount(reviewQueue.length)
          setSavingStatus('Conflicto de concurrencia: el servidor cambió. Se conservaron cambios locales; revisa y confirma el reintento.')
          setSavingStatus('Error al sincronizar: el diagrama cambió en el servidor. Se conservaron los cambios locales; revisa el modelo antes de reintentar.')
          return
        }
        if (!baseline) saveServerDiagramSnapshot(diagramId, serverBefore)

        const normalizedQueue = normalizePendingOperations(queue, baseline)
        setOfflineQueue(diagramId, normalizedQueue)
        let currentRevision = serverBefore.diagram.revision ?? 1
        const replay = await replayPendingQueue(normalizedQueue, async (action) => {
          const revHeaders: Record<string, string> = {
            'X-Diagram-Revision': String(currentRevision)
          }
          try {
            let resRev: number | null = null
            if (action.type === 'SAVE_ELEMENT' || action.type === 'UPDATE_ELEMENT') {
              const update = action.type === 'UPDATE_ELEMENT'
              const elementPath = `/api/projects/${project.id}/diagrams/${diagramId}/elements`
              const res = await requestFull(update ? `${elementPath}/${action.payload.id}` : elementPath, auth.token, {
                method: update ? 'PUT' : 'POST',
                headers: revHeaders,
                body: JSON.stringify({ ...action.payload, expectedRevision: currentRevision })
              })
              resRev = res.revision
            } else if (action.type === 'DELETE_ELEMENT') {
              const res = await requestFull(`/api/projects/${project.id}/diagrams/${diagramId}/elements/${action.payload.elementId}`,
                auth.token, { method: 'DELETE', headers: revHeaders })
              resRev = res.revision
            } else if (action.type === 'SAVE_POSITIONS') {
              const res = await requestFull(`/api/projects/${project.id}/diagrams/${diagramId}/elements/positions`, auth.token, {
                method: 'POST',
                headers: revHeaders,
                body: JSON.stringify({ ...action.payload, expectedRevision: currentRevision })
              })
              resRev = res.revision
            } else if (action.type === 'SAVE_RELATION' || action.type === 'UPDATE_RELATION') {
              const update = action.type === 'UPDATE_RELATION'
              const relationPath = `/api/projects/${project.id}/diagrams/${diagramId}/relations`
              const res = await requestFull(update ? `${relationPath}/${action.payload.id}` : relationPath, auth.token, {
                method: update ? 'PUT' : 'POST',
                headers: revHeaders,
                body: JSON.stringify({ ...action.payload, expectedRevision: currentRevision })
              })
              resRev = res.revision
            } else if (action.type === 'DELETE_RELATION') {
              const res = await requestFull(`/api/projects/${project.id}/diagrams/${diagramId}/relations/${action.payload.relationId}`,
                auth.token, { method: 'DELETE', headers: revHeaders })
              resRev = res.revision
            }
            if (resRev != null && !isNaN(resRev)) {
              currentRevision = resRev
            } else {
              currentRevision++
            }
          } catch (error) {
            // Si el borrado se aplicó pero se perdió la respuesta, repetirlo ya está satisfecho.
            if ((action.type === 'DELETE_ELEMENT' || action.type === 'DELETE_RELATION') &&
                error instanceof ApiRequestError && error.status === 404) return
            throw error
          }
        })
        const completed = new Set(replay.completedIds)
        const pending = getOfflineQueue(diagramId).filter((action) => !completed.has(action.id))
        setOfflineQueue(diagramId, pending)
        setPendingQueueCount(pending.length)

        if (replay.error) {
          if (replay.error instanceof ApiRequestError && replay.error.status === 409) {
            const full = await request<DiagramFullView>(
              `/api/projects/${project.id}/diagrams/${diagramId}`,
              auth.token
            ).catch(() => null)
            if (full && acceptServerRevision(diagramId, full.diagram.revision)) {
              saveServerDiagramSnapshot(diagramId, full)
              setDiagram(full.diagram)
            }
            const reviewQueue = markPendingQueueForReview(pending)
            setOfflineQueue(diagramId, reviewQueue)
            setPendingQueueCount(reviewQueue.length)
            setSavingStatus(`Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409). Se conservaron ${pending.length} cambio(s) locales; revísalos antes de reintentar.`)
            return
          }
          const detail = replay.error instanceof Error ? replay.error.message : 'Error de comunicación.'
          setSavingStatus(`Error al sincronizar: ${pending.length} cambio(s) siguen guardados localmente. ${detail}`)
          return
        }

        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagramId}`,
          auth.token
        )
        if (!acceptServerRevision(diagramId, full.diagram.revision)) return
        setDiagram(full.diagram)
        setElementsRaw(full.elements)
        setRelationsRaw(full.relations)
        saveServerDiagramSnapshot(diagramId, full)
        saveLocalDiagramSnapshot(diagramId, diagramRef.current, full.elements, full.relations)
        setSavingStatus(pending.length ? `Sincronizado; ${pending.length} cambio(s) siguen pendientes` : 'Guardado')
        if (pending.length) window.setTimeout(() => flushPendingQueue(diagramId), 3000)
      } catch (error) {
        const detail = error instanceof Error ? error.message : 'Error de comunicación.'
        const remaining = getOfflineQueue(diagramId).length
        setPendingQueueCount(remaining)
        setSavingStatus(remaining
          ? `Error al sincronizar: ${remaining} cambio(s) se conservaron localmente. ${detail}`
          : `Cambios sincronizados, pero no se pudo actualizar la vista del servidor. ${detail}`)
      } finally {
        activeQueueFlushesRef.current.delete(diagramId)
        setIsSyncingQueue(false)
      }
    },
    [project.id, auth.token, historicalView, acceptServerRevision]
  )

  const handleRetryPendingSync = useCallback(async () => {
    const currentDiagram = diagramRef.current
    if (!currentDiagram || !isOnline || historicalView) return
    const queue = getOfflineQueue(currentDiagram.id)
    if (!queue.some(action => action.requiresReview) || !window.confirm(
      'Se volverán a enviar los cambios locales usando la revisión actual del servidor. Si otro colaborador modificó los mismos elementos, este reintento aplicará tus cambios después de tu confirmación. ¿Continuar?'
    )) return

    try {
      setSavingStatus('Actualizando la base de comparación...')
      const full = await request<DiagramFullView>(`/api/projects/${project.id}/diagrams/${currentDiagram.id}`, auth.token)
      if (!acceptServerRevision(currentDiagram.id, full.diagram.revision)) return
      saveServerDiagramSnapshot(currentDiagram.id, full)
      const retryQueue = queue.map(action => ({ ...action, requiresReview: false }))
      setOfflineQueue(currentDiagram.id, retryQueue)
      setPendingQueueCount(retryQueue.length)
      await flushPendingQueue(currentDiagram.id)
    } catch (error) {
      setSavingStatus('No se pudo actualizar el diagrama antes del reintento. Los cambios locales siguen pendientes.')
      onError(error)
    }
  }, [isOnline, historicalView, project.id, auth.token, acceptServerRevision, flushPendingQueue, onError])

  // Escuchar eventos de conexión a internet (CU10 Requerimiento 7)
  useEffect(() => {
    function handleOnline() {
      setIsOnline(true)
      const currentDiagramId = diagramRef.current?.id
      if (currentDiagramId) {
        flushPendingQueue(currentDiagramId)
      }
    }

    function handleOffline() {
      setIsOnline(false)
      setSavingStatus('Sin conexión (Offline)')
    }

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [flushPendingQueue])

  // Carga inicial única del diagrama al montar el componente (con soporte offline)
  useEffect(() => {
    let isMounted = true
    async function init() {
      setLoadedBaseDiagramId('')
      try {
        setSavingStatus('Cargando...')
        if (!navigator.onLine) {
          const cachedDiagramId = localStorage.getItem(`case.last_diagram.${project.id}`)
          if (cachedDiagramId) {
            const cached = loadLocalDiagramSnapshot(cachedDiagramId)
            if (cached && isMounted) {
              if (cached.diagram?.revision != null) diagramRequestCoordinator.observeRevision(cachedDiagramId, cached.diagram.revision)
              setDiagram(cached.diagram)
              setLoadedBaseDiagramId(cachedDiagramId)
              setElementsRaw(cached.elements)
              setRelationsRaw(cached.relations)
              setPendingQueueCount(getOfflineQueue(cachedDiagramId).length)
              setSavingStatus('Cargado offline')
              return
            }
          }
        }

        const def = await request<DiagramSummary>(
          `/api/projects/${project.id}/diagrams/default`,
          auth.token
        )
        if (!isMounted) return
        if (def.revision != null) diagramRequestCoordinator.observeRevision(def.id, def.revision)
        setDiagram(def)
        localStorage.setItem(`case.last_diagram.${project.id}`, def.id)

        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${def.id}`,
          auth.token
        )
        if (!isMounted) return
        if (!acceptServerRevision(def.id, full.diagram.revision)) return
        const pendingQueue = getOfflineQueue(def.id)
        const localPending = pendingQueue.length > 0 ? loadLocalDiagramSnapshot(def.id) : null
        const displayElements = localPending?.elements ?? mergePendingPositions(full.elements, pendingQueue)
        const displayRelations = localPending?.relations ?? full.relations
        setDiagram(full.diagram)
        setElementsRaw(displayElements)
        setRelationsRaw(displayRelations)
        setLoadedBaseDiagramId(def.id)
        saveServerDiagramSnapshot(def.id, full)
        if (!pendingQueue.length) saveLocalDiagramSnapshot(def.id, full.diagram, full.elements, full.relations)
        else saveLocalDiagramSnapshot(def.id, full.diagram, displayElements, displayRelations)
        setPendingQueueCount(pendingQueue.length)
        setSavingStatus(pendingQueue.some(action => action.requiresReview)
          ? 'Conflicto de concurrencia: se restauraron cambios locales pendientes de revisión.'
          : pendingQueue.length ? `${pendingQueue.length} cambio(s) local(es) pendientes` : 'Guardado')

        if (navigator.onLine && pendingQueue.length > 0) {
          flushPendingQueue(def.id)
        }
      } catch (err) {
        if (!isMounted) return
        const cachedDiagramId = localStorage.getItem(`case.last_diagram.${project.id}`)
        if (cachedDiagramId) {
          const cached = loadLocalDiagramSnapshot(cachedDiagramId)
          if (cached) {
            setDiagram(cached.diagram)
            setLoadedBaseDiagramId(cachedDiagramId)
            setElementsRaw(cached.elements)
            setRelationsRaw(cached.relations)
            setPendingQueueCount(getOfflineQueue(cachedDiagramId).length)
            setSavingStatus('Cargado offline')
            return
          }
        }
        setSavingStatus('Error al cargar')
        onError(err)
      }
    }

    init()
    return () => {
      isMounted = false
    }
  }, [project.id, auth.token, acceptServerRevision])

  const [wsConnected, setWsConnected] = useState(false)

  // Caso de Uso 8: Conexión WebSocket para sincronización bidireccional en tiempo real (solo si online)
  useEffect(() => {
    if (!diagram || !isOnline) return
    const currentDiagramId = diagram.id
    let ws: WebSocket | null = null
    let isDestroyed = false

    function connectWs() {
      if (isDestroyed || !navigator.onLine) return
      try {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        let host = window.location.host
        if (base) {
          host = base.replace(/^https?:\/\//, '')
        }
        ws = new WebSocket(`${protocol}//${host}/ws/diagrams/${currentDiagramId}`, ['case-events-v1', `bearer.${auth.token}`])

        ws.onopen = () => {
          if (!isDestroyed) setWsConnected(true)
          window.dispatchEvent(new CustomEvent('case:canvas-proposal', { detail: { diagramId: currentDiagramId } }))
        }

        ws.onmessage = async (event) => {
          try {
            const data = JSON.parse(event.data)
            if (isDestroyed || data.diagramId !== currentDiagramId) return
            if (data.type === 'UML_PROPOSAL_CHANGED') {
              window.dispatchEvent(new CustomEvent('case:canvas-proposal', { detail: data })); return
            }
            if (data.diagramId === currentDiagramId && navigator.onLine && getOfflineQueue(currentDiagramId).length === 0) {
              const full = await request<DiagramFullView>(
                `/api/projects/${project.id}/diagrams/${currentDiagramId}`,
                auth.token
              )
              if (!acceptServerRevision(currentDiagramId, full.diagram.revision)) return
              saveServerDiagramSnapshot(currentDiagramId, full)
              setDiagram(full.diagram)
              setElementsRaw((prev) => {
                const prevStr = JSON.stringify(prev)
                const nextStr = JSON.stringify(full.elements)
                return prevStr === nextStr ? prev : full.elements
              })
              setRelationsRaw((prev) => {
                const prevStr = JSON.stringify(prev)
                const nextStr = JSON.stringify(full.relations)
                return prevStr === nextStr ? prev : full.relations
              })
            }
          } catch {
            // Ignorar errores de parseo
          }
        }

        ws.onclose = () => {
          if (!isDestroyed) {
            setWsConnected(false)
            if (navigator.onLine) setTimeout(connectWs, 3000)
          }
        }

        ws.onerror = () => {
          if (!isDestroyed) setWsConnected(false)
        }
      } catch {
        if (!isDestroyed) setWsConnected(false)
      }
    }

    connectWs()

    return () => {
      isDestroyed = true
      if (ws) ws.close()
    }
  }, [diagram?.id, project.id, auth.token, isOnline, acceptServerRevision])

  // Caso de Uso 8: Sincronización continua reactiva (cada 1.5s <= 2s solo si online)
  useEffect(() => {
    if (!diagram?.id || !isOnline) return
    const interval = setInterval(async () => {
      if (!navigator.onLine) return
      if (getOfflineQueue(diagram.id).length > 0) return
      try {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        )
        if (!acceptServerRevision(diagram.id, full.diagram.revision)) return
        saveServerDiagramSnapshot(diagram.id, full)
        setDiagram(full.diagram)
        setElementsRaw((prev) => {
          const prevStr = JSON.stringify(prev)
          const nextStr = JSON.stringify(full.elements)
          return prevStr === nextStr ? prev : full.elements
        })
        setRelationsRaw((prev) => {
          const prevStr = JSON.stringify(prev)
          const nextStr = JSON.stringify(full.relations)
          return prevStr === nextStr ? prev : full.relations
        })
      } catch {
        // En caso de corte momentáneo de red, se mantiene el estado local
      }
    }, 1500)

    return () => clearInterval(interval)
  }, [diagram?.id, project.id, auth.token, isOnline, acceptServerRevision])

  // Sincronizar nodos locales cuando cambian elementsRaw sin perder selección
  useEffect(() => {
    if (!diagram?.id) return
    const overrides = loadRelationHandleOverrides(diagram.id)
    for (const relation of relationsRaw as RelationWithHandles[]) {
      if (!overrides.has(relation.id) && (relation.sourceHandle || relation.targetHandle)) {
        overrides.set(relation.id, {
          sourceHandle: relation.sourceHandle ?? null,
          targetHandle: relation.targetHandle ?? null
        })
      }
    }
    relationHandleOverridesRef.current = overrides
  }, [diagram?.id])

  // Sincronizar nodos locales cuando cambian elementsRaw sin perder selección
  useEffect(() => {
    setNodes((prev) => buildNodes(elementsRaw, prev))
  }, [elementsRaw, buildNodes, setNodes])

  // Sincronizar aristas locales cuando cambian relationsRaw
  useEffect(() => {
    setEdges(buildEdges(relationsRaw, elementsRaw))
  }, [relationsRaw, elementsRaw, buildEdges, setEdges])

  // Atajos de teclado: Ctrl+A (seleccionar todo), Escape (deseleccionar), Delete (eliminar seleccionados)
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      const active = document.activeElement
      const isInput =
        active &&
        (active.tagName === 'INPUT' ||
          active.tagName === 'TEXTAREA' ||
          active.tagName === 'SELECT' ||
          (active as HTMLElement).isContentEditable)

      if (isInput || canvasProposal.active) return

      // Ctrl + A o Cmd + A: Seleccionar todas las clases dentro del lienzo
      if ((e.ctrlKey || e.metaKey) && (e.key === 'a' || e.key === 'A')) {
        e.preventDefault()
        setNodes((prev) => prev.map((n) => ({ ...n, selected: true })))
      }

      // Escape: Deseleccionar todos los elementos
      if (e.key === 'Escape') {
        setNodes((prev) => prev.map((n) => ({ ...n, selected: false })))
      }

      // Delete o Supr: Eliminar elementos seleccionados con confirmación
      if (e.key === 'Delete') {
        e.preventDefault()
        handleDeleteSelected()
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [handleDeleteSelected, setNodes, canvasProposal.active])

  // Al finalizar de arrastrar uno o varios nodos seleccionados, persistir coordenadas juntas (con soporte Offline)
  const handleNodeDragStop = useCallback(
    async (_event: unknown, node: UmlNodeType, draggedNodes?: UmlNodeType[]) => {
      const currentDiagram = diagramRef.current
      if (isReadOnly || !currentDiagram) return
      const movedList = draggedNodes && draggedNodes.length > 0 ? draggedNodes : [node]
      const positions = movedList.map((n) => ({
        id: n.id,
        posicionX: n.position.x,
        posicionY: n.position.y
      }))

      const posMap = new Map(positions.map((p) => [p.id, p]))
      const nextElements = elementsRef.current.map((e) => {
        const upd = posMap.get(e.id)
        return upd ? { ...e, posicionX: upd.posicionX, posicionY: upd.posicionY } : e
      })
      elementsRef.current = nextElements
      setElementsRaw(nextElements)
      saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, relationsRef.current)

      if (!navigator.onLine || getOfflineQueue(currentDiagram.id).length > 0) {
        const count = appendOfflineQueue(
          currentDiagram.id,
          {
            type: 'SAVE_POSITIONS',
            payload: { positions }
          },
          currentDiagram.revision
        )
        setPendingQueueCount(count)
        setSavingStatus('Guardado localmente (Offline)')
        return
      }

      try {
        setSavingStatus('Sincronizando...')
        const expectedRevision = diagramRequestCoordinator.revision(currentDiagram.id) ?? currentDiagram.revision
        const response = await requestFull(
          `/api/projects/${project.id}/diagrams/${currentDiagram.id}/elements/positions`,
          auth.token,
          {
            method: 'POST',
            headers: expectedRevision != null ? { 'X-Diagram-Revision': String(expectedRevision) } : {},
            body: JSON.stringify({ positions, expectedRevision })
          }
        )
        applyConfirmedDiagramRevision(currentDiagram.id)
        saveLocalDiagramSnapshot(currentDiagram.id, diagramRef.current ?? currentDiagram, elementsRef.current, relationsRef.current)
        setSavingStatus(response.revision == null ? 'Guardado; revisión confirmada en cola' : 'Guardado')
      } catch (err) {
        if (err instanceof ApiRequestError && err.status === 409) {
          const full = await request<DiagramFullView>(
            `/api/projects/${project.id}/diagrams/${currentDiagram.id}`,
            auth.token
          ).catch(() => null)
          const count = appendOfflineQueue(
            currentDiagram.id,
            { type: 'SAVE_POSITIONS', payload: { positions } },
            full?.diagram.revision ?? diagramRequestCoordinator.revision(currentDiagram.id) ?? currentDiagram.revision,
            true
          )
          if (full && acceptServerRevision(currentDiagram.id, full.diagram.revision)) {
            setDiagram(full.diagram)
            const mergedElements = mergePendingPositions(full.elements, getOfflineQueue(currentDiagram.id))
            elementsRef.current = mergedElements
            setElementsRaw(mergedElements)
            setRelationsRaw(full.relations)
            relationsRef.current = full.relations
            saveServerDiagramSnapshot(currentDiagram.id, full)
            saveLocalDiagramSnapshot(currentDiagram.id, full.diagram, mergedElements, full.relations)
          }
          setPendingQueueCount(count)
          setSavingStatus('Conflicto de concurrencia al mover posiciones (HTTP 409). Se conservó el movimiento local; revísalo y confirma el reintento.')
          return
        }
        const count = appendOfflineQueue(
          currentDiagram.id,
          {
            type: 'SAVE_POSITIONS',
            payload: { positions }
          },
          currentDiagram.revision
        )
        setPendingQueueCount(count)
        setSavingStatus('Guardado localmente (Offline)')
      }
    },
    [isReadOnly, project.id, auth.token, applyConfirmedDiagramRevision, acceptServerRevision]
  )

  // Crear nuevo elemento en el centro (con soporte Offline CU10)
  async function handleAddElement(tipo: TipoElementoUML = 'CLASE') {
    if (isReadOnly || !diagram) return
    const nombreDefecto =
      tipo === 'INTERFAZ'
        ? 'NuevaInterfaz'
        : tipo === 'CLASE_ABSTRACTA'
        ? 'NuevaClaseAbstracta'
        : tipo === 'ENUMERACION'
        ? 'NuevaEnumeracion'
        : 'NuevaClase'

    const randomOffset = Math.floor(Math.random() * 80)
    const payload = {
      id: crypto.randomUUID(),
      tipoElemento: tipo,
      nombre: nombreDefecto,
      posicionX: 180 + randomOffset,
      posicionY: 120 + randomOffset,
      propiedadesJson: JSON.stringify({
        description: '',
        stereotype: '',
        attributes: [],
        methods: []
      })
    }

    if (!navigator.onLine || getOfflineQueue(diagram.id).length > 0) {
      const nuevo: ElementDetail = {
        diagramaId: diagram.id,
        ...payload
      }
      const nextElements = [...elementsRaw, nuevo]
      setElementsRaw(nextElements)
      saveLocalDiagramSnapshot(diagram.id, diagram, nextElements, relationsRaw)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'SAVE_ELEMENT',
          payload: nuevo
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setSavingStatus('Guardado localmente (Offline)')
      return
    }

    try {
      setSavingStatus('Guardando...')
      const headers: Record<string, string> =
        diagram.revision != null ? { 'X-Diagram-Revision': String(diagram.revision) } : {}
      const saved = await request<ElementDetail>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/elements`,
        auth.token,
        {
          method: 'POST',
          headers,
          body: JSON.stringify({ ...payload, expectedRevision: diagram.revision })
        }
      )
      const nextElements = [...elementsRaw, saved]
      setElementsRaw(nextElements)
      applyConfirmedDiagramRevision(diagram.id)
      saveLocalDiagramSnapshot(diagram.id, diagram, nextElements, relationsRaw)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full && acceptServerRevision(diagram.id, full.diagram.revision)) {
          setDiagram(full.diagram)
          setElementsRaw(full.elements)
          setRelationsRaw(full.relations)
          saveServerDiagramSnapshot(diagram.id, full)
        }
        setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
        return
      }
      const localElement: ElementDetail = { diagramaId: diagram.id, ...payload }
      const nextElements = [...elementsRaw, localElement]
      setElementsRaw(nextElements)
      saveLocalDiagramSnapshot(diagram.id, diagram, nextElements, relationsRaw)
      const count = appendOfflineQueue(
        diagram.id,
        { type: 'SAVE_ELEMENT', payload: localElement },
        diagram.revision
      )
      setPendingQueueCount(count)
      setSavingStatus(pendingOperationStatus(err))
    }
  }

  // Guardar cambios del modal de elemento (con soporte Offline CU10)
  async function handleSaveElementDetails(
    nombre: string,
    tipoElemento: TipoElementoUML,
    propiedades: UmlProperties
  ) {
    if (!editingElement || !diagram) return
    const payload = {
      id: editingElement.id,
      tipoElemento,
      nombre,
      posicionX: editingElement.posicionX,
      posicionY: editingElement.posicionY,
      propiedadesJson: JSON.stringify(propiedades)
    }

    const nextElements = elementsRaw.map((e) =>
      e.id === editingElement.id ? { ...e, ...payload } : e
    )

    if (!navigator.onLine || getOfflineQueue(diagram.id).length > 0) {
      setElementsRaw(nextElements)
      saveLocalDiagramSnapshot(diagram.id, diagram, nextElements, relationsRaw)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'UPDATE_ELEMENT',
          payload
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setEditingElement(null)
      setSavingStatus('Guardado localmente (Offline)')
      return
    }

    try {
      setSavingStatus('Guardando...')
      const headers: Record<string, string> =
        diagram.revision != null ? { 'X-Diagram-Revision': String(diagram.revision) } : {}
      const saved = await request<ElementDetail>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/elements/${payload.id}`,
        auth.token,
        {
          method: 'PUT',
          headers,
          body: JSON.stringify({ ...payload, expectedRevision: diagram.revision })
        }
      )
      const updatedElements = elementsRaw.map((e) => (e.id === saved.id ? saved : e))
      setElementsRaw(updatedElements)
      applyConfirmedDiagramRevision(diagram.id)
      saveLocalDiagramSnapshot(diagram.id, diagram, updatedElements, relationsRaw)
      setEditingElement(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full && acceptServerRevision(diagram.id, full.diagram.revision)) {
          setDiagram(full.diagram)
          setElementsRaw(full.elements)
          setRelationsRaw(full.relations)
          saveServerDiagramSnapshot(diagram.id, full)
        }
        setEditingElement(null)
        setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
        return
      }
      setElementsRaw(nextElements)
      saveLocalDiagramSnapshot(diagram.id, diagram, nextElements, relationsRaw)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'UPDATE_ELEMENT',
          payload
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setEditingElement(null)
      setSavingStatus(pendingOperationStatus(err))
    }
  }

  // Conexión arrastrada entre dos nodos
  const handleConnect = useCallback(
    (connection: Connection) => {
      if (isReadOnly) return
      if (!connection.source || !connection.target) return

      if (connection.source === connection.target) {
        setPendingConnection(ensureReflexiveHandlePair(connection))
        return
      }

      setPendingConnection(connection)
    },
    [isReadOnly]
  )

  const handleReconnect = useCallback(
    (oldEdge: Edge, newConnection: Connection) => {
      if (isReadOnly || !newConnection.source || !newConnection.target) return
      if (newConnection.source !== oldEdge.source || newConnection.target !== oldEdge.target) return

      const safeConnection = newConnection.source === newConnection.target
        ? ensureReflexiveHandlePair(newConnection)
        : newConnection
      rememberRelationHandles(oldEdge.id, safeConnection)
      setEdges((currentEdges) =>
        reconnectEdge(oldEdge, safeConnection, currentEdges, { shouldReplaceId: false }).map((edge) =>
          edge.id === oldEdge.id
            ? {
                ...edge,
                sourcePosition: positionFromRelationHandle(safeConnection.sourceHandle, Position.Right),
                targetPosition: positionFromRelationHandle(safeConnection.targetHandle, Position.Left)
              }
            : edge
        )
      )
      setSavingStatus('Conexión actualizada')
    },
    [isReadOnly, rememberRelationHandles, setEdges]
  )

  // Guardar nueva relación desde el modal (con soporte Offline CU10)
  async function handleSavePendingRelation(
    tipoRelacion: TipoRelacionUML,
    nombreRelacion: string | null,
    multiplicidadOrigen: string | null,
    multiplicidadDestino: string | null
  ) {
    if (!pendingConnection || !diagram) return
    const payload = {
      id: crypto.randomUUID(),
      elementoOrigen: pendingConnection.source,
      elementoDestino: pendingConnection.target,
      nombreRelacion,
      tipoRelacion,
      multiplicidadOrigen,
      multiplicidadDestino
    }

    if (!navigator.onLine || getOfflineQueue(diagram.id).length > 0) {
      const localRel: RelationDetail = {
        id: payload.id,
        diagramaId: diagram.id,
        elementoOrigen: pendingConnection.source,
        elementoDestino: pendingConnection.target,
        nombreRelacion,
        tipoRelacion,
        multiplicidadOrigen,
        multiplicidadDestino
      }
      const nextRels = [...relationsRaw, localRel]
      rememberRelationHandles(localRel.id, pendingConnection)
      setRelationsRaw(nextRels)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'SAVE_RELATION',
          payload
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setPendingConnection(null)
      setSavingStatus('Guardado localmente (Offline)')
      return
    }

    try {
      setSavingStatus('Guardando relación...')
      const headers: Record<string, string> =
        diagram.revision != null ? { 'X-Diagram-Revision': String(diagram.revision) } : {}
      const saved = await request<RelationDetail>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/relations`,
        auth.token,
        {
          method: 'POST',
          headers,
          body: JSON.stringify({ ...payload, expectedRevision: diagram.revision })
        }
      )

      const nextRels = [...relationsRaw, saved]
      rememberRelationHandles(saved.id, pendingConnection)
      setRelationsRaw(nextRels)
      applyConfirmedDiagramRevision(diagram.id)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      setPendingConnection(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full && acceptServerRevision(diagram.id, full.diagram.revision)) {
          setDiagram(full.diagram)
          setElementsRaw(full.elements)
          setRelationsRaw(full.relations)
          saveServerDiagramSnapshot(diagram.id, full)
        }
        setPendingConnection(null)
        setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
        return
      }
      const localRel: RelationDetail = {
        id: payload.id,
        diagramaId: diagram.id,
        elementoOrigen: pendingConnection.source,
        elementoDestino: pendingConnection.target,
        nombreRelacion,
        tipoRelacion,
        multiplicidadOrigen,
        multiplicidadDestino
      }
      const nextRels = [...relationsRaw, localRel]
      rememberRelationHandles(localRel.id, pendingConnection)
      setRelationsRaw(nextRels)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'SAVE_RELATION',
          payload
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setPendingConnection(null)
      setSavingStatus(pendingOperationStatus(err))
    }
  }

  // Clic sobre una relación para editar o eliminar
  function handleEdgeClick(_event: React.MouseEvent, edge: Edge) {
    const rel = relationsRaw.find((r) => r.id === edge.id)
    if (commentMode && rel) {
      openCanvasComment(_event)
      return
    }
    if (rel) setEditingRelation(rel)
  }

  function handleNodeClick(_event: React.MouseEvent, node: UmlNodeType) {
    if (!commentMode) return
    openCanvasComment(_event)
  }

  function openCanvasComment(event: React.MouseEvent) {
    const position = flowInstance.current?.screenToFlowPosition({ x: event.clientX, y: event.clientY })
    if (!position) return
    setDraftComment({ x: position.x, y: position.y, content: '' })
    setCommentMode(false)
  }

  // Guardar edición de relación existente (con soporte Offline CU10)
  async function handleUpdateRelation(
    tipoRelacion: TipoRelacionUML,
    nombreRelacion: string | null,
    multiplicidadOrigen: string | null,
    multiplicidadDestino: string | null
  ) {
    if (!editingRelation || !diagram) return
    const payload = {
      id: editingRelation.id,
      elementoOrigen: editingRelation.elementoOrigen,
      elementoDestino: editingRelation.elementoDestino,
      nombreRelacion,
      tipoRelacion,
      multiplicidadOrigen,
      multiplicidadDestino
    }

    const nextRels = relationsRaw.map((r) =>
      r.id === editingRelation.id ? { ...r, ...payload } : r
    )

    if (!navigator.onLine || getOfflineQueue(diagram.id).length > 0) {
      setRelationsRaw(nextRels)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'UPDATE_RELATION',
          payload
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setEditingRelation(null)
      setSavingStatus('Guardado localmente (Offline)')
      return
    }

    try {
      setSavingStatus('Actualizando relación...')
      const headers: Record<string, string> =
        diagram.revision != null ? { 'X-Diagram-Revision': String(diagram.revision) } : {}
      const saved = await request<RelationDetail>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/relations/${payload.id}`,
        auth.token,
        {
          method: 'PUT',
          headers,
          body: JSON.stringify({ ...payload, expectedRevision: diagram.revision })
        }
      )

      const updatedRels = relationsRaw.map((r) => (r.id === saved.id ? saved : r))
      setRelationsRaw(updatedRels)
      applyConfirmedDiagramRevision(diagram.id)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, updatedRels)
      setEditingRelation(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full && acceptServerRevision(diagram.id, full.diagram.revision)) {
          setDiagram(full.diagram)
          setElementsRaw(full.elements)
          setRelationsRaw(full.relations)
          saveServerDiagramSnapshot(diagram.id, full)
        }
        setEditingRelation(null)
        setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
        return
      }
      setRelationsRaw(nextRels)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'UPDATE_RELATION',
          payload
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setEditingRelation(null)
      setSavingStatus(pendingOperationStatus(err))
    }
  }

  // Eliminar relación (con soporte Offline CU10)
  async function handleDeleteRelation() {
    if (!editingRelation || !diagram) return
    const nextRels = relationsRaw.filter((r) => r.id !== editingRelation.id)

    if (!navigator.onLine || getOfflineQueue(diagram.id).length > 0) {
      setRelationsRaw(nextRels)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'DELETE_RELATION',
          payload: { relationId: editingRelation.id }
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setEditingRelation(null)
      setSavingStatus('Guardado localmente (Offline)')
      return
    }

    try {
      setSavingStatus('Eliminando relación...')
      const headers: Record<string, string> =
        diagram.revision != null ? { 'X-Diagram-Revision': String(diagram.revision) } : {}
      await request(
        `/api/projects/${project.id}/diagrams/${diagram.id}/relations/${editingRelation.id}`,
        auth.token,
        {
          method: 'DELETE',
          headers
        }
      )
      setRelationsRaw(nextRels)
      applyConfirmedDiagramRevision(diagram.id)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      setEditingRelation(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full && acceptServerRevision(diagram.id, full.diagram.revision)) {
          setDiagram(full.diagram)
          setElementsRaw(full.elements)
          setRelationsRaw(full.relations)
          saveServerDiagramSnapshot(diagram.id, full)
        }
        setEditingRelation(null)
        setSavingStatus('Conflicto de concurrencia: el diagrama cambió en el servidor (HTTP 409).')
        return
      }
      setRelationsRaw(nextRels)
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      const count = appendOfflineQueue(
        diagram.id,
        {
          type: 'DELETE_RELATION',
          payload: { relationId: editingRelation.id }
        },
        diagram.revision
      )
      setPendingQueueCount(count)
      setEditingRelation(null)
      setSavingStatus(pendingOperationStatus(err))
    }
  }

  // Nombres para los modales de relaciones
  const sourceName = useMemo(() => {
    const sourceId = pendingConnection?.source ?? editingRelation?.elementoOrigen
    return elementsRaw.find((e) => e.id === sourceId)?.nombre ?? 'Elemento'
  }, [pendingConnection, editingRelation, elementsRaw])

  const targetName = useMemo(() => {
    const targetId = pendingConnection?.target ?? editingRelation?.elementoDestino
    return elementsRaw.find((e) => e.id === targetId)?.nombre ?? 'Elemento'
  }, [pendingConnection, editingRelation, elementsRaw])

  async function handleValidateDiagram() {
    if (!diagram) return
    try {
      setValidating(true)
      const report = await request<ValidationReport>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/validate`,
        auth.token
      )
      setValidationReport(report)
    } catch (err) {
      onError(err)
    } finally {
      setValidating(false)
    }
  }

  async function handleGenerateRelational() {
    if (!diagram) return
    try {
      setGeneratingRelational(true)
      const schema = await request<RelationalSchema>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/relational`,
        auth.token
      )
      setRelationalSchema(schema)
    } catch (err) {
      onError(err)
    } finally {
      setGeneratingRelational(false)
    }
  }

  // A render-only projection: canonical nodes/raw entities and offline snapshots stay untouched.
  const previewGraph = useMemo(() => {
    if (!canvasProposal.active || loadedBaseDiagramId !== diagram?.id
      || elementsRaw.some(el => !nodes.some(node => node.id === el.id))
      || relationsRaw.some(rel => !edges.some(edge => edge.id === rel.id))) return { nodes, edges }
    const projected = projectCanvasProposal(elementsRaw, relationsRaw, canvasProposal.active, canvasProposal.active.scope === 'PRIVATE')
    return overlayCanvasGraph(nodes, edges,
      buildNodes(projected.elements.filter(el => projected.marks[el.id]), nodes).map(node => ({ ...node, data: { ...node.data, readOnly: true, previewKind: projected.marks[node.id].kind, previewMembers: projected.marks[node.id].members, previewFields: projected.marks[node.id].fields } })),
      buildEdges(projected.relations, projected.elements).filter(edge => projected.relationMarks[edge.id]).map(edge => greenPreviewEdge(edge, projected.relationMarks[edge.id])))
  }, [nodes, edges, elementsRaw, relationsRaw, canvasProposal.active, buildNodes, buildEdges, loadedBaseDiagramId, diagram?.id])

  const displayedGraph = useMemo(() => {
    if (!historicalView) return previewGraph
    const snapshot = historicalView.snapshot
    const viewNodes = buildNodes(snapshot.elements, nodes).map(node => ({ ...node, selected: false, data: { ...node.data, readOnly: true } }))
    const viewEdges = buildEdges(snapshot.relations, snapshot.elements)
    if (!historicalView.changes) return { nodes: viewNodes, edges: viewEdges }
    const changedClassIds = new Set(historicalView.changes.filter(change => change.entity !== 'RELACIÓN').map(change => change.id.split(':')[0]))
    const changedRelationIds = new Set(historicalView.changes.filter(change => change.entity === 'RELACIÓN').map(change => change.id))
    const removedClasses = (historicalView.before?.elements || []).filter(item => !snapshot.elements.some(current => current.id === item.id))
    const ghostNodes = buildNodes(removedClasses, nodes).map(node => ({ ...node, selected: false, className: 'version-removed-node', style: { border: '2px dashed #16a34a' }, data: { ...node.data, name: `${node.data.name} (eliminada)`, readOnly: true } }))
    const removedRelations = (historicalView.before?.relations || []).filter(item => !snapshot.relations.some(current => current.id === item.id))
    const ghostEdges = buildEdges(removedRelations, historicalView.before?.elements || []).map(edge => ({ ...edge, id: `removed-${edge.id}`, label: `${edge.label || ''} (eliminada)`, style: { ...edge.style, stroke: '#16a34a', strokeWidth: 3, strokeDasharray: '5 4' } }))
    return {
      nodes: [...viewNodes.map(node => changedClassIds.has(node.id) ? { ...node, className: 'version-changed-node' } : node), ...ghostNodes],
      edges: [...viewEdges.map(edge => changedRelationIds.has(edge.id) ? { ...edge, style: { ...edge.style, stroke: '#16a34a', strokeWidth: 3 } } : edge), ...ghostEdges]
    }
  }, [historicalView, previewGraph, buildNodes, buildEdges, nodes])

  useEffect(() => {
    if (!historicalView || displayedGraph.nodes.length === 0) return
    const timer = window.setTimeout(() => {
      void flowInstance.current?.fitView({
        nodes: displayedGraph.nodes.map(node => ({ id: node.id })),
        padding: 0.25,
        maxZoom: 1.15,
        duration: 180
      })
    }, 120)
    return () => window.clearTimeout(timer)
  }, [historicalView?.version.id, historicalView?.beforeVersion?.id])

  const handleFlowNodesChange = useCallback((changes: NodeChange<UmlNodeType>[]) => {
    if (!historicalView) {
      onNodesChange(changes)
      if (changes.some(change => change.type === 'position' && change.dragging)) {
        setEdges(current => current.some(edge => edge.type === 'umlRouted')
          ? current.map(edge => edge.type === 'umlRouted' ? { ...edge, type: edge.data?.tipoRelacion === 'AGREGACION' || edge.data?.tipoRelacion === 'COMPOSICION' ? 'umlOrthogonal' : 'step', data: { ...(edge.data || {}), routePoints: null, routeLabelPoint: null } } : edge)
          : current)
      }
      return
    }
    // En modo histórico solo aceptamos mediciones de React Flow para renderizar nodos y aristas.
    const dimensions = changes.filter(change => change.type === 'dimensions')
    if (dimensions.length) {
      setHistoricalNodeMeasurements(current => {
        const next = { ...current }
        for (const change of dimensions) {
          if (change.type === 'dimensions' && change.dimensions) next[change.id] = change.dimensions
        }
        return next
      })
      onNodesChange(dimensions)
    }
  }, [historicalView, onNodesChange, setEdges])

  async function returnToCurrentVersion() {
    if (!diagram) return
    try {
      const full = await request<DiagramFullView>(`/api/projects/${project.id}/diagrams/${diagram.id}`, auth.token)
      handleFullDiagramUpdated(full)
      fitUpdatedDiagram()
      setSavingStatus('Versión actual cargada')
    } catch (err) { onError(err) }
  }

  async function navigateHistory(offset: -1 | 1) {
    if (!historicalView || !diagram) return
    try {
      const number = historicalView.version.numeroVersion + offset
      if (number < 1) return
      const detail = await request<VersionSummary & { snapshotJson: string }>(`/api/projects/${project.id}/diagrams/${diagram.id}/versions/at/${number}`, auth.token)
      const snapshot = JSON.parse(detail.snapshotJson) as DiagramFullView
      setHistoricalNodeMeasurements({})
      setHistoricalView(previous => previous?.before && previous.beforeVersion
        ? { snapshot, version: detail, before: previous.before, beforeVersion: previous.beforeVersion, changes: compareVersions(previous.before, snapshot) }
        : { snapshot, version: detail })
    } catch (err) { if (!(err instanceof ApiRequestError && err.status === 404)) onError(err) }
  }

  const selectedCount = useMemo(() => nodes.filter((n) => n.selected).length, [nodes])
  function focusProposalChanges() {
    const ids = affectedPreviewNodeIds(canvasProposal.active, previewGraph.nodes, previewGraph.edges)
    if (ids.length) void flowInstance.current?.fitView({ nodes: ids.map(id => ({ id })), padding: 0.4, maxZoom: 1.2, duration: 350 })
  }
  const handleArrangeDiagram = useCallback(async () => {
    if (isReadOnly || diagram?.bloqueado || historicalView || canvasProposal.active || arrangingDiagram || !diagram) return
    const liveNodes = flowInstance.current?.getNodes() ?? nodesRef.current
    if (liveNodes.length < 2) return
    const layoutNodes = liveNodes.map(node => ({
      id: node.id,
      x: node.position.x,
      y: node.position.y,
      width: node.measured?.width ?? node.width ?? node.initialWidth ?? 240,
      height: node.measured?.height ?? node.height ?? node.initialHeight ?? estimateUmlNodeHeight(node.data.propertiesJson)
    }))
    const layoutEdges = edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target, label: typeof edge.label === 'string' ? edge.label : '', type: (edge.data as { tipoRelacion?: string } | undefined)?.tipoRelacion }))
    const initialLayout = layoutUmlGraph(layoutNodes, layoutEdges)
    const rotatedCandidates = [0, 1, 2, 3].map(turn => {
      const transformed = initialLayout.map(position => {
        const node = layoutNodes.find(candidate => candidate.id === position.id)!
        const cx = position.x + node.width / 2, cy = position.y + node.height / 2
        const point = turn === 0 ? { x: cx, y: cy } : turn === 1 ? { x: -cy, y: cx } : turn === 2 ? { x: -cx, y: -cy } : { x: cy, y: -cx }
        return { id: position.id, x: point.x - node.width / 2, y: point.y - node.height / 2 }
      })
      const minX = Math.min(...transformed.map(position => position.x)), minY = Math.min(...transformed.map(position => position.y))
      return transformed.map(position => ({ ...position, x: position.x - minX + 80, y: position.y - minY + 80 }))
    })
    const arranged = scoreUmlCandidates(layoutNodes, layoutEdges, rotatedCandidates)[0]?.positions ?? initialLayout
    const positions = new Map(arranged.map(position => [position.id, { x: position.x, y: position.y }]))
    const nextNodes = liveNodes.map(node => {
      const position = positions.get(node.id)
      return position ? { ...node, position } : node
    })
    const movedNodes = nextNodes.filter(node => {
      const previous = liveNodes.find(current => current.id === node.id)
      return previous && (Math.abs(previous.position.x - node.position.x) > 0.5 || Math.abs(previous.position.y - node.position.y) > 0.5)
    })
    if (!movedNodes.length) return

    const routeNodes = nextNodes.map(node => ({ id: node.id, x: node.position.x, y: node.position.y, width: node.measured?.width ?? node.width ?? 240, height: node.measured?.height ?? node.height ?? 120 }))
    const routeResults = routeUmlEdges(routeNodes, edges.map(edge => ({ id: edge.id, source: edge.source, target: edge.target, label: typeof edge.label === 'string' ? edge.label : '' })))
    const routeById = new Map(routeResults.map(route => [route.id, route.points]))
    // Exact same orthogonal paths are used in the rendered edge, rather than center-line estimates.
    const routedMetrics = measureRoutedLayout(routeNodes, routeResults)
    if (routedMetrics.nodeOverlaps > 0 || routedMetrics.edgeThroughNodes > 0) console.warn('CU22 routing metrics', routedMetrics)

    setArrangingDiagram(true)
    setSavingStatus('Organizando diagrama...')
    setNodes(current => current.map(node => {
      const position = positions.get(node.id)
      return position ? { ...node, position } : node
    }))
    try {
      await handleNodeDragStop(null, movedNodes[0], movedNodes)
      setEdges(current => current.map(edge => {
        const route = routeResults.find(item => item.id === edge.id)
        return { ...edge, type: 'umlRouted', data: { ...(edge.data || {}), routePoints: routeById.get(edge.id) ?? null, routeLabelPoint: route?.labelPoint ?? null } }
      }))
      window.setTimeout(() => {
        void flowInstance.current?.fitView({ nodes: nextNodes.map(node => ({ id: node.id })), padding: 0.24, maxZoom: 1.05, duration: 320 })
      }, 120)
    } catch (error) {
      onError(error)
    } finally {
      setArrangingDiagram(false)
    }
  }, [isReadOnly, historicalView, canvasProposal.active, arrangingDiagram, diagram, edges, handleNodeDragStop, setNodes, setEdges, onError])
  const selectedNodeId = nodes.find((node) => node.selected)?.id
  const selectedEdgeId = edges.find((edge) => edge.selected)?.id
  const selectedElement = selectedNodeId ? elementsRaw.find((element) => element.id === selectedNodeId) ?? null : null
  const selectedRelation = selectedEdgeId ? relationsRaw.find((relation) => relation.id === selectedEdgeId) ?? null : null
  const openImageImport = (file: File) => {
    setImageImportFile(file)
    setShowAi(true)
  }

  async function handleCreateIntermediateClass(relation: RelationDetail) {
    if (isReadOnly || !diagram) return
    const name = window.prompt('Nombre de la clase intermedia (por ejemplo, Detalle_Venta):')?.trim()
    if (!name) return
    const rawAttributes = window.prompt('Atributos opcionales separados por coma (ejemplo: cantidad: Integer, precio: Double):', '') ?? ''
    const attributes = rawAttributes.split(',').map(value => value.trim()).filter(Boolean).map(value => {
      const [attributeName, ...typeParts] = value.split(':')
      return { nombre: attributeName.trim(), tipo: typeParts.join(':').trim() || 'String' }
    }).filter(attribute => attribute.nombre)
    try {
      setSavingStatus('Creando clase intermedia...')
      const full = await request<DiagramFullView>(
        `/api/projects/${project.id}/diagrams/${diagram.id}/relations/${relation.id}/intermediate-class`,
        auth.token,
        {
          method: 'POST',
          headers: diagram.revision != null ? { 'X-Diagram-Revision': String(diagram.revision) } : {},
          body: JSON.stringify({ nombre: name, atributos: attributes })
        }
      )
      handleFullDiagramUpdated(full)
      setSavingStatus('Clase intermedia creada')
    } catch (err) {
      setSavingStatus(err instanceof Error ? err.message : 'No se pudo crear la clase intermedia.')
      onError(err)
    }
  }

  async function handleCreateBackendDirect(): Promise<void> {
    if (!diagram) throw new Error('El diagrama todavía no está disponible.')
    const generatedName = project.name.toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'app-generada'
    const response = await fetch(`/api/projects/${project.id}/diagrams/${diagram.id}/generate/zip`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.token}` },
      body: JSON.stringify({
        packageName: 'bo.edu.generated',
        projectName: generatedName,
        serverPort: 8081,
        databaseName: 'app_db',
        includeFrontend: false,
        includeFlutter: false
      })
    })
    if (!response.ok) {
      const body = await response.json().catch(() => ({})) as { message?: string }
      const error = new Error(body.message || 'No se pudo crear el backend.')
      onError(error)
      throw error
    }
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${generatedName}.zip`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  }

  return (
    <>
    <WorkspaceChrome
      projectId={project.id} token={auth.token}
      comparisonVersionIds={comparisonVersionIds}
      projectName={project.name} role={project.role} diagram={diagram} elements={elementsRaw} relations={relationsRaw}
      selectedElement={selectedElement} selectedRelation={selectedRelation} members={members} savingStatus={savingStatus}
      currentUser={{ name: auth.name, email: auth.email, role: project.role }}
      online={isOnline} syncing={isSyncingQueue} wsConnected={wsConnected} queueCount={pendingQueueCount} readOnly={isReadOnly}
      onBack={onBack} onManageMembers={onManageMembers} onCreate={handleAddElement} onEditElement={handleEditElement}
      onImageUpload={openImageImport}
      onEditRelation={setEditingRelation} onRelationType={setPendingRelationType} onValidate={handleValidateDiagram} onRelational={handleGenerateRelational}
      onAi={(mode) => { setAiInitialMode(mode || 'command'); setShowAi(true) }}
      onGenerator={handleCreateBackendDirect}
      onFlutterGenerator={() => { setGeneratorWithFlutter(true); setShowGenerator(true) }}
      onVersions={() => setShowVersions(true)}
      onActivity={() => setShowActivity(true)}
      commentMode={commentMode}
      onComments={() => setCommentMode(value => !value)}
      selectedCommentId={selectedCommentId}
      onEditSelectedComment={() => {
        const comment = canvasComments.find(item => item.id === selectedCommentId)
        if (comment) { setEditingCommentId(comment.id); setEditingCommentText(comment.content) }
      }}
      onDeleteSelectedComment={() => { if (selectedCommentId) void deleteCanvasComment(selectedCommentId) }}
      onExchange={() => setShowXmi(true)} onApiTester={() => setShowApiTester(true)} onDeleteSelected={handleDeleteSelected}
      onCreateIntermediateClass={handleCreateIntermediateClass}
      onArrangeDiagram={() => void handleArrangeDiagram()} arrangingDiagram={arrangingDiagram} arrangeDisabled={Boolean(historicalView || canvasProposal.active)}
      onRetryPendingSync={() => void handleRetryPendingSync()}
      selectedCount={selectedCount} onSelectAll={() => setNodes(prev => prev.map(node => ({ ...node, selected: true })))}
      onDeselectAll={() => setNodes(prev => prev.map(node => ({ ...node, selected: false })))}
    >
      {/* Barra de herramientas superior */}
      <div className="uml-toolbar legacy-toolbar">
        <div className="uml-toolbar-left">
          <span className="uml-diagram-title">{diagram?.nombre ?? 'Cargando diagrama...'}</span>
          <span
            className={`status-pill ${
              !isOnline ? 'offline' : isSyncingQueue ? 'syncing' : savingStatus.includes('Error') ? 'error' : 'online'
            }`}
            title={
              !isOnline
                ? 'Modo Offline: Sin conexión a internet. Los cambios se guardan localmente y se sincronizarán al reconectar.'
                : isSyncingQueue
                ? 'Sincronizando operaciones pendientes con el servidor...'
                : 'Conexión activa con el servidor'
            }
          >
            {!isOnline
              ? `● Modo Offline${pendingQueueCount > 0 ? ` (${pendingQueueCount} pendientes)` : ''}`
              : isSyncingQueue
              ? `● Sincronizando (${pendingQueueCount})...`
              : '● En línea'}
          </span>
          <span className={`status-pill ${savingStatus.includes('Error') ? 'error' : ''}`}>
            {savingStatus}
          </span>
          {isOnline && (
            <span
              className={`collab-pill ${wsConnected ? 'connected' : 'syncing'}`}
              title="Sincronización colaborativa en tiempo real (< 2s)"
            >
              {wsConnected ? '● En vivo' : '● Sincronizando'}
            </span>
          )}
          {isReadOnly && <span className="readonly-badge">Modo Lectura</span>}
        </div>

        <div className="uml-toolbar-actions">
          {/* Botones de Modelado e Ingeniería (CU05 y CU06) */}
          <button
            type="button"
            className="uml-tool-btn feature-btn"
            disabled={validating}
            onClick={handleValidateDiagram}
            title="Validar consistencia y reglas semánticas del modelo UML"
          >
            {validating ? 'Validando...' : 'Validar Modelo'}
          </button>
          <button
            type="button"
            className="uml-tool-btn feature-btn primary"
            disabled={generatingRelational}
            onClick={handleGenerateRelational}
            title="Transformar diagrama a tablas SQL y PostgreSQL DDL (CU06 / CU11)"
          >
            {generatingRelational ? 'Generando...' : 'Relacional & SQL'}
          </button>

          <button
            type="button"
            className="uml-tool-btn feature-btn"
            style={{ background: '#7c3aed', color: 'white', display: 'inline-flex', alignItems: 'center', gap: '5px' }}
            onClick={() => setShowAi(true)}
            title="Asistente de IA local para modelado por voz y comandos"
          >
            <SparklesIcon size={14} color="white" /> IA & Voz
          </button>

          <button
            type="button"
            className="uml-tool-btn feature-btn"
            style={{ background: '#16a34a', color: 'white', display: 'inline-flex', alignItems: 'center', gap: '5px' }}
            onClick={() => void handleCreateBackendDirect()}
            title="Crear Backend Spring Boot con configuración predeterminada"
          >
            <CodeIcon size={14} color="white" /> Generar Backend
          </button>

          <button
            type="button"
            className="uml-tool-btn feature-btn"
            onClick={() => setShowVersions(true)}
            title="Historial de versiones y snapshots del diagrama"
          >
            Versiones
          </button>

          <button
            type="button"
            className="uml-tool-btn feature-btn"
            style={{ display: 'inline-flex', alignItems: 'center', gap: '5px' }}
            onClick={() => setShowXmi(true)}
            title="Exportar o importar modelos en XMI o JSON con cardinalidades (CU13)"
          >
            <ExchangeIcon size={14} /> Exportar / Importar
          </button>

          <button
            type="button"
            className="uml-tool-btn feature-btn"
            onClick={() => setShowApiTester(true)}
            title="Probar endpoints de la API generada por REST o voz"
          >
            Probar API
          </button>

          {/* Controles de Selección Múltiple (Requerimientos 2 y 13) */}
          {selectedCount > 0 ? (
            <div className="selection-toolbar-group">
              <span className="selection-count-badge">
                🎯 {selectedCount} {selectedCount === 1 ? 'seleccionada' : 'seleccionadas'}
              </span>
              {!isReadOnly && (
                <button
                  type="button"
                  className="uml-tool-btn danger"
                  onClick={handleDeleteSelected}
                  title="Eliminar clases seleccionadas (Supr / Delete)"
                >
                  🗑️ Eliminar ({selectedCount})
                </button>
              )}
              <button
                type="button"
                className="uml-tool-btn secondary"
                onClick={() => setNodes((prev) => prev.map((n) => ({ ...n, selected: false })))}
                title="Deseleccionar todo (Escape)"
              >
                Deseleccionar todo
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="uml-tool-btn secondary"
              onClick={() => setNodes((prev) => prev.map((n) => ({ ...n, selected: true })))}
              title="Seleccionar todas las clases dentro del lienzo (Ctrl + A)"
            >
              Seleccionar todo (Ctrl+A)
            </button>
          )}

          {!isReadOnly && (
            <>
              <span className="toolbar-divider" />
              <button
                type="button"
                className="uml-tool-btn"
                onClick={() => handleAddElement('CLASE')}
                title="Crear una nueva clase vacía en el lienzo"
              >
                + Nueva Clase
              </button>
              <button
                type="button"
                className="uml-tool-btn secondary"
                onClick={() => handleAddElement('INTERFAZ')}
                title="Crear una nueva interfaz UML"
              >
                + Interfaz
              </button>
              <button
                type="button"
                className="uml-tool-btn secondary"
                onClick={() => handleAddElement('CLASE_ABSTRACTA')}
                title="Crear una nueva clase abstracta"
              >
                + Abstracta
              </button>
              <button
                type="button"
                className="uml-tool-btn secondary"
                onClick={() => handleAddElement('ENUMERACION')}
                title="Crear una nueva enumeración UML"
              >
                + Enumeración
              </button>
            </>
          )}
        </div>
      </div>

      {/* Contenedor React Flow con Selección Múltiple y Expansión Manual del Lienzo */}
      <div
        ref={viewportRef}
        className={`uml-canvas-viewport ${isResizingCanvas ? 'is-resizing-canvas' : ''} ${canvasProposal.active ? 'acodi-preview-active' : ''} ${historicalView ? 'is-historical-view' : ''}`}
        onClickCapture={commentMode && !historicalView && !canvasProposal.active ? openCanvasComment : undefined}
        style={
          canvasCustomSize.width && canvasCustomSize.height
            ? {
                width: `${canvasCustomSize.width}px`,
                height: `${canvasCustomSize.height}px`,
                minWidth: '100%',
                minHeight: '100%',
                flex: '0 0 auto'
              }
            : {
                width: '100%',
                height: '100%',
                minWidth: 0,
                minHeight: 0,
                flex: '1 1 0%'
              }
        }
      >
        {historicalView && <div className="version-history-banner" role="status">
          <strong>{historicalView.changes ? 'Comparación histórica' : 'Visualizando versión histórica'} · v{historicalView.version.numeroVersion}</strong>
          {historicalView.changes && <span>{historicalView.changes.length} cambio(s) entre v{historicalView.beforeVersion?.numeroVersion} y v{historicalView.version.numeroVersion}</span>}
          {historicalView.changes && <details><summary>Ver diferencias</summary><ul>{historicalView.changes.map((change, index) => <li key={`${change.entity}-${change.id}-${index}`}>{change.action}: {change.entity} — {change.label}</li>)}</ul></details>}
          <button type="button" onClick={() => void navigateHistory(-1)}>Anterior</button>
          <button type="button" onClick={() => void navigateHistory(1)}>Siguiente</button>
          <button type="button" onClick={() => void returnToCurrentVersion()}>Volver a la actual</button>
        </div>}
        {!historicalView && <CanvasProposalBar key={canvasProposal.active?.id || 'none'} active={canvasProposal.active} pending={canvasProposal.pending}
          editable={!isReadOnly && isOnline && pendingQueueCount === 0} busy={canvasProposal.busy} error={canvasProposal.error}
          stale={Boolean(canvasProposal.active && (canvasProposal.active.status === 'CONFLICTO' || canvasProposal.active.baseRevision !== diagram?.revision))}
          onSelect={canvasProposal.select} onApprove={canvasProposal.approve} onReject={canvasProposal.reject} onRegenerate={canvasProposal.regenerate} onFocus={focusProposalChanges} />}
        <ReactFlow
          onInit={instance => { flowInstance.current = instance }}
          nodes={displayedGraph.nodes}
          edges={displayedGraph.edges}
          onNodesChange={canvasProposal.active && !historicalView ? undefined : handleFlowNodesChange}
          onEdgesChange={historicalView || canvasProposal.active ? undefined : onEdgesChange}
          onConnect={historicalView || canvasProposal.active ? undefined : handleConnect}
          onReconnect={historicalView || canvasProposal.active ? undefined : handleReconnect}
          onNodeDragStop={historicalView || canvasProposal.active ? undefined : handleNodeDragStop}
          onNodeClick={historicalView || canvasProposal.active || commentMode ? undefined : handleNodeClick}
          onEdgeClick={historicalView || canvasProposal.active || commentMode ? undefined : handleEdgeClick}
          onPaneClick={undefined}
          nodeTypes={NODE_TYPES}
          edgeTypes={EDGE_TYPES}
          nodesDraggable={!isReadOnly && !canvasProposal.active}
          nodesConnectable={!isReadOnly && !historicalView && !canvasProposal.active}
          edgesReconnectable={!isReadOnly && !historicalView && !canvasProposal.active}
          deleteKeyCode={historicalView || canvasProposal.active ? null : 'Backspace'}
          elementsSelectable={!historicalView}
          selectionOnDrag={true}
          selectionMode={SelectionMode.Partial}
          panOnDrag={[1, 2]}
          multiSelectionKeyCode={['Control', 'Meta']}
          fitView
        >
          <ViewportPortal>
            {draftComment && (
              <div
                style={{ position: 'absolute', left: draftComment.x, top: draftComment.y, transform: 'translate(-8px, -8px)', width: 180, minHeight: 90, padding: '12px 12px 10px', border: '2px solid #111827', background: '#ffffff', color: '#172033', zIndex: 30, boxSizing: 'border-box', pointerEvents: 'auto' }}
                onPointerDown={event => event.stopPropagation()}
              >
                <span aria-hidden="true" style={{ position: 'absolute', right: -2, top: -2, width: 17, height: 17, background: '#ffffff', borderLeft: '2px solid #111827', borderBottom: '2px solid #111827', clipPath: 'polygon(0 0, 100% 0, 100% 100%)' }} />
                <textarea autoFocus value={draftComment.content} onChange={event => setDraftComment(current => current ? { ...current, content: event.target.value } : current)} onPointerDown={event => event.stopPropagation()} placeholder="Escribe aquí el comentario" rows={3} maxLength={2000} style={{ display: 'block', width: '100%', minHeight: 52, boxSizing: 'border-box', resize: 'vertical', border: 0, outline: 0, color: '#172033', background: 'transparent', font: '14px Arial, sans-serif' }} />
                <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                  <button type="button" onPointerDown={event => event.stopPropagation()} onClick={event => { event.stopPropagation(); void saveDraftComment() }} disabled={!draftComment.content.trim()} style={{ padding: '5px 9px', fontSize: 12, lineHeight: 1.2, borderRadius: 4 }}>Guardar</button>
                  <button type="button" onPointerDown={event => event.stopPropagation()} onClick={event => { event.stopPropagation(); setDraftComment(null) }} style={{ padding: '5px 9px', fontSize: 12, lineHeight: 1.2, borderRadius: 4 }}>Cancelar</button>
                </div>
              </div>
            )}
            {canvasComments.map(comment => (
              <div
                key={comment.id}
                onPointerDown={event => {
                  event.stopPropagation()
                  if ((event.target as HTMLElement).closest('button, textarea')) return
                  const pointer = flowInstance.current?.screenToFlowPosition({ x: event.clientX, y: event.clientY })
                  if (!pointer) return
                  setSelectedCommentId(comment.id)
                  draggingComment.current = { id: comment.id, offsetX: pointer.x - (comment.posicionX ?? 0), offsetY: pointer.y - (comment.posicionY ?? 0) }
                  event.currentTarget.setPointerCapture(event.pointerId)
                }}
                onPointerMove={event => {
                  const resize = resizingComment.current
                  if (resize?.id === comment.id && flowInstance.current) {
                    const scale = flowInstance.current.getZoom() || 1
                    const width = Math.max(120, Math.min(600, Math.round(resize.width + (event.clientX - resize.pointerX) / scale)))
                    const height = Math.max(70, Math.min(600, Math.round(resize.height + (event.clientY - resize.pointerY) / scale)))
                    setCanvasComments(comments => comments.map(item => item.id === comment.id ? { ...item, canvasWidth: width, canvasHeight: height } : item))
                    return
                  }
                  const drag = draggingComment.current
                  if (!drag || drag.id !== comment.id || !flowInstance.current) return
                  const position = flowInstance.current.screenToFlowPosition({ x: event.clientX, y: event.clientY })
                  const nextX = position.x - drag.offsetX
                  const nextY = position.y - drag.offsetY
                  setCanvasComments(comments => comments.map(item => item.id === comment.id ? { ...item, posicionX: nextX, posicionY: nextY } : item))
                }}
                onPointerUp={event => {
                  const resize = resizingComment.current
                  if (resize?.id === comment.id) {
                    resizingComment.current = null
                    const current = canvasComments.find(item => item.id === comment.id)
                    if (current) void resizeCanvasComment(comment.id, { width: current.canvasWidth, height: current.canvasHeight })
                    return
                  }
                  const drag = draggingComment.current
                  if (!drag || drag.id !== comment.id || !flowInstance.current) return
                  draggingComment.current = null
                  const position = flowInstance.current.screenToFlowPosition({ x: event.clientX, y: event.clientY })
                  void moveCanvasComment(comment.id, { x: position.x - drag.offsetX, y: position.y - drag.offsetY })
                }}
                onClick={event => {
                  event.stopPropagation()
                  setSelectedCommentId(comment.id)
                }}
                style={{
                  position: 'absolute',
                  left: comment.posicionX ?? 0,
                  top: comment.posicionY ?? 0,
                  transform: 'translate(-8px, -8px)',
                  width: comment.canvasWidth || 180,
                  height: comment.canvasHeight || 90,
                  padding: '12px 28px 12px 12px',
                  borderRadius: 0,
                  border: selectedCommentId === comment.id ? '2px solid #2563eb' : '2px solid #111827',
                  background: '#ffffff',
                  color: '#172033',
                  boxShadow: 'none',
                  cursor: 'grab',
                  fontSize: 13,
                  lineHeight: 1.35,
                  zIndex: 20,
                  userSelect: 'none',
                  pointerEvents: 'auto',
                  overflow: 'hidden',
                  overflowWrap: 'anywhere'
                }}
              >
                <span aria-hidden="true" style={{ position: 'absolute', right: -2, top: -2, width: 17, height: 17, background: '#ffffff', borderLeft: '2px solid #111827', borderBottom: '2px solid #111827', clipPath: 'polygon(0 0, 100% 0, 100% 100%)' }} />
                <strong style={{ display: 'block', marginBottom: 4 }}>{comment.authorName}</strong>
                {editingCommentId === comment.id ? <>
                  <textarea autoFocus value={editingCommentText} onChange={event => setEditingCommentText(event.target.value)} onPointerDown={event => event.stopPropagation()} onClick={event => event.stopPropagation()} rows={3} maxLength={2000} style={{ width: '100%', boxSizing: 'border-box', resize: 'vertical', border: '1px solid #111827', padding: 5 }} />
                  <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                    <button type="button" onPointerDown={event => event.stopPropagation()} onClick={event => { event.stopPropagation(); void updateCanvasComment(comment.id) }}>Guardar</button>
                    <button type="button" onPointerDown={event => event.stopPropagation()} onClick={event => { event.stopPropagation(); setEditingCommentId(null) }}>Cancelar</button>
                  </div>
                </> : <span>{comment.content}</span>}
                {selectedCommentId === comment.id && <div
                  className="uml-comment-resize-handle"
                  role="separator"
                  aria-label="Cambiar tamaño del comentario"
                  title="Arrastra para cambiar el tamaño"
                  onPointerDown={event => {
                    event.stopPropagation()
                    resizingComment.current = { id: comment.id, pointerX: event.clientX, pointerY: event.clientY, width: comment.canvasWidth || 180, height: comment.canvasHeight || 90 }
                    event.currentTarget.parentElement?.setPointerCapture(event.pointerId)
                  }}
                />}
              </div>
            ))}
          </ViewportPortal>
          <Background variant={BackgroundVariant.Dots} gap={16} size={1} color="#cbd5e1" />
          <Controls />
          {!historicalView && <MiniMap nodeStrokeWidth={3} zoomable pannable />}
        </ReactFlow>

        {/* Control de expansión manual del área de trabajo (Esquina inferior derecha) */}
        <div
          className={`canvas-resizer-control ${isResizingCanvas ? 'is-dragging' : ''}`}
          onMouseDown={handleMouseDownCanvasResizer}
          title="Arrastrar para ampliar manualmente el área del lienzo UML"
        >
          <span>
            {canvasCustomSize.width && canvasCustomSize.height
              ? `${canvasCustomSize.width} × ${canvasCustomSize.height} px`
              : 'Ampliar lienzo'}
          </span>
          <span className="resizer-icon">⇲</span>
          {canvasCustomSize.width !== null && (
            <button
              type="button"
              className="canvas-reset-size-btn"
              onClick={(e) => {
                e.stopPropagation()
                setCanvasCustomSize({ width: null, height: null })
              }}
              title="Restablecer tamaño automático al 100%"
            >
              ↺ Auto
            </button>
          )}
        </div>
      </div>
    </WorkspaceChrome>

      {/* Modal de edición detallada de clase */}
      {editingElement && (
        <ClassEditorModal
          initialName={editingElement.nombre}
          initialType={editingElement.tipoElemento}
          initialPropertiesJson={editingElement.propiedadesJson}
          onSave={handleSaveElementDetails}
          onClose={() => setEditingElement(null)}
        />
      )}

      {/* Modal para configurar nueva relación */}
      {pendingClassDeletion && (
        <ConfirmModal
          open={true}
          title={pendingClassDeletion.title}
          message={pendingClassDeletion.message}
          confirmLabel="Eliminar"
          cancelLabel="Cancelar"
          onCancel={() => setPendingClassDeletion(null)}
          onConfirm={() => {
            const deletion = pendingClassDeletion
            setPendingClassDeletion(null)
            if (deletion.operation === 'single') {
              void handleDeleteElement(deletion.elementIds[0])
            } else {
              void performDeleteSelected(deletion.elementIds)
            }
          }}
        />
      )}

      {pendingConnection && (
        <RelationModal
          initialType={pendingRelationType}
          sourceName={sourceName}
          targetName={targetName}
          onSave={handleSavePendingRelation}
          onClose={() => setPendingConnection(null)}
        />
      )}

      {/* Modal para modificar o borrar relación existente */}
      {editingRelation && (
        <RelationModal
          initialType={editingRelation.tipoRelacion}
          initialName={editingRelation.nombreRelacion}
          initialMultOrigin={editingRelation.multiplicidadOrigen}
          initialMultTarget={editingRelation.multiplicidadDestino}
          sourceName={sourceName}
          targetName={targetName}
          onSave={handleUpdateRelation}
          onDelete={handleDeleteRelation}
          onClose={() => setEditingRelation(null)}
        />
      )}

      {/* Modal de Validación UML (CU05) */}
      {validationReport && (
        <ValidationModal
          report={validationReport}
          onProceedToRelational={handleGenerateRelational}
          onClose={() => setValidationReport(null)}
        />
      )}

      {/* Modal de Modelo Relacional Generado (CU06 / CU11) */}
      {relationalSchema && diagram && (
        <RelationalModal
          schema={relationalSchema}
          projectName={project.name}
          projectId={project.id}
          diagramId={diagram.id}
          token={auth.token}
          revision={diagram.revision}
          isReadOnly={isReadOnly}
          onClose={() => setRelationalSchema(null)}
          onDiagramUpdated={handleFullDiagramUpdated}
          onError={onError}
        />
      )}

      {/* Modal Generador de Software (CU14, CU15, CU16) */}
      {showGenerator && diagram && (
        <CodeGeneratorModal
          projectName={project.name}
          diagramId={diagram.id}
          projectId={project.id}
          token={auth.token}
          initialFlutter={generatorWithFlutter}
          simplifiedBackend={!generatorWithFlutter}
          onClose={() => setShowGenerator(false)}
          onError={onError}
        />
      )}

      {/* Modal Asistente IA y Voz (CU10, CU11, CU12) */}
      {showAi && diagram && (
        <AiAssistantModal
          projectId={project.id}
          diagramId={diagram.id}
          token={auth.token}
          revision={diagram.revision}
          initialMode={imageImportFile ? 'image' : aiInitialMode}
          initialImageFile={imageImportFile}
          hasExistingContent={elementsRaw.length > 0 || relationsRaw.length > 0}
          selectedElementId={selectedElement?.id ?? null}
          selectedRelationId={selectedRelation?.id ?? null}
          onClose={() => { setShowAi(false); setImageImportFile(null) }}
          onDiagramUpdated={handleFullDiagramUpdated}
          onError={onError}
        />
      )}

      {/* Modal de Versiones (CU09) */}
      {showVersions && diagram && (
        <VersionHistoryModal
          projectId={project.id}
          diagramId={diagram.id}
          token={auth.token}
          revision={diagram.revision}
          isReadOnly={isReadOnly}
          onClose={() => setShowVersions(false)}
          onView={(snapshot, version) => { setHistoricalNodeMeasurements({}); setHistoricalView({ snapshot, version }); setShowVersions(false) }}
          onCompare={(before, beforeVersion, after, afterVersion) => {
            setComparisonVersionIds([beforeVersion.id, afterVersion.id])
            setHistoricalNodeMeasurements({})
            setHistoricalView({ snapshot: after, version: afterVersion, before, beforeVersion, changes: compareVersions(before, after) })
            setShowVersions(false)
          }}
          onRestored={handleRestoredDiagram}
          onError={onError}
        />
      )}

      {showActivity && diagram && (
        <ActivityLogModal
          projectId={project.id}
          diagramId={diagram.id}
          token={auth.token}
          onClose={() => setShowActivity(false)}
          onError={onError}
        />
      )}

      {/* Modal de Interoperabilidad XMI (CU13) */}
      {showXmi && diagram && (
        <XmiModal
          projectId={project.id}
          diagramId={diagram.id}
          diagramName={diagram.nombre}
          token={auth.token}
          revision={diagram.revision}
          isReadOnly={isReadOnly}
          isLocked={diagram.bloqueado}
          lockUser={diagram.bloqueadoPor}
          onClose={() => setShowXmi(false)}
          onDiagramUpdated={handleFullDiagramUpdated}
          onError={onError}
        />
      )}

      {/* Consola de Pruebas de API */}
      {showApiTester && (
        <ApiTesterModal
          elements={elementsRaw}
          onClose={() => setShowApiTester(false)}
        />
      )}
    </>
  )
}
