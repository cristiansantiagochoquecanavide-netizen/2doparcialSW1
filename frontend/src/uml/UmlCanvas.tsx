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
  BackgroundVariant,
  MarkerType,
  SelectionMode,
  Position,
  reconnectEdge,
  BaseEdge,
  StepEdge,
  EdgeLabelRenderer,
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
import { VersionsModal } from './VersionsModal'
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
  if (!handle) return `${fallbackPosition}-${type}-${fallbackSlot}`
  const match = /^(top|right|bottom|left)-(source|target)(?:-(0|1|2))?$/.exec(handle)
  if (!match || match[2] !== type) return `${fallbackPosition}-${type}-${fallbackSlot}`
  return `${match[1]}-${type}-${match[3] ?? '0'}`
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

type UmlEdgeData = { tipoRelacion?: TipoRelacionUML }

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

const EDGE_TYPES = { umlReflexive: ReflexiveOrthogonalEdge, umlOrthogonal: UmlOrthogonalEdge }

interface ApiResponse<T> {
  data: T
  revision: number | null
}

async function requestFull<T>(path: string, token: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  const url = base ? `${base}${path}` : path
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...options.headers
    }
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiRequestError(response.status, body.message ?? 'Error al comunicar con el servidor.')
  }
  const revHeader = response.headers.get('X-Diagram-Revision')
  const revision = revHeader ? parseInt(revHeader, 10) : null
  const data = response.status === 204 ? (undefined as T) : ((await response.json()) as T)
  return { data, revision }
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
  expectedRevision?: number | null
): number {
  const queue = getOfflineQueue(diagramId)
  queue.push({
    ...action,
    id: crypto.randomUUID(),
    timestamp: Date.now(),
    expectedRevision: expectedRevision !== undefined ? expectedRevision : ((action as any).expectedRevision ?? null)
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

export function UmlCanvas({ project, auth, onError, members = [], onManageMembers, onBack }: Props) {
  const isReadOnly = project.role === 'LECTOR'

  const [diagram, setDiagram] = useState<DiagramSummary | null>(null)
  const [elementsRaw, setElementsRaw] = useState<ElementDetail[]>([])
  const [relationsRaw, setRelationsRaw] = useState<RelationDetail[]>([])
  const [loadedBaseDiagramId, setLoadedBaseDiagramId] = useState('')

  const [nodes, setNodes, onNodesChange] = useNodesState<UmlNodeType>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const flowInstance = useRef<ReactFlowInstance<UmlNodeType, Edge> | null>(null)

  const [savingStatus, setSavingStatus] = useState<string>('Guardado')
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
  const [showAi, setShowAi] = useState(false)
  const [aiInitialMode, setAiInitialMode] = useState<'command' | 'image' | 'chat'>('command')
  const [imageImportFile, setImageImportFile] = useState<File | null>(null)
  const [showVersions, setShowVersions] = useState(false)
  const [showXmi, setShowXmi] = useState(false)
  const [showApiTester, setShowApiTester] = useState(false)

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
        if (currentDiagram.revision != null) {
          setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
        }
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
        setSavingStatus('Guardado')
      } catch (err) {
        if (err instanceof ApiRequestError && err.status === 409) {
          const full = await request<DiagramFullView>(
            `/api/projects/${project.id}/diagrams/${currentDiagram.id}`,
            auth.token
          ).catch(() => null)
          if (full) {
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
    [isReadOnly, project.id, auth.token]
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
      for (const elementId of selectedIds) {
        await request(
          `/api/projects/${project.id}/diagrams/${currentDiagram.id}/elements/${elementId}`,
          auth.token,
          { method: 'DELETE', headers }
        )
      }
      setElementsRaw(nextElements)
      setRelationsRaw(nextRelations)
      if (currentDiagram.revision != null) {
        setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
      }
      saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, nextRelations)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${currentDiagram.id}`,
          auth.token
        ).catch(() => null)
        if (full) {
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
  }, [isReadOnly, project.id, auth.token])

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
      const selectedMap = new Map((previousNodes ?? nodesRef.current ?? []).map((n) => [n.id, !!n.selected]))
      return elements.map((el) => ({
        id: el.id,
        type: 'umlClass',
        position: { x: el.posicionX, y: el.posicionY },
        selected: selectedMap.get(el.id) ?? false,
        data: {
          elementId: el.id,
          name: el.nombre,
          elementType: el.tipoElemento,
          propertiesJson: el.propiedadesJson,
          readOnly: isReadOnly,
          onEdit: handleEditElement,
          onDelete: requestDeleteElement
        }
      }))
    },
    [isReadOnly, handleEditElement, requestDeleteElement]
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
      setLoadedBaseDiagramId(full.diagram.id)
      setDiagram(full.diagram)
      setElementsRaw(full.elements)
      setRelationsRaw(full.relations)
      saveServerDiagramSnapshot(full.diagram.id, full)
      setNodes(buildNodes(full.elements))
      setEdges(buildEdges(full.relations, full.elements))
    },
    [buildNodes, buildEdges, setNodes, setEdges]
  )

  // Sincronizar cola de operaciones pendientes al reconectar a internet (CU10)
  const flushPendingQueue = useCallback(
    async (diagramId: string) => {
      const queue = getOfflineQueue(diagramId)
      if (queue.length === 0) return

      setIsSyncingQueue(true)
      setSavingStatus('Sincronizando...')

      try {
        const serverBefore = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagramId}`,
          auth.token
        )
        const baseline = loadServerDiagramSnapshot(diagramId)
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
          setPendingQueueCount(getOfflineQueue(diagramId).length)
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
            if (full) {
              saveServerDiagramSnapshot(diagramId, full)
              setDiagram(full.diagram)
              setElementsRaw(full.elements)
              setRelationsRaw(full.relations)
            }
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
        setIsSyncingQueue(false)
      }
    },
    [project.id, auth.token]
  )

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
        setDiagram(def)
        localStorage.setItem(`case.last_diagram.${project.id}`, def.id)

        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${def.id}`,
          auth.token
        )
        if (!isMounted) return
        setElementsRaw(full.elements)
        setRelationsRaw(full.relations)
        setLoadedBaseDiagramId(def.id)
        saveServerDiagramSnapshot(def.id, full)
        saveLocalDiagramSnapshot(def.id, def, full.elements, full.relations)
        setPendingQueueCount(getOfflineQueue(def.id).length)
        setSavingStatus('Guardado')

        if (navigator.onLine && getOfflineQueue(def.id).length > 0) {
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
  }, [project.id, auth.token])

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
  }, [diagram?.id, project.id, auth.token, isOnline])

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
        saveServerDiagramSnapshot(diagram.id, full)
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
  }, [diagram?.id, project.id, auth.token, isOnline])

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

      if (!navigator.onLine || getOfflineQueue(currentDiagram.id).length > 0) {
        setElementsRaw(nextElements)
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, relationsRaw)
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
        const headers: Record<string, string> =
          currentDiagram.revision != null ? { 'X-Diagram-Revision': String(currentDiagram.revision) } : {}
        await request(
          `/api/projects/${project.id}/diagrams/${currentDiagram.id}/elements/positions`,
          auth.token,
          {
            method: 'POST',
            headers,
            body: JSON.stringify({ positions, expectedRevision: currentDiagram.revision })
          }
        )
        setElementsRaw(nextElements)
        if (currentDiagram.revision != null) {
          setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
        }
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, relationsRaw)
        setSavingStatus('Guardado')
      } catch (err) {
        if (err instanceof ApiRequestError && err.status === 409) {
          const full = await request<DiagramFullView>(
            `/api/projects/${project.id}/diagrams/${currentDiagram.id}`,
            auth.token
          ).catch(() => null)
          if (full) {
            setDiagram(full.diagram)
            setElementsRaw(full.elements)
            setRelationsRaw(full.relations)
            saveServerDiagramSnapshot(currentDiagram.id, full)
          }
          setSavingStatus('Conflicto de concurrencia al mover posiciones (HTTP 409).')
          return
        }
        setElementsRaw(nextElements)
        saveLocalDiagramSnapshot(currentDiagram.id, currentDiagram, nextElements, relationsRaw)
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
    [isReadOnly, project.id, auth.token, relationsRaw]
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
      if (diagram.revision != null) {
        setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
      }
      saveLocalDiagramSnapshot(diagram.id, diagram, nextElements, relationsRaw)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full) {
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
      if (diagram.revision != null) {
        setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
      }
      saveLocalDiagramSnapshot(diagram.id, diagram, updatedElements, relationsRaw)
      setEditingElement(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full) {
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
      if (diagram.revision != null) {
        setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
      }
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      setPendingConnection(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full) {
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
    if (rel) setEditingRelation(rel)
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
      if (diagram.revision != null) {
        setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
      }
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, updatedRels)
      setEditingRelation(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full) {
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
      if (diagram.revision != null) {
        setDiagram((prev) => (prev ? { ...prev, revision: (prev.revision ?? 0) + 1 } : prev))
      }
      saveLocalDiagramSnapshot(diagram.id, diagram, elementsRaw, nextRels)
      setEditingRelation(null)
      setSavingStatus('Guardado')
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 409) {
        const full = await request<DiagramFullView>(
          `/api/projects/${project.id}/diagrams/${diagram.id}`,
          auth.token
        ).catch(() => null)
        if (full) {
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

  const selectedCount = useMemo(() => nodes.filter((n) => n.selected).length, [nodes])
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
  function focusProposalChanges() {
    const ids = affectedPreviewNodeIds(canvasProposal.active, previewGraph.nodes, previewGraph.edges)
    if (ids.length) void flowInstance.current?.fitView({ nodes: ids.map(id => ({ id })), padding: 0.4, maxZoom: 1.2, duration: 350 })
  }
  const selectedNodeId = nodes.find((node) => node.selected)?.id
  const selectedEdgeId = edges.find((edge) => edge.selected)?.id
  const selectedElement = selectedNodeId ? elementsRaw.find((element) => element.id === selectedNodeId) ?? null : null
  const selectedRelation = selectedEdgeId ? relationsRaw.find((relation) => relation.id === selectedEdgeId) ?? null : null
  const openImageImport = (file: File) => {
    setImageImportFile(file)
    setShowAi(true)
  }

  return (
    <>
    <WorkspaceChrome
      projectId={project.id} token={auth.token}
      projectName={project.name} role={project.role} diagram={diagram} elements={elementsRaw} relations={relationsRaw}
      selectedElement={selectedElement} selectedRelation={selectedRelation} members={members} savingStatus={savingStatus}
      currentUser={{ name: auth.name, email: auth.email, role: project.role }}
      online={isOnline} syncing={isSyncingQueue} wsConnected={wsConnected} queueCount={pendingQueueCount} readOnly={isReadOnly}
      onBack={onBack} onManageMembers={onManageMembers} onCreate={handleAddElement} onEditElement={handleEditElement}
      onImageUpload={openImageImport}
      onEditRelation={setEditingRelation} onRelationType={setPendingRelationType} onValidate={handleValidateDiagram} onRelational={handleGenerateRelational}
      onAi={(mode) => { setAiInitialMode(mode || 'command'); setShowAi(true) }} onGenerator={() => setShowGenerator(true)} onVersions={() => setShowVersions(true)}
      onExchange={() => setShowXmi(true)} onApiTester={() => setShowApiTester(true)} onDeleteSelected={handleDeleteSelected}
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
            onClick={() => setShowGenerator(true)}
            title="Generar Backend Spring Boot y Frontend React en archivo ZIP"
          >
            <CodeIcon size={14} color="white" /> Generar Software
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
        className={`uml-canvas-viewport ${isResizingCanvas ? 'is-resizing-canvas' : ''} ${canvasProposal.active ? 'acodi-preview-active' : ''}`}
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
        <CanvasProposalBar key={canvasProposal.active?.id || 'none'} active={canvasProposal.active} pending={canvasProposal.pending}
          editable={!isReadOnly && isOnline && pendingQueueCount === 0} busy={canvasProposal.busy} error={canvasProposal.error}
          stale={Boolean(canvasProposal.active && (canvasProposal.active.status === 'CONFLICTO' || canvasProposal.active.baseRevision !== diagram?.revision))}
          onSelect={canvasProposal.select} onApprove={canvasProposal.approve} onReject={canvasProposal.reject} onRegenerate={canvasProposal.regenerate} onFocus={focusProposalChanges} />
        <ReactFlow
          onInit={instance => { flowInstance.current = instance }}
          nodes={previewGraph.nodes}
          edges={previewGraph.edges}
          onNodesChange={canvasProposal.active ? undefined : onNodesChange}
          onEdgesChange={canvasProposal.active ? undefined : onEdgesChange}
          onConnect={canvasProposal.active ? undefined : handleConnect}
          onReconnect={canvasProposal.active ? undefined : handleReconnect}
          onNodeDragStop={canvasProposal.active ? undefined : handleNodeDragStop}
          onEdgeClick={canvasProposal.active ? undefined : handleEdgeClick}
          nodeTypes={NODE_TYPES}
          edgeTypes={EDGE_TYPES}
          nodesDraggable={!isReadOnly && !canvasProposal.active}
          nodesConnectable={!isReadOnly && !canvasProposal.active}
          edgesReconnectable={!isReadOnly && !canvasProposal.active}
          deleteKeyCode={canvasProposal.active ? null : 'Backspace'}
          elementsSelectable={true}
          selectionOnDrag={true}
          selectionMode={SelectionMode.Partial}
          panOnDrag={[1, 2]}
          multiSelectionKeyCode={['Control', 'Meta']}
          fitView
        >
          <Background variant={BackgroundVariant.Dots} gap={16} size={1} color="#cbd5e1" />
          <Controls />
          <MiniMap nodeStrokeWidth={3} zoomable pannable />
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
        <VersionsModal
          projectId={project.id}
          diagramId={diagram.id}
          token={auth.token}
          revision={diagram.revision}
          isReadOnly={isReadOnly}
          onClose={() => setShowVersions(false)}
          onRestored={handleFullDiagramUpdated}
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
