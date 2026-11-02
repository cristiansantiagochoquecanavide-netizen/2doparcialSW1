export type VersionChange = { entity: string; action: 'AGREGADO' | 'ELIMINADO' | 'MODIFICADO'; id: string; label: string }
export function compareVersions(before: unknown, after: unknown): VersionChange[]
