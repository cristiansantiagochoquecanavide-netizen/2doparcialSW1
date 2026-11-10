import { FormEvent, useCallback, useEffect, useState } from 'react'
import { UmlCanvas } from './uml/UmlCanvas'

type Role = 'PROPIETARIO' | 'EDITOR' | 'LECTOR'
type Project = { id: string; name: string; description: string | null; role: Role; createdAt: string }
type Member = { id: string; userId: string; name: string; email: string; role: Role }
type Auth = { token: string; name: string; email: string }
const base = (import.meta.env.VITE_API_BASE_URL !== undefined ? import.meta.env.VITE_API_BASE_URL : '').replace(/\/+$/, '')

function readStoredAuth(): Auth | null {
  try {
    const raw = localStorage.getItem('case.auth')
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<Auth>
    if (typeof value.token !== 'string' || !value.token || typeof value.name !== 'string' || typeof value.email !== 'string') {
      localStorage.removeItem('case.auth')
      return null
    }
    return { token: value.token, name: value.name, email: value.email }
  } catch {
    localStorage.removeItem('case.auth')
    return null
  }
}

async function api<T>(path: string, token?: string, options: RequestInit = {}): Promise<T> {
  const url = base ? `${base}${path}` : path
  const response = await fetch(url, { ...options, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers } })
  if (!response.ok) {
    const raw = await response.text()
    let message = ''
    try { message = (JSON.parse(raw) as { message?: string }).message ?? '' } catch { message = raw.trim() }
    if (response.status === 401 || response.status === 403) {
      localStorage.removeItem('case.auth')
      window.dispatchEvent(new Event('case:unauthorized'))
    }
    throw new Error(message || `No se pudo completar la operación (${response.status}).`)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export default function App() {
  const [auth, setAuth] = useState<Auth | null>(readStoredAuth)
  const [registering, setRegistering] = useState(false), [projects, setProjects] = useState<Project[]>([]), [selected, setSelected] = useState<Project | null>(null), [message, setMessage] = useState<string | null>(null)
  const error = useCallback((value: unknown) => setMessage(value instanceof Error ? value.message : 'Error inesperado.'), [])
  useEffect(() => {
    const logout = () => { setAuth(null); setSelected(null); setProjects([]); setMessage('Tu sesión expiró. Ingresa nuevamente.') }
    window.addEventListener('case:unauthorized', logout)
    return () => window.removeEventListener('case:unauthorized', logout)
  }, [])
  const load = () => auth && api<Project[]>('/api/projects', auth.token).then(setProjects).catch(error)
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps
  if (!auth) return <AuthScreen registering={registering} setRegistering={v => { setRegistering(v); setMessage(null) }} onAuth={value => { localStorage.setItem('case.auth', JSON.stringify(value)); setAuth(value); setMessage(null) }} onError={error} onClearError={() => setMessage(null)} message={message} />
  if (selected) return <Workspace project={selected} auth={auth} onBack={() => { setSelected(null); load() }} onError={error} />
  const token = auth.token
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    try {
      setMessage(null)
      await api('/api/projects', token, {
        method: 'POST',
        body: JSON.stringify({ name: form.get('name'), description: form.get('description') })
      })
      formEl.reset()
      load()
    } catch (e) {
      error(e)
    }
  }
  async function rename(p: Project) { const name = prompt('Nombre del proyecto:', p.name); if (!name) return; try { await api(`/api/projects/${p.id}`, token, { method: 'PUT', body: JSON.stringify({ name, description: p.description }) }); load() } catch (e) { error(e) } }
  async function remove(p: Project) { if (!confirm(`¿Eliminar “${p.name}”?`)) return; try { await api(`/api/projects/${p.id}`, token, { method: 'DELETE' }); load() } catch (e) { error(e) } }
  return <main className="app"><header><div><h1>Proyecto CASE</h1><p>{auth.name} · {auth.email}</p></div><button className="secondary" onClick={() => { localStorage.removeItem('case.auth'); setAuth(null); setMessage(null) }}>Salir</button></header>{message && <p className="error">{message}</p>}
    <section className="card"><h2>Nuevo proyecto de modelado</h2><form className="inline" onSubmit={create}><input name="name" placeholder="Nombre" required maxLength={120} /><input name="description" placeholder="Descripción opcional" maxLength={500} /><button>Crear</button></form></section>
    <section className="card"><h2>Mis proyectos</h2>{projects.length === 0 ? <p>Aún no participas en ningún proyecto.</p> : <div className="projects">{projects.map(p => <article key={p.id}><div><strong>{p.name}</strong><span className="role">{p.role}</span><p>{p.description || 'Sin descripción'}</p></div><div className="actions"><button onClick={() => setSelected(p)}>Abrir</button>{p.role !== 'LECTOR' && <button className="secondary" onClick={() => rename(p)}>Editar</button>}{p.role === 'PROPIETARIO' && <button className="danger" onClick={() => remove(p)}>Eliminar</button>}</div></article>)}</div>}</section></main>
}

function AuthScreen({ registering, setRegistering, onAuth, onError, onClearError, message }: { registering: boolean; setRegistering: (v: boolean) => void; onAuth: (v: Auth) => void; onError: (e: unknown) => void; onClearError: () => void; message: string | null }) {
  const [submitting, setSubmitting] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const f = new FormData(event.currentTarget)
    setSubmitting(true)
    onClearError()
    try {
      const value = await api<Auth>(registering ? '/api/auth/register' : '/api/auth/login', undefined, { method: 'POST', body: JSON.stringify({ email: f.get('email'), password: f.get('password'), ...(registering ? { name: f.get('name') } : {}) }) })
      localStorage.setItem('case.auth', JSON.stringify(value))
      onAuth(value)
    } catch (e) { onError(e) } finally { setSubmitting(false) }
  }
  return <main className="auth"><section className="card"><h1>Proyecto CASE</h1><p>{registering ? 'Crea tu cuenta para iniciar un espacio de modelado.' : 'Ingresa a tus proyectos de modelado.'}</p>{message && <p className="error">{message}</p>}<form onSubmit={submit}>{registering && <input name="name" placeholder="Nombre" required maxLength={100} />}<input name="email" type="email" placeholder="Correo" autoComplete="email" required /><input name="password" type="password" placeholder="Contraseña (mínimo 8 caracteres)" autoComplete={registering ? 'new-password' : 'current-password'} required minLength={8} /><button disabled={submitting}>{submitting ? 'Procesando…' : registering ? 'Registrarme' : 'Iniciar sesión'}</button></form><button type="button" className="link" onClick={() => { setRegistering(!registering); onClearError() }} disabled={submitting}>{registering ? 'Ya tengo una cuenta' : 'Crear una cuenta'}</button></section></main>
}

function Workspace({ project, auth, onBack, onError }: { project: Project; auth: Auth; onBack: () => void; onError: (e: unknown) => void }) {
  const [members, setMembers] = useState<Member[]>([])
  const [showMembers, setShowMembers] = useState(false)
  const loadMembers = () => api<Member[]>(`/api/projects/${project.id}/members`, auth.token).then(setMembers).catch(onError)
  useEffect(() => { loadMembers() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  async function add(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const formEl = event.currentTarget; const f = new FormData(formEl); try { await api(`/api/projects/${project.id}/members`, auth.token, { method: 'POST', body: JSON.stringify({ email: f.get('email'), role: f.get('role') }) }); formEl.reset(); loadMembers() } catch (e) { onError(e) } }
  async function role(member: Member, value: string) { try { await api(`/api/projects/${project.id}/members/${member.id}`, auth.token, { method: 'PUT', body: JSON.stringify({ role: value }) }); loadMembers() } catch (e) { onError(e) } }
  async function remove(member: Member) { if (!confirm(`¿Quitar a ${member.name}?`)) return; try { await api(`/api/projects/${project.id}/members/${member.id}`, auth.token, { method: 'DELETE' }); loadMembers() } catch (e) { onError(e) } }
  return (
    <main className="app workspace-page">
      <header>
        <div>
          <h1>{project.name}</h1>
          <p>Workspace de modelado UML · Tu rol: <strong className="role">{project.role}</strong></p>
        </div>
        <div className="actions">
          <button className="secondary" onClick={() => setShowMembers(!showMembers)}>
            {showMembers ? 'Ocultar Colaboradores' : `Colaboradores (${members.length})`}
          </button>
          <button className="secondary" onClick={onBack}>Volver a proyectos</button>
        </div>
      </header>

      {showMembers && (
        <section className="card collaborators-panel">
          <h2>Colaboradores del proyecto</h2>
          {project.role === 'PROPIETARIO' && (
            <form className="inline" onSubmit={add}>
              <input name="email" type="email" required placeholder="Correo del colaborador" />
              <select name="role" defaultValue="EDITOR">
                <option value="EDITOR">EDITOR</option>
                <option value="LECTOR">LECTOR</option>
              </select>
              <button>Agregar</button>
            </form>
          )}
          <div className="members">
            {members.map(m => (
              <div key={m.id} className="member">
                <span><strong>{m.name}</strong> · {m.email}</span>
                {project.role === 'PROPIETARIO' && m.role !== 'PROPIETARIO' ? (
                  <span>
                    <select value={m.role} onChange={e => role(m, e.target.value)}>
                      <option value="EDITOR">EDITOR</option>
                      <option value="LECTOR">LECTOR</option>
                    </select>
                    <button className="danger" onClick={() => remove(m)}>Quitar</button>
                  </span>
                ) : (
                  <span className="role">{m.role}</span>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      <UmlCanvas project={project} auth={auth} onError={onError} members={members} onManageMembers={() => setShowMembers(value => !value)} onBack={onBack} />
    </main>
  )
}
