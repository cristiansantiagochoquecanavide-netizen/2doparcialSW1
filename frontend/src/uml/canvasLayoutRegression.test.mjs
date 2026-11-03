import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('canvas heading actions wrap above the clipped canvas at narrow widths', async () => {
  const styles = await readFile(new URL('../styles.css', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')

  assert.match(styles, /\.canvas-heading\s*\{[^}]*min-height:\s*44px;[^}]*flex:\s*0 0 auto;[^}]*flex-wrap:\s*wrap/s)
  assert.match(styles, /\.canvas-heading-actions\s*\{[^}]*flex:\s*1 1 420px;[^}]*flex-wrap:\s*wrap;[^}]*min-width:\s*0/s)
  assert.match(styles, /\.canvas-heading\s*\{[^}]*position:\s*relative;[^}]*z-index:\s*4/s)
  assert.match(workspace, /onClick=\{p\.onSelectAll\}/)
  assert.match(workspace, /<details className="more-tools">/)
})

test('responsive workspace has one bounded row and removes the orphaned right resizer', async () => {
  const styles = await readFile(new URL('../styles.css', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')

  assert.match(styles, /\.workspace-grid\s*\{[^}]*grid-template-rows:\s*minmax\(0, 1fr\)/s)
  assert.match(styles, /@media\s*\(max-width:\s*1050px\)[^{]*\{\s*\.workspace-grid\s*>\s*\.right-panel-resizer\s*\{\s*display:\s*none/s)
  assert.match(workspace, /className=\{`panel-resizer right-panel-resizer /)
  assert.match(workspace, /<div className="canvas-mount">\{p\.children\}<\/div>/)
})

test('switching chat channels scrolls only the message pane, never the workspace ancestors', async () => {
  const chat = await readFile(new URL('./LiveTeamChatPanel.tsx', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('./WorkspaceChrome.tsx', import.meta.url), 'utf8')
  const scroll = await readFile(new URL('./scrollChatViewport.mjs', import.meta.url), 'utf8')

  assert.match(chat, /function selectChannel\(id: string\).*setActiveChannelId\(id\); setView\('text'\)/)
  assert.match(chat, /scrollChatViewportToLatest\(scrollRef\.current, behavior\)/)
  assert.doesNotMatch(chat, /scrollIntoView\(/)
  assert.match(scroll, /container\.scrollTo\(\{ top: container\.scrollHeight, behavior \}\)/)
  assert.match(workspace, /key=\{p\.projectId \+ ':' \+ p\.token\}/)
  assert.match(workspace, /<div className="canvas-mount">\{p\.children\}<\/div>/)
})
