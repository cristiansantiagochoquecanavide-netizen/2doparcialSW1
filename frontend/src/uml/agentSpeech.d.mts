export type SpeechState = { supported: boolean; available: boolean; automatic: boolean; speaking: boolean; busy: boolean; pending: boolean; roomConnected: boolean; microphoneActive: boolean; rate: number; volume: number; error: string }
export type SpeechMessage = { id: string; senderType: string; status: string; content: string; createdAt?: string }
export type SpeechSource = { projectId: string; conversationId: string; scope: string }
export type SpeechView = { conversationId: string; scope: string; active: boolean }
export type AgentSpeech = { state(): SpeechState; setAutomatic(value: boolean): void; configure(options: { rate?: number; volume?: number }): void; session(options: { room: boolean; chat: boolean; mic?: boolean }): void; view(view: SpeechView): void; receive(frame: unknown): boolean; manual(message: SpeechMessage, source: SpeechSource): boolean; stop(): void; dispose(): void }
export function spanishLocalVoice(voices: SpeechSynthesisVoice[]): SpeechSynthesisVoice | null
export function spokenText(content: string, limit?: number): string
export function createSpeechPreferences(): { read(user: string, session: string): { session: string; automatic: boolean; rate: number; volume: number } | null; write(user: string, session: string, state: Pick<SpeechState, 'automatic' | 'rate' | 'volume'>): void }
export function createAgentSpeech(options: { synthesis: SpeechSynthesis | null; Utterance: typeof SpeechSynthesisUtterance | null; projectId: string; now?: () => number; onState?: (state: SpeechState) => void }): AgentSpeech
