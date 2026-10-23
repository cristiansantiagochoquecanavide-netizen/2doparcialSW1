import { memo } from 'react'
import { Handle, Position, type NodeProps, type Node } from '@xyflow/react'
import { TipoElementoUML, parseUmlProperties } from './types'

export type UmlNodeData = {
  elementId: string
  name: string
  elementType: TipoElementoUML
  propertiesJson: string
  readOnly?: boolean
  onEdit?: (id: string) => void
  onDelete?: (id: string) => void
  previewKind?: string
  previewMembers?: Record<string, string>
  previewFields?: string[]
}

export type UmlNodeType = Node<UmlNodeData, 'umlClass'>

function UmlClassNodeComponent({ data, selected }: NodeProps<UmlNodeType>) {
  const { attributes, methods, description, stereotype: customStereotype } = parseUmlProperties(data.propertiesJson)

  const stereotype =
    customStereotype && customStereotype.trim()
      ? (customStereotype.startsWith('«') ? customStereotype : `«${customStereotype}»`)
      : data.elementType === 'INTERFAZ'
      ? '«interface»'
      : data.elementType === 'CLASE_ABSTRACTA'
      ? '«abstract»'
      : data.elementType === 'ENUMERACION'
      ? '«enum»'
      : null

  const isAbstract = data.elementType === 'CLASE_ABSTRACTA'

  const handleDefinitions = [
    { side: Position.Top, axis: 'left' as const },
    { side: Position.Right, axis: 'top' as const },
    { side: Position.Bottom, axis: 'left' as const },
    { side: Position.Left, axis: 'top' as const }
  ]

  const renderConnectionHandles = (type: 'source' | 'target') =>
    handleDefinitions.flatMap(({ side, axis }) =>
      (type === 'source' ? [20, 50, 80] : [30, 60, 90]).map((offset, index) => {
        const sideName = side.toLowerCase()
        const style = axis === 'left'
          ? { left: `${offset}%` }
          : { top: `${offset}%` }

        return (
          <Handle
            key={`${sideName}-${type}-${offset}`}
            type={type}
            position={side}
            id={`${sideName}-${type}-${index}`}
            className="uml-edge-handle"
            style={style}
          />
        )
      })
    )

  return (
    <div className={`uml-node-card ${selected ? 'selected' : ''} ${data.elementType.toLowerCase()} ${data.previewKind ? 'acodi-preview' : ''} ${data.previewKind === 'DELETE' ? 'acodi-preview-delete' : ''} ${data.previewKind === 'CREATE' ? 'acodi-preview-create' : ''}`}>
      {/* Handles para conexiones de relaciones UML */}
      {renderConnectionHandles('target')}
      {renderConnectionHandles('source')}

      {/* Cabecera del elemento UML */}
      <div className="uml-node-header">
        {stereotype && <div className={`uml-stereotype ${data.previewFields?.some(f => f === 'type' || f === 'stereotype') ? 'acodi-field-preview' : ''}`}>{stereotype}</div>}
        <div className={`uml-node-title ${isAbstract ? 'italic' : ''} ${data.previewFields?.includes('name') ? 'acodi-field-preview' : ''}`}>{data.name}</div>
        {description && description.trim() && (
          <div className={`uml-node-description ${data.previewFields?.includes('description') ? 'acodi-field-preview' : ''}`} title={description}>
            {description}
          </div>
        )}

        {!data.readOnly && (
          <div className="uml-node-actions nodrag">
            <button
              type="button"
              className="uml-btn-tiny"
              title="Editar atributos y operaciones"
              onClick={(e) => {
                e.stopPropagation()
                data.onEdit?.(data.elementId)
              }}
            >
              ✎
            </button>
            <button
              type="button"
              className="uml-btn-tiny danger"
              title="Eliminar elemento"
              onClick={(e) => {
                e.stopPropagation()
                data.onDelete?.(data.elementId)
              }}
            >
              ✕
            </button>
          </div>
        )}
      </div>

      {/* Compartimento de Atributos */}
      <div className="uml-node-compartment">
        {attributes.length === 0 ? (
          <div className="uml-empty-line"> </div>
        ) : (
          attributes.map((attr) => (
            <div key={attr.id} title={data.previewMembers?.[attr.id] === 'DELETE' ? 'Eliminar atributo' : undefined} className={`uml-attribute-item ${data.previewMembers?.[attr.id] ? 'acodi-member-preview' : ''} ${data.previewMembers?.[attr.id] === 'DELETE' ? 'acodi-deleted' : ''}`}>
              <span className="vis">{attr.visibility}</span> {attr.name}:{' '}
              <span className="type">{attr.type}</span>
            </div>
          ))
        )}
      </div>

      {/* Compartimento de Métodos / Operaciones */}
      <div className="uml-node-compartment">
        {methods.length === 0 ? (
          <div className="uml-empty-line"> </div>
        ) : (
          methods.map((method) => {
            const cleanName = (method.name || '').replace(/\(\s*\)$/, '')
            return (
              <div key={method.id} title={data.previewMembers?.[method.id] === 'DELETE' ? 'Eliminar método' : undefined} className={`uml-method-item ${data.previewMembers?.[method.id] ? 'acodi-member-preview' : ''} ${data.previewMembers?.[method.id] === 'DELETE' ? 'acodi-deleted' : ''}`}>
                <span className="vis">{method.visibility}</span> {cleanName}({method.parameters || ''}):{' '}
                <span className="type">{method.returnType}</span>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}

export const UmlClassNode = memo(UmlClassNodeComponent)
