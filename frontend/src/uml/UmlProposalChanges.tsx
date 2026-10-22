import { describeChange, type ProposalChange } from './umlProposalClient.mjs'

/** Displays the exact persisted patch, including member IDs and unchanged class properties. */
export function UmlProposalChanges({ changes }: { changes: ProposalChange[] }) {
  return <>{changes.map(change => <article className={`ai-proposal-change ${change.kind.toLowerCase()}`} key={change.id}>
    <h4>{describeChange(change)}</h4><small>ID: {change.id}</small>
    <div className="ai-proposal-diff"><section><strong>Antes</strong><pre>{change.before ? JSON.stringify(change.before, null, 2) : 'No existe'}</pre></section><section><strong>Después</strong><pre>{change.after ? JSON.stringify(change.after, null, 2) : 'Eliminado'}</pre></section></div>
  </article>)}</>
}
