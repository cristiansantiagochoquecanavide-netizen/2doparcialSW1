import { useState } from 'react'

/** Collapse the rendering only: the stored message and the speech source remain complete. */
export function ExpandableMessage({ content, className }: { content: string; className?: string }) {
  const [expanded, setExpanded] = useState(false)
  const long = content.length > 800
  return <div className={className}><div className="team-message-content">{long && !expanded ? content.slice(0, 800) + '…' : content}</div>{long && <button type="button" className="team-expand-message" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{expanded ? 'Ver menos' : 'Ver respuesta completa'}</button>}</div>
}
