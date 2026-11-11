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
  const [projectsLoading, setProjectsLoading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null)
  const [editTarget, setEditTarget] = useState<Project | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Project | null>(null)
  const [savingEdit, setSavingEdit] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const error = useCallback((value: unknown) => setMessage(value instanceof Error ? value.message : 'Error inesperado.'), [])
  useEffect(() => {
    const logout = () => { setAuth(null); setSelected(null); setProjects([]); setMessage('Tu sesión expiró. Ingresa nuevamente.') }
    window.addEventListener('case:unauthorized', logout)
    return () => window.removeEventListener('case:unauthorized', logout)
  }, [])
  const load = useCallback(() => {
    if (!auth) return Promise.resolve()
    setProjectsLoading(true)
    return api<Project[]>('/api/projects', auth.token).then(setProjects).catch(error).finally(() => setProjectsLoading(false))
  }, [auth, error])
  useEffect(() => { void load() }, [load])
  if (!auth) return <AuthScreen registering={registering} setRegistering={v => { setRegistering(v); setMessage(null) }} onAuth={value => { localStorage.setItem('case.auth', JSON.stringify(value)); setAuth(value); setMessage(null) }} onError={error} onClearError={() => setMessage(null)} message={message} />
  if (selected) return <Workspace project={selected} auth={auth} onBack={() => { setSelected(null); load() }} onError={error} />
  const token = auth.token
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const formEl = event.currentTarget
    const form = new FormData(formEl)
    try {
      setNotice(null)
      setCreating(true)
      await api('/api/projects', token, {
        method: 'POST',
        body: JSON.stringify({ name: form.get('name'), description: form.get('description') })
      })
      formEl.reset()
      setNotice({ kind: 'success', text: 'Proyecto creado. Ya está listo para abrir.' })
      await load()
    } catch (e) { setNotice({ kind: 'error', text: e instanceof Error ? e.message : 'No se pudo crear el proyecto.' }) }
    finally { setCreating(false) }
  }
  async function rename(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!editTarget) return
    const form = new FormData(event.currentTarget), name = String(form.get('name') || '').trim(), description = String(form.get('description') || '').trim()
    if (!name) return
    setSavingEdit(true); setNotice(null); setDialogError(null)
    try {
      await api(`/api/projects/${editTarget.id}`, token, { method: 'PUT', body: JSON.stringify({ name, description }) })
      setEditTarget(null); setNotice({ kind: 'success', text: 'Los datos del proyecto se actualizaron.' }); await load()
    } catch (e) { setDialogError(e instanceof Error ? e.message : 'No se pudo actualizar el proyecto.') }
    finally { setSavingEdit(false) }
  }
  async function remove() {
    if (!deleteTarget) return
    setDeleting(true); setNotice(null); setDialogError(null)
    try {
      await api(`/api/projects/${deleteTarget.id}`, token, { method: 'DELETE' })
      setDeleteTarget(null); setNotice({ kind: 'success', text: 'El proyecto se eliminó.' }); await load()
    } catch (e) { setDialogError(e instanceof Error ? e.message : 'No se pudo eliminar el proyecto.') }
    finally { setDeleting(false) }
  }
  const initials = auth.name.trim().split(/\s+/).slice(0, 2).map(part => part[0]?.toUpperCase()).join('') || auth.email[0]?.toUpperCase()
  return <main className="projects-home">
    <header className="home-header">
      <div className="brand-lockup"><span className="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M7 8.5h12a5 5 0 0 1 0 10H12m0 0 4-4m-4 4 4 4M25 8v16" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"/><circle cx="25" cy="8" r="2" fill="currentColor"/><circle cx="25" cy="24" r="2" fill="currentColor"/></svg></span><span><strong>CASE Studio</strong><small>Modelado UML colaborativo</small></span></div>
      <details className="profile-menu"><summary aria-label={`Perfil de ${auth.name}`}><span className="profile-avatar">{initials}</span><span className="profile-copy"><strong>{auth.name}</strong><small>{auth.email}</small></span><svg aria-hidden="true" viewBox="0 0 20 20"><path d="m5 7 5 5 5-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg></summary><div className="profile-popover"><strong>{auth.name}</strong><span>{auth.email}</span><button type="button" onClick={() => { localStorage.removeItem('case.auth'); setAuth(null); setMessage(null) }}><svg aria-hidden="true" viewBox="0 0 20 20"><path d="M8 4H4v12h4m3-3 3-3-3-3m3 3H7" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg>Cerrar sesión</button></div></details>
    </header>
    <div className="home-content">
      <section className="home-intro"><div><span className="section-kicker">TU ESPACIO DE TRABAJO</span><h1>Diseña sistemas con claridad.</h1><p>Organiza tus proyectos UML y continúa el modelado desde un solo lugar.</p></div><div className="project-count"><span>{projectsLoading ? '…' : projects.length}</span><small>{projects.length === 1 ? 'proyecto' : 'proyectos'}</small></div></section>
      {notice && <div className={`home-notice ${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'} aria-live="polite"><span aria-hidden="true">{notice.kind === 'success' ? '✓' : '!'}</span>{notice.text}<button type="button" aria-label="Cerrar aviso" onClick={() => setNotice(null)}>×</button></div>}
      {message && <div className="home-notice error" role="alert">{message}</div>}
      <section className="create-project-card" aria-labelledby="create-project-title"><div className="card-heading"><span className="create-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/></svg></span><div><span className="section-kicker">EMPIEZA ALGO NUEVO</span><h2 id="create-project-title">Crear proyecto</h2><p>Define el espacio donde construirás tu próximo modelo.</p></div></div><form className="create-project-form" onSubmit={create}>
        <label><span>Nombre del proyecto <b aria-hidden="true">*</b></span><input name="name" placeholder="Ej. Sistema de biblioteca" required maxLength={120} aria-label="Nombre del proyecto" /></label>
        <label><span>Descripción <small>Opcional</small></span><input name="description" placeholder="¿Qué sistema vas a modelar?" maxLength={500} aria-label="Descripción opcional" /></label>
        <button className="primary-action" disabled={creating}><span>{creating ? 'Creando…' : 'Crear proyecto'}</span>{creating ? <span className="button-spinner" aria-hidden="true" /> : <span aria-hidden="true">→</span>}</button>
      </form></section>
      <section className="project-section" aria-labelledby="projects-title"><div className="projects-heading"><div><span className="section-kicker">TUS ESPACIOS</span><h2 id="projects-title">Mis proyectos</h2></div><span className="projects-total">{projectsLoading ? 'Cargando…' : `${projects.length} ${projects.length === 1 ? 'proyecto' : 'proyectos'}`}</span></div>
        {projectsLoading && projects.length === 0 ? <div className="projects-loading" role="status"><span className="button-spinner" />Cargando tus proyectos…</div> : projects.length === 0 ? <div className="empty-projects"><span className="empty-project-icon" aria-hidden="true"><svg viewBox="0 0 48 48" fill="none"><rect x="9" y="10" width="30" height="27" rx="5" stroke="currentColor" strokeWidth="2"/><path d="M16 19h16M16 25h10M16 31h7" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/><path d="M34 7v8M30 11h8" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/></svg></span><h3>Aquí empieza tu próximo proyecto</h3><p>Crea un espacio de trabajo para diseñar y compartir un diagrama UML.</p></div> : <div className="project-grid">{projects.map(p => <article className="project-card" key={p.id}><div className="project-card-top"><span className="project-symbol" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><rect x="3" y="4" width="8" height="6" rx="1.5" stroke="currentColor" strokeWidth="1.6"/><rect x="13" y="14" width="8" height="6" rx="1.5" stroke="currentColor" strokeWidth="1.6"/><path d="M11 7h3a3 3 0 0 1 3 3v4" stroke="currentColor" strokeWidth="1.6"/><circle cx="17" cy="14" r="1.2" fill="currentColor"/></svg></span><span className={`project-role ${p.role.toLowerCase()}`}>{p.role === 'PROPIETARIO' ? 'Propietario' : p.role === 'EDITOR' ? 'Editor' : 'Lector'}</span><details className="project-options"><summary aria-label={`Opciones de ${p.name}`}>•••</summary><div>{p.role !== 'LECTOR' && <button type="button" onClick={() => { setEditTarget(p); setDialogError(null); setNotice(null) }}>Editar proyecto</button>}{p.role === 'PROPIETARIO' && <button type="button" className="option-danger" onClick={() => { setDeleteTarget(p); setDialogError(null); setNotice(null) }}>Eliminar proyecto</button>}</div></details></div><div className="project-card-body"><h3>{p.name}</h3><p>{p.description?.trim() || 'Sin descripción agregada.'}</p></div><div className="project-card-meta"><span><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M6 3v3m8-3v3M4 8h12M5 5h10a1 1 0 0 1 1 1v9H4V6a1 1 0 0 1 1-1Z" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>Creado {new Intl.DateTimeFormat('es-BO', { dateStyle: 'medium' }).format(new Date(p.createdAt))}</span></div><div className="project-card-footer"><button type="button" className="open-project" onClick={() => setSelected(p)}>Abrir proyecto<span aria-hidden="true">→</span></button></div></article>)}</div>}
      </section>
    </div>
    {editTarget && <div className="modal-backdrop" onKeyDown={event => { if (event.key === 'Escape' && !savingEdit) setEditTarget(null) }} onMouseDown={event => { if (event.target === event.currentTarget && !savingEdit) setEditTarget(null) }}><section className="project-dialog" role="dialog" aria-modal="true" aria-labelledby="edit-project-title"><button type="button" className="dialog-close" disabled={savingEdit} aria-label="Cerrar" onClick={() => setEditTarget(null)}>×</button><span className="section-kicker">DETALLES DEL PROYECTO</span><h2 id="edit-project-title">Editar proyecto</h2><p>Actualiza el nombre o la descripción de este espacio.</p>{dialogError && <div className="dialog-error" role="alert">{dialogError}</div>}<form onSubmit={rename}><label>Nombre del proyecto<input name="name" defaultValue={editTarget.name} required maxLength={120} autoFocus /></label><label>Descripción <small>Opcional</small><textarea name="description" defaultValue={editTarget.description || ''} maxLength={500} rows={3} /></label><div className="dialog-actions"><button type="button" className="dialog-secondary" disabled={savingEdit} onClick={() => setEditTarget(null)}>Cancelar</button><button className="primary-action" disabled={savingEdit}>{savingEdit ? 'Guardando…' : 'Guardar cambios'}</button></div></form></section></div>}
    {deleteTarget && <div className="modal-backdrop" onKeyDown={event => { if (event.key === 'Escape' && !deleting) setDeleteTarget(null) }} onMouseDown={event => { if (event.target === event.currentTarget && !deleting) setDeleteTarget(null) }}><section className="project-dialog confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="delete-project-title" aria-describedby="delete-project-description"><span className="delete-dialog-icon" aria-hidden="true">!</span><h2 id="delete-project-title">¿Eliminar proyecto?</h2><p id="delete-project-description">Se eliminará <strong>{deleteTarget.name}</strong>. Esta acción no se puede deshacer.</p>{dialogError && <div className="dialog-error" role="alert">{dialogError}</div>}<div className="dialog-actions"><button type="button" className="dialog-secondary" autoFocus disabled={deleting} onClick={() => setDeleteTarget(null)}>Cancelar</button><button type="button" className="danger-action" disabled={deleting} onClick={() => void remove()}>{deleting ? 'Eliminando…' : 'Eliminar proyecto'}</button></div></section></div>}
  </main>
}

function AuthScreen({ registering, setRegistering, onAuth, onError, onClearError, message }: { registering: boolean; setRegistering: (v: boolean) => void; onAuth: (v: Auth) => void; onError: (e: unknown) => void; onClearError: () => void; message: string | null }) {
  const [submitting, setSubmitting] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
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
  return <main className="auth-screen">
    <section className="auth-story" aria-label="CASE Studio, modelado UML colaborativo">
      <div className="auth-story-inner">
        <div className="auth-brand"><span className="auth-brand-icon" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M7 8.5h12a5 5 0 0 1 0 10H12m0 0 4-4m-4 4 4 4M25 8v16" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"/><circle cx="25" cy="8" r="2" fill="currentColor"/><circle cx="25" cy="24" r="2" fill="currentColor"/></svg></span><span><strong>CASE Studio</strong><small>UML · TEAM WORKSPACE</small></span></div>
        <div className="auth-story-copy"><span className="auth-overline"><i /> ENTORNO DE MODELADO COLABORATIVO</span><h1>Diseña, modela<br />y colabora.</h1><p>Convierte ideas en sistemas claros. Construye diagramas UML junto a tu equipo y avanza con la asistencia de COIN.</p></div>
        <div className="uml-visual" aria-label="Composición visual de un diagrama UML" role="img">
          <svg className="uml-connectors" viewBox="0 0 520 255" fill="none" aria-hidden="true"><path d="M165 94h56v55h61M342 94h-31v55h-29m-117 56h58v-55m119 55h-32v-55" stroke="#8c83ff" strokeWidth="1.5" strokeDasharray="5 5"/><path d="M165 202h-40v-54h40" stroke="#1bbba8" strokeWidth="1.5"/><circle cx="221" cy="94" r="3" fill="#16b8a6"/><circle cx="311" cy="149" r="3" fill="#8c83ff"/><circle cx="221" cy="205" r="3" fill="#16b8a6"/></svg>
          <div className="uml-node uml-node-main"><span>«entity»</span><strong>Proyecto</strong><i /><small>+ nombre: String</small><small>+ diagrama: UML</small></div>
          <div className="uml-node uml-node-user"><span>«actor»</span><strong>Desarrollador</strong><i /><small>+ colaborar()</small></div>
          <div className="uml-node uml-node-diagram"><span>«model»</span><strong>Diagrama</strong><i /><small>+ clases: Elemento[]</small></div>
          <div className="uml-node uml-node-coin"><span>«assistant»</span><strong>COIN</strong><i /><small>Asistencia contextual</small></div>
          <div className="uml-presence"><div className="presence-avatars"><span>AL</span><span>MR</span><span>+</span></div><span><i /> Equipo trabajando</span></div>
        </div>
        <div className="auth-feature-row"><div><span className="feature-icon violet" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><rect x="2.5" y="3" width="6" height="5" rx="1" stroke="currentColor" strokeWidth="1.4"/><rect x="11.5" y="12" width="6" height="5" rx="1" stroke="currentColor" strokeWidth="1.4"/><path d="M8.5 5.5h2a2 2 0 0 1 2 2v4.5" stroke="currentColor" strokeWidth="1.4"/></svg></span><span><strong>Modelado visual</strong><small>Diagramas UML de clases</small></span></div><div><span className="feature-icon teal" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><circle cx="7" cy="7" r="2.5" stroke="currentColor" strokeWidth="1.4"/><circle cx="14" cy="8" r="2" stroke="currentColor" strokeWidth="1.4"/><path d="M2.5 16a4.5 4.5 0 0 1 9 0m1-3a3.5 3.5 0 0 1 5 3" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round"/></svg></span><span><strong>Trabajo en equipo</strong><small>Colaboración sincronizada</small></span></div><div><span className="feature-icon violet" aria-hidden="true"><svg viewBox="0 0 20 20" fill="none"><path d="m10 2 1.7 5.1L17 9l-5.3 1.8L10 16l-1.7-5.2L3 9l5.3-1.9L10 2Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round"/><path d="m15.5 13 .8 2.1 2.2.9-2.2.8-.8 2.2-.9-2.2-2.1-.8 2.1-.9.9-2.1Z" fill="currentColor"/></svg></span><span><strong>Asistencia COIN</strong><small>IA en tu contexto UML</small></span></div></div>
        <p className="auth-story-foot">Un espacio de trabajo para pensar, diseñar y construir.</p>
      </div>
    </section>
    <section className="auth-panel"><div className="auth-form-wrap">
      <div className="auth-mobile-brand"><span className="auth-brand-icon" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M7 8.5h12a5 5 0 0 1 0 10H12m0 0 4-4m-4 4 4 4M25 8v16" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"/><circle cx="25" cy="8" r="2" fill="currentColor"/><circle cx="25" cy="24" r="2" fill="currentColor"/></svg></span><strong>CASE Studio</strong></div>
      <div className="auth-heading"><span className="section-kicker">{registering ? 'ÚNETE A CASE STUDIO' : 'BIENVENIDO DE NUEVO'}</span><h2>{registering ? 'Crea tu cuenta' : 'Inicia sesión'}</h2><p>{registering ? 'Configura tu acceso y empieza a colaborar.' : 'Ingresa a tu espacio de modelado UML.'}</p></div>
      {message && <div className="auth-message" role="alert" aria-live="assertive"><svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" strokeWidth="1.5"/><path d="M10 6v4m0 3h.01" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"/></svg><span>{message}</span></div>}
      <form className="auth-form" onSubmit={submit} noValidate={false}>
        {registering && <label className="auth-field"><span>Nombre</span><span className="auth-input-wrap"><svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="6" r="3" stroke="currentColor" strokeWidth="1.5"/><path d="M4 17a6 6 0 0 1 12 0" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg><input name="name" type="text" placeholder="Tu nombre" autoComplete="name" required maxLength={100} /></span></label>}
        <label className="auth-field"><span>Correo electrónico</span><span className="auth-input-wrap"><svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="2.5" y="4" width="15" height="12" rx="2" stroke="currentColor" strokeWidth="1.5"/><path d="m3.5 5 6.5 5 6.5-5" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round"/></svg><input name="email" type="email" placeholder="nombre@equipo.com" autoComplete="email" inputMode="email" required /></span></label>
        <label className="auth-field"><span>Contraseña</span><span className="auth-input-wrap"><svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="3.5" y="8" width="13" height="9" rx="2" stroke="currentColor" strokeWidth="1.5"/><path d="M6.5 8V5.5a3.5 3.5 0 0 1 7 0V8m-3.5 4v1.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg><input name="password" type={showPassword ? 'text' : 'password'} placeholder={registering ? 'Mínimo 8 caracteres' : 'Ingresa tu contraseña'} autoComplete={registering ? 'new-password' : 'current-password'} required minLength={8} /><button type="button" className="password-toggle" aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}><svg viewBox="0 0 20 20" fill="none" aria-hidden="true">{showPassword ? <><path d="M3 3 17 17M8.6 8.7a2 2 0 0 0 2.7 2.7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/><path d="M6.2 5.2A9.5 9.5 0 0 1 10 4.5c4.4 0 7 5.5 7 5.5a12 12 0 0 1-2.4 3.1M4.2 6.5C2.7 7.8 3 10 3 10s2.6 5.5 7 5.5c.9 0 1.7-.2 2.4-.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></> : <><path d="M2.5 10S5.2 4.5 10 4.5 17.5 10 17.5 10 14.8 15.5 10 15.5 2.5 10 2.5 10Z" stroke="currentColor" strokeWidth="1.5"/><circle cx="10" cy="10" r="2.2" stroke="currentColor" strokeWidth="1.5"/></>}</svg></button></span>{registering && <small className="auth-field-hint">Usa al menos 8 caracteres.</small>}</label>
        <button className="auth-submit" disabled={submitting}>{submitting ? <><span className="auth-spinner" aria-hidden="true" />{registering ? 'Creando cuenta…' : 'Verificando acceso…'}</> : <>{registering ? 'Crear cuenta' : 'Iniciar sesión'}<span aria-hidden="true">→</span></>}</button>
      </form>
      <p className="auth-switch">{registering ? '¿Ya tienes una cuenta?' : '¿Todavía no tienes una cuenta?'} <button type="button" onClick={() => { setRegistering(!registering); onClearError(); setShowPassword(false) }} disabled={submitting}>{registering ? 'Iniciar sesión' : 'Crear una cuenta'}</button></p>
      <p className="auth-security"><svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 2.5 16 5v4.2c0 4-2.5 6.7-6 8.3-3.5-1.6-6-4.3-6-8.3V5l6-2.5Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round"/><path d="m7.5 10 1.7 1.7 3.5-3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/></svg>Tu sesión se protege con autenticación segura.</p>
    </div></section>
  </main>
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
