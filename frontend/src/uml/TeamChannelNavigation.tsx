import { useState } from 'react'
import { HashIcon, VolumeIcon, LockIcon } from './icons'

export type NavigationItem = { id: string; name: string; kind: 'text' | 'voice' | 'private' | 'legacy'; protected?: boolean }
type Props = {
  items: NavigationItem[]; selected: string; busy: boolean
  onSelect: (item: NavigationItem) => void
  onCreate: (kind: 'text' | 'voice' | 'private') => void
  onRename: (item: NavigationItem) => void; onDelete: (item: NavigationItem) => void
}

export function TeamChannelNavigation({ items, selected, busy, onSelect, onCreate, onRename, onDelete }: Props) {
  const [menu, setMenu] = useState('')
  return <nav className="team-channel-sidebar" aria-label="Canales y conversaciones">
    {(['text', 'voice', 'private'] as const).map(kind => <section key={kind} className="team-nav-section">
      <div className="team-nav-heading"><span>{kind === 'text' ? 'Canales de texto' : kind === 'voice' ? 'Canales de voz' : 'Mis chats con COIN'}</span><button type="button" disabled={busy} aria-label={kind === 'text' ? 'Crear canal de texto' : kind === 'voice' ? 'Crear sala de voz' : 'Crear chat privado'} onClick={() => onCreate(kind)}>+</button></div>
      {items.filter(item => item.kind === kind || (kind === 'text' && item.kind === 'legacy')).map(item => <div key={item.kind + ':' + item.id} className={'team-nav-entry' + (menu === item.id ? ' menu-open' : '')}>
        <div className="team-nav-row"><button type="button" className={'team-channel-link' + (selected === item.kind + ':' + item.id ? ' active' : '')} onClick={() => { setMenu(''); onSelect(item) }} title={item.name + (item.kind === 'legacy' ? ' · Historial grupal de COIN' : '')}>
          {kind === 'voice' ? <VolumeIcon size={13} /> : kind === 'private' ? <LockIcon size={13} /> : <HashIcon size={13} />}<span className="channel-name">{item.name}</span>{item.kind === 'legacy' && <small>IA</small>}
        </button><button type="button" className="team-nav-options" aria-label={'Opciones de ' + item.name} aria-expanded={menu === item.id} onClick={() => setMenu(menu === item.id ? '' : item.id)}>⋯</button></div>
        {menu === item.id && <div className="team-nav-menu"><button type="button" disabled={busy} onClick={() => { setMenu(''); onRename(item) }}>Cambiar nombre</button><button type="button" disabled={busy || item.protected} title={item.protected ? '#general no se puede eliminar' : undefined} onClick={() => { setMenu(''); onDelete(item) }}>{item.kind === 'private' ? 'Eliminar chat' : 'Eliminar'}</button></div>}
      </div>)}
    </section>)}
  </nav>
}
