import test from 'node:test'
import assert from 'node:assert/strict'
import { scrollChatViewportToLatest } from './scrollChatViewport.mjs'

test('channel autoscroll targets only the chat messages container', () => {
  const calls = []
  const messagesContainer = {
    scrollHeight: 720,
    scrollTo: (options) => calls.push(options)
  }

  scrollChatViewportToLatest(messagesContainer, 'auto')

  assert.deepEqual(calls, [{ top: 720, behavior: 'auto' }])
})

test('new chat messages keep smooth autoscroll inside the chat container', () => {
  const calls = []
  const messagesContainer = {
    scrollHeight: 480,
    scrollTo: (options) => calls.push(options)
  }

  scrollChatViewportToLatest(messagesContainer)

  assert.deepEqual(calls, [{ top: 480, behavior: 'smooth' }])
  assert.doesNotThrow(() => scrollChatViewportToLatest(null))
})
