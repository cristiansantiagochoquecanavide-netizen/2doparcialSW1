export const mentionPattern = () => /(?<![\p{L}\p{N}_@.+-])@acodi(?![\p{L}\p{N}_@.+-])/iu
export function insertAcodiMention(text, cursor = text.length) {
  if (mentionPattern().test(text)) return { text, cursor: Math.max(0, Math.min(cursor, text.length)) }
  const position = Math.max(0, Math.min(cursor, text.length)), left = text.slice(0, position), right = text.slice(position)
  const insertion = (left && !/\s$/.test(left) ? ' ' : '') + '@ACODI '
  return { text: left + insertion + right, cursor: left.length + insertion.length }
}
export function acodiError(code) {
  return ({ RATE_LIMITED: 'ACODI alcanzó el límite temporal del proveedor. Espera antes de reintentar.', LIMIT_REACHED: 'Se alcanzó el presupuesto o límite de consultas del proyecto. Espera el plazo indicado.', CONTEXT_REQUIRED: 'Selecciona un diagrama del proyecto para que ACODI pueda responder.', CONTEXT_UNAVAILABLE: 'El contexto del diagrama ya no está disponible o no tienes acceso.', INTERRUPTED: 'La consulta se interrumpió al reiniciar el servidor. Puedes reintentar de forma explícita.', PROVIDER_UNAVAILABLE: 'ACODI no está disponible en este momento. Tu mensaje se conservó.', RESPONSE_FAILED: 'No fue posible obtener una respuesta válida. Puedes reintentar.' })[code] || 'No fue posible completar la consulta a ACODI.'
}
export function channelSpeechMessage(message) {
  return { ...message, id: message.canonicalAiMessageId || message.id, senderType: message.authorType, senderUserId: message.senderUserId, recipientUserId: message.replyToMessageId ? 'individual' : null, status: 'COMPLETED' }
}
export function channelRecommendationFrame(projectId, message) {
  if (message.authorType !== 'AGENT' || !message.canonicalAiMessageId || message.replyToMessageId) return null
  return { type: 'AI_CONVERSATION_MESSAGE', projectId, conversationId: 'channel:' + message.channelId, scope: 'GROUP', message: channelSpeechMessage(message) }
}
