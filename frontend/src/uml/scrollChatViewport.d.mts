export function scrollChatViewportToLatest(
  container: Pick<HTMLElement, 'scrollHeight' | 'scrollTo'> | null | undefined,
  behavior?: ScrollBehavior
): void
