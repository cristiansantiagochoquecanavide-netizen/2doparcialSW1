// Two independent Edge profiles against the isolated real backend on 18082.
// HTTP, JWT, PostgreSQL, chat/signaling sockets and WebRTC are real; microphones are synthetic.
import { build } from 'esbuild'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'

const apiBase = 'http://localhost:18082'
async function api(route, auth, method = 'GET', body) {
  const r = await fetch(apiBase + '/api' + route, { method, headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: 'Bearer ' + auth.token } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) })
  assert.ok(r.ok, `${method} ${route}: ${r.status} ${r.ok ? '' : await r.text()}`)
  return r.status === 204 ? null : r.json()
}
const suffix = crypto.randomUUID(), password = crypto.randomUUID()
const raul = await api('/auth/register', null, 'POST', { name: 'Raúl', email: `raul-${suffix}@voice.test`, password })
const carlos = await api('/auth/register', null, 'POST', { name: 'Carlos', email: `carlos-${suffix}@voice.test`, password })
const outsider = await api('/auth/register', null, 'POST', { name: 'Externo', email: `outside-${suffix}@voice.test`, password })
const project = await api('/projects', raul, 'POST', { name: 'Presencia de voz aislada', description: 'Validación en dos navegadores' })
await api(`/projects/${project.id}/members`, raul, 'POST', { email: carlos.email, role: 'EDITOR' })
const rooms = await api(`/projects/${project.id}/chat/voice-rooms`, raul)
const room = rooms[0]
const second = await api(`/projects/${project.id}/chat/voice-rooms`, raul, 'POST', { name: 'Sala alternativa' })
for (const route of [`/projects/${project.id}/chat/voice-rooms/${room.id}/participants`, `/projects/${crypto.randomUUID()}/chat/voice-rooms/${room.id}/participants`]) {
  const denied = await fetch(apiBase + '/api' + route, { headers: { Authorization: 'Bearer ' + outsider.token } })
  assert.ok([403, 404].includes(denied.status), `foreign presence was not rejected: ${denied.status}`)
}
const fixture = `
import React from 'react'; import {createRoot} from 'react-dom/client';
import {LiveTeamChatPanel} from './src/uml/LiveTeamChatPanel';
import {AgentSpeechProvider} from './src/uml/AgentSpeechUI';
import './src/styles.css';import './src/uml/team-collaboration.css';
window.testSockets=[];window.testPeers=[];window.mediaCalls=0;
const NativeSocket=window.WebSocket;window.WebSocket=class extends NativeSocket{constructor(...args){super(...args);window.testSockets.push(this)}};
const NativePeer=window.RTCPeerConnection;window.RTCPeerConnection=new Proxy(NativePeer,{construct(Target,args){const pc=new Target(...args);window.testPeers.push(pc);return pc}});
const getMedia=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=(...args)=>{window.mediaCalls++;return getMedia(...args)};
const {auth,project}=window.testContext;
function App(){const [wide,setWide]=React.useState(false);return <AgentSpeechProvider projectId={project.id} userKey={auth.email} sessionKey={auth.token}><div style={{height:'100vh',display:'flex'}}><LiveTeamChatPanel projectId={project.id} diagramId={null} projectName={project.name} diagramName="Prueba de voz" token={auth.token} members={[]} currentUser={{name:auth.name,email:auth.email,role:'EDITOR'}} isWide={wide} onToggleWide={()=>setWide(!wide)} onClose={()=>{}} onSwitchToInspector={()=>{}} /></div></AgentSpeechProvider>}
createRoot(document.getElementById('root')).render(<App/>);
`
const bundle = await build({ stdin: { contents: fixture, loader: 'jsx', resolveDir: process.cwd() }, bundle: true, write: false, outdir: 'voice-fixture', format: 'iife', jsx: 'automatic', define: { 'import.meta.env.VITE_API_BASE_URL': JSON.stringify(apiBase) } })
const js = bundle.outputFiles.find(f => f.path.endsWith('.js')).text, css = bundle.outputFiles.find(f => f.path.endsWith('.css')).text
const server = createServer((req, res) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  const context = { project, auth: req.url.startsWith('/carlos') ? carlos : raul }
  res.end('<!doctype html><html><head><style>html,body,#root{height:100%;margin:0}' + css + '</style></head><body><div id="root"></div><script>window.testContext=' + JSON.stringify(context) + ';' + js.replaceAll('</script', '<\\/script') + '</script></body></html>')
})
await new Promise(resolve => server.listen(15175, '127.0.0.1', resolve))
const browsers = []
async function browser(user) {
  const profile = await mkdtemp(path.join(tmpdir(), 'case-voice-browser-'))
  const process = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--remote-allow-origins=*', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' })
  const item = { process, profile }; browsers.push(item)
  let port
  for (let i = 0; i < 100; i++) { try { port = (await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break } catch { await new Promise(r => setTimeout(r, 100)) } }
  assert.ok(port, 'Edge did not start')
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
  const socket = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl); item.socket = socket
  const callbacks = new Map(); let id = 0
  socket.addEventListener('message', event => { const msg = JSON.parse(String(event.data)); if (msg.id && callbacks.has(msg.id)) { const c = callbacks.get(msg.id); callbacks.delete(msg.id); clearTimeout(c.timer); msg.error ? c.reject(new Error(JSON.stringify(msg.error))) : c.resolve(msg.result) } })
  await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
  const call = (method, params = {}) => new Promise((resolve, reject) => { const key = ++id; const timer = setTimeout(() => { callbacks.delete(key); reject(new Error(user + ': timeout ' + method)) }, 40000); callbacks.set(key, { resolve, reject, timer }); socket.send(JSON.stringify({ id: key, method, params })) })
  const evaluate = async expression => { const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw new Error(user + ': ' + (result.exceptionDetails.exception?.description || result.exceptionDetails.text)); return result.result.value }
  const wait = expression => evaluate(`new Promise((resolve,reject)=>{let n=0;const t=setInterval(()=>{if(${expression}){clearInterval(t);resolve(true)}else if(++n>250){clearInterval(t);reject(new Error('DOM timeout: '+${JSON.stringify(expression)}))}},100)})`)
  const click = text => evaluate(`Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()===${JSON.stringify(text)}).click()`)
  const select = name => evaluate(`Array.from(document.querySelectorAll('.team-channel-link')).find(b=>b.textContent.trim()===${JSON.stringify(name)}).click()`)
  await call('Page.enable'); await call('Runtime.enable'); await call('Emulation.setDeviceMetricsOverride', { width: 1000, height: 850, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: `http://127.0.0.1:15175/${user}` })
  await wait(`document.querySelectorAll('.team-channel-link').length>=4 && document.querySelector('.team-online-pill')?.textContent==='CHAT EN VIVO'`)
  Object.assign(item, { call, evaluate, wait, click, select }); return item
}
const heading = count => `document.querySelector('.voice-participants-heading')?.textContent==='Participantes (${count} / 6)'`
try {
  const a = await browser('raul'), b = await browser('carlos')
  await a.select(room.name); await a.wait(heading(0)); await a.click('Unirse a la sala'); await a.wait(heading(1))
  await b.select(room.name); await b.wait(heading(1))
  assert.ok(await b.evaluate(`document.querySelector('.voice-participant-list').textContent.includes('Raúl')`))
  assert.equal(await b.evaluate('window.mediaCalls'), 0); assert.equal(await b.evaluate('window.testPeers.length'), 0)
  assert.equal(await b.evaluate(`window.testSockets.filter(s=>s.url.endsWith('/voice')).length`), 0)
  await mkdir('../docs/evidencias/voz-presencia', { recursive: true })
  const screenshot = await b.call('Page.captureScreenshot', { format: 'png' }); await writeFile('../docs/evidencias/voz-presencia/01-carlos-observa-raul.png', Buffer.from(screenshot.data, 'base64'))
  await a.click('Salir de la sala'); await b.wait(heading(0))
  await a.click('Unirse a la sala'); await a.wait(heading(1)); await b.wait(heading(1))
  // Reconnect the existing chat socket while observing; recover the authoritative snapshot.
  await b.evaluate(`window.testSockets.find(s=>s.url.endsWith('/chat')&&s.readyState===1).close()`)
  await b.wait(`window.testSockets.filter(s=>s.url.endsWith('/chat')).length>=2 && ${heading(1)}`)
  await b.click('Unirse a la sala'); await a.wait(heading(2)); await b.wait(heading(2))
  await a.wait(`window.testPeers.some(p=>p.connectionState==='connected')`); await b.wait(`window.testPeers.some(p=>p.connectionState==='connected')`)
  await a.click('Activar micrófono'); await b.click('Activar micrófono')
  await b.wait(`document.querySelectorAll('.voice-mic-indicator:not(.is-muted)').length===2`)
  await b.wait(`window.testPeers.some(p=>p.connectionState==='connected')`)
  let received = 0
  for (let i = 0; i < 40 && received === 0; i++) {
    received = await b.evaluate(`(async()=>{let bytes=0;for(const p of window.testPeers){if(p.connectionState==='connected')for(const s of (await p.getStats()).values())if(s.type==='inbound-rtp'&&s.kind==='audio')bytes+=s.bytesReceived||0}return bytes})()`)
    if (!received) await new Promise(r => setTimeout(r, 100))
  }
  assert.ok(received > 0, 'No real WebRTC audio packets received')
  // Reload Carlos: membership disappears, he returns as an observer without microphone access.
  await b.call('Page.reload', { ignoreCache: true }); await b.wait(`document.querySelectorAll('.team-channel-link').length>=4`)
  await b.select(room.name); await b.wait(heading(1)); await a.wait(heading(1)); assert.equal(await b.evaluate('window.mediaCalls'), 0)
  await b.select(second.name); await b.wait(heading(0)); await b.select(room.name); await b.wait(heading(1))
  // Raúl changes rooms: leave the previous roster and appear only in the new room.
  await a.select(second.name); await a.wait(heading(0)); await b.wait(heading(0))
  await a.click('Unirse a la sala'); await a.wait(heading(1)); await b.select(second.name); await b.wait(heading(1))
  // Abrupt signaling disconnect removes presence, then Raúl's existing reconnect flow rejoins.
  await a.evaluate(`window.testSockets.find(s=>s.url.endsWith('/voice')&&s.readyState===1).close()`)
  await b.wait(heading(0)); await b.wait(heading(1))
  await a.click('Salir de la sala'); await b.wait(heading(0))
  console.log('VOICE_PRESENCE_BROWSER_OK: two real Edge profiles, real PostgreSQL/JWT/HTTP/WebSockets, 1→0→1→2, names, observer uses no media/peer/signaling socket, chat reconnect, page reload, room switch, signaling reconnect, isolation. WebRTC audio bytes received=' + received + '. Synthetic microphones; no human listening test.')
} catch (error) {
  for (const b of browsers) if (b.evaluate) console.error('VOICE_BROWSER_DIAGNOSTIC', await b.evaluate('document.body.innerText.slice(-2200)').catch(() => 'unavailable'))
  throw error
} finally {
  for (const b of browsers) {
    if (b.call) await b.call('Browser.close').catch(() => {})
    b.socket?.close(); b.process.kill()
    const absolute = path.resolve(b.profile), prefix = path.resolve(tmpdir()) + path.sep
    if (!absolute.startsWith(prefix)) throw new Error('Unsafe temporary browser path')
    await rm(absolute, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 }).catch(() => {})
  }
  server.close()
}
