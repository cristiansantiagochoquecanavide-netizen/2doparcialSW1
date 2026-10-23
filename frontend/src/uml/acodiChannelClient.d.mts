import type { ChatMessage } from './LiveTeamChatPanel'
import type { SpeechMessage } from './agentSpeech.mjs'
export function mentionPattern(): RegExp
export function insertAcodiMention(text: string, cursor?: number): { text: string; cursor: number }
export function acodiError(code: string | null): string
export function channelSpeechMessage(message: ChatMessage): SpeechMessage
export function channelRecommendationFrame(projectId: string, message: ChatMessage): unknown | null
