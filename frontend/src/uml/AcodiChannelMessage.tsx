import type { ChatMessage } from './LiveTeamChatPanel'
import { acodiError } from './acodiChannelClient.mjs'

export function AcodiChannelMessage({ message, own, canListen, canRetry, busy, onListen, onRetry }: { message: ChatMessage; own: boolean; canListen: boolean; canRetry: boolean; busy: boolean; onListen: () => void; onRetry: () => void }) {
  const agent = message.authorType === 'AGENT', job = message.acodi
  return <article className={'team-message-card' + (agent ? ' team-msg-acodi' : own ? ' team-msg-own' : '')}>
    <div className="team-msg-avatar" aria-label={agent ? 'Avatar ACODI' : undefined}>{agent ? 'AC' : message.senderName.slice(0, 1).toUpperCase()}</div>
    <div className="team-msg-body"><header className="team-msg-header"><strong className="team-msg-author">{agent ? 'ACODI' : message.senderName}</strong><span className="role-pill">{agent ? 'Agente IA' : message.senderRole}</span><time className="team-msg-time" dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleString('es-BO')}</time></header>
      {message.replyToMessageId && <small>En respuesta a la consulta · {message.replyToMessageId.slice(0, 8)}</small>}
      <div className="team-msg-text">{message.content}</div>
      {agent && <button type="button" disabled={!canListen} onClick={onListen}>Escuchar</button>}
      {job && ['PENDIENTE','PROCESANDO'].includes(job.status) && <small role="status">{job.status === 'PENDIENTE' ? 'Consulta en cola para ACODI…' : 'ACODI está preparando una respuesta…'}</small>}
      {job?.status === 'FALLIDO' && <div className="team-inline-error" role="alert"><span>{acodiError(job.errorCode)}{job.retryAfter && ` Puedes reintentar a partir de ${new Date(job.retryAfter).toLocaleTimeString('es-BO')}.`}</span>{canRetry && <button type="button" disabled={busy || job.attempts >= (job.maxAttempts ?? 3) || Boolean(job.retryAfter && Date.parse(job.retryAfter) > Date.now())} onClick={onRetry}>Reintentar ACODI</button>}</div>}
    </div>
  </article>
}
