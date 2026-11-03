export function scrollChatViewportToLatest(container, behavior = 'smooth') {
  if (!container) return
  container.scrollTo({ top: container.scrollHeight, behavior })
}
