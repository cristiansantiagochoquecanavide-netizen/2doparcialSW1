// Real React Flow DOM, explicit HTTP/WebSocket fixtures, no provider calls.
import { build } from 'esbuild'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'
const fixture = `
import React from 'react';import {createRoot} from 'react-dom/client';import {UmlCanvas} from './src/uml/UmlCanvas';import './src/styles.css';
window.calls=[];window.sockets=[];window.errors=[];
const element=n=>({id:n.id,diagramaId:'diagram',nombre:n.name,tipoElemento:n.type,posicionX:n.x,posicionY:n.y,propiedadesJson:JSON.stringify(n.properties)});
const nodes=[{id:'venta',name:'Venta',x:60,y:70},{id:'producto',name:'Producto',x:510,y:70},{id:'cliente',name:'Cliente',x:60,y:380},{id:'pago',name:'Pago',x:510,y:380}].map(n=>({...n,type:'CLASE',properties:{attributes:[{id:n.id+'-id',name:'id',type:'Long',visibility:'-'}],methods:[{id:n.id+'-method',name:'guardar',returnType:'void',parameters:'',visibility:'+'}]}}));
const relation=(id,source,target,name)=>({id,diagramaId:'diagram',elementoOrigen:source,elementoDestino:target,tipoRelacion:'ASOCIACION',nombreRelacion:name,multiplicidadOrigen:'1',multiplicidadDestino:'0..*'});
window.model={diagram:{id:'diagram',projectId:'project',nombre:'Ventas · 4 clases y 3 relaciones',descripcion:'',revision:2,estado:'BORRADOR'},elements:nodes.map(element),relations:[relation('compra','cliente','venta','realiza'),relation('detalle','venta','producto','incluye'),relation('pagos','venta','pago','registra')]};
window.proposal=null;window.active=null;
window.signal=()=>{for(const ws of window.sockets)if(ws.url.includes('/ws/diagrams/'))ws.onmessage?.({data:JSON.stringify({type:'UML_PROPOSAL_CHANGED',diagramId:'diagram'})})};
window.propose=(kind,privateScope=false)=>{let change,description;
if(kind==='relation'){change={entity:'RELATION',kind:'CREATE',id:'garantia',before:null,after:{id:'garantia',source:'producto',destination:'pago',type:'ASOCIACION',name:'garantia',sourceMultiplicity:'1',targetMultiplicity:'0..1'}};description='Relacionar Producto y Pago';}
else if(kind==='delete-relation'){const rel=window.model.relations.find(r=>r.id==='detalle');change={entity:'RELATION',kind:'DELETE',id:rel.id,after:null,before:{id:rel.id,source:rel.elementoOrigen,destination:rel.elementoDestino,type:rel.tipoRelacion,name:rel.nombreRelacion,sourceMultiplicity:rel.multiplicidadOrigen,targetMultiplicity:rel.multiplicidadDestino}};description='Eliminar relación detalle';}
else {const target=kind==='producto'?'producto':'venta',e=window.model.elements.find(e=>e.id===target);const before={id:e.id,name:e.nombre,type:e.tipoElemento,x:e.posicionX,y:e.posicionY,properties:JSON.parse(e.propiedadesJson)},after=structuredClone(before);
if(kind==='method'){after.properties.methods[0].returnType='Boolean';description='Modificar guardar(): Boolean';}
else if(kind==='remove'){after.properties.attributes=[];description='Eliminar atributo id de Venta';}
else {const name=kind==='producto'?'clase':'metodoPago';after.properties.attributes.push({id:target+'-'+name,name,type:'String',visibility:'-'});description='Agregar '+name+': String a '+before.name;}
change={entity:'CLASS',kind:'UPDATE',id:target,before,after};}
window.proposal={id:'proposal-'+kind,projectId:'project',diagramId:'diagram',scope:privateScope?'PRIVATE':'GROUP',requesterName:'Ana',description,status:'PENDIENTE',baseRevision:window.model.diagram.revision,currentRevision:window.model.diagram.revision,preview:{changes:[change],warnings:[{mensaje:'Esta validación no debe repetirse en la vista previa'}],destructive:kind==='remove'||kind==='delete-relation',clarification:null}};window.active=privateScope?null:window.proposal;if(privateScope)window.dispatchEvent(new CustomEvent('case:canvas-private-proposal',{detail:{projectId:'project',diagramId:'diagram',proposalId:window.proposal.id}}));else window.signal();};
class FakeSocket{static OPEN=1;readyState=1;constructor(url){this.url=url;window.sockets.push(this);setTimeout(()=>this.onopen?.(),20)}close(){this.readyState=3}send(){}}window.WebSocket=FakeSocket;
window.fetch=async(url,options={})=>{const method=options.method||'GET';window.calls.push({url,method,body:options.body});let data;
if(url.endsWith('/ai/proposals/canvas'))data={active:window.active,pending:window.active?[window.active]:[]};
else if(method==='GET'&&url.includes('/ai/proposals/proposal-'))data=window.proposal;
else if(url.endsWith('/reject')){window.proposal.status='RECHAZADA';window.active=null;if(window.proposal.scope!=='PRIVATE')window.signal();data=window.proposal;}
else if(url.endsWith('/apply')){if(window.conflict){window.proposal.status='CONFLICTO';window.signal();return {ok:false,status:409,json:async()=>({message:'La revisión cambió'})}}for(const change of window.proposal.preview.changes){if(change.entity==='CLASS')window.model.elements=window.model.elements.map(e=>e.id===change.id?element(change.after):e);else if(change.kind==='DELETE')window.model.relations=window.model.relations.filter(r=>r.id!==change.id);else{const n=change.after;window.model.relations.push({...relation(n.id,n.source,n.destination,n.name),multiplicidadOrigen:n.sourceMultiplicity,multiplicidadDestino:n.targetMultiplicity});}}window.model.diagram.revision++;window.proposal.status='APLICADA';window.active=null;if(window.proposal.scope!=='PRIVATE')window.signal();data=window.proposal;}
else if(url.endsWith('/diagrams/default'))data=window.model.diagram;
else if(url.endsWith('/diagrams/diagram')){await new Promise(resolve=>setTimeout(resolve,250));data=window.model;}
else if(url.includes('/chat/channels'))data=[];
else data=[];
return {ok:true,status:200,headers:{get:()=>null},json:async()=>structuredClone(data)};};
window.partialFrames=[];setInterval(()=>{const count=document.querySelectorAll('.react-flow__node').length;if(count>0&&count<4)window.partialFrames.push(count)},10);
window.propose('producto');
createRoot(document.getElementById('root')).render(<UmlCanvas project={{id:'project',name:'Prueba visual ACODI · datos de prueba',role:'EDITOR'}} auth={{token:'fixture',name:'Ana',email:'ana@test'}} onError={e=>window.errors.push(String(e))}/>);
`
const bundle = await build({ stdin: { contents: fixture, loader: 'jsx', resolveDir: process.cwd() }, bundle: true, write: false, outdir: 'fixture', format: 'iife', define: { 'import.meta.env.VITE_API_BASE_URL': '""' }, jsx: 'automatic' })
const js = bundle.outputFiles.find(f => f.path.endsWith('.js')).text, css = bundle.outputFiles.find(f => f.path.endsWith('.css')).text
const server = createServer((req, res) => { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end('<html><head><style>html,body,#root{height:100%;margin:0}' + css + '</style></head><body><div id="root"></div><script>' + js.replaceAll('</script', '<\\/script') + '</script></body></html>') })
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const profile = await mkdtemp(path.join(tmpdir(), 'acodi-canvas-'))
const browser = spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', ['--headless=new', '--disable-gpu', '--no-first-run', '--remote-debugging-port=0', '--remote-allow-origins=*', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' })
let socket, id = 0; const callbacks = new Map(), errors = []
const call = (method, params = {}) => new Promise((resolve, reject) => { const token = ++id, timer = setTimeout(() => reject(new Error('CDP timeout ' + method)), 15000); callbacks.set(token, { resolve: value => { clearTimeout(timer); resolve(value) }, reject }); socket.send(JSON.stringify({ id: token, method, params })) })
const evaluate = async expression => { const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value }
const waitFor = expression => evaluate('new Promise((resolve,reject)=>{let n=0;const t=setInterval(()=>{if(' + expression + '){clearInterval(t);resolve(true)}else if(++n>100){clearInterval(t);reject(new Error("DOM timeout"))}},100)})')
const button = label => evaluate(`Array.from(document.querySelectorAll('.acodi-canvas-bar button')).find(b=>b.textContent==='${label}').click()`)
const pause = () => evaluate('new Promise(resolve=>setTimeout(resolve,450))')
const positions = () => evaluate('Array.from(document.querySelectorAll(".react-flow__node")).map(n=>({id:n.dataset.id,transform:n.style.transform})).sort((a,b)=>a.id.localeCompare(b.id))')
const counts = async (edgeCount=3) => {
  assert.equal(await evaluate('document.querySelectorAll(".react-flow__node").length'),4)
  assert.equal(await evaluate('document.querySelectorAll(".react-flow__edge").length'),edgeCount)
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".react-flow__edge-path")).every(p=>p.getAttribute("d")?.length>10)'),true)
}
const screenshot = async name => {
  await evaluate('document.querySelector(".react-flow__controls-fitview").click()');await pause()
  await evaluate('document.querySelector(".react-flow__controls-zoomout").click()');await pause()
  await evaluate('document.querySelector(".react-flow__controls-zoomout").click()');await pause()
  const dir=path.resolve('../docs/evidencias/acodi-preview');await mkdir(dir,{recursive:true})
  const capture=await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})
  await writeFile(path.join(dir,name),Buffer.from(capture.data,'base64'))
}
try {
  let port
  for (let i = 0; i < 100; i++) { try { port = (await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break } catch { await new Promise(resolve => setTimeout(resolve, 200)) } }
  assert.ok(port)
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json()
  socket = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl)
  socket.addEventListener('message', event => { const message = JSON.parse(event.data); if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.exception?.description); const cb = callbacks.get(message.id); if (cb) { callbacks.delete(message.id); message.error ? cb.reject(new Error(message.error.message)) : cb.resolve(message.result) } })
  await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }))
  await call('Runtime.enable'); await call('Page.enable'); await call('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false })
  await call('Page.navigate', { url: 'http://127.0.0.1:' + server.address().port })
  await waitFor('document.querySelectorAll(".react-flow__node").length===4&&document.querySelectorAll(".react-flow__edge").length===3&&document.querySelector(".acodi-member-preview")');await pause();await counts()
  assert.deepEqual(await evaluate('window.partialFrames'),[])
  await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview")');await counts()
  const original=await evaluate('JSON.stringify(window.model)'),originalPositions=await positions()
  await evaluate('window.propose("producto")');await waitFor('document.querySelector("[data-id=producto] .acodi-member-preview")?.textContent.includes("clase")');await pause();await counts()
  assert.deepEqual(await positions(),originalPositions)
  assert.equal(await evaluate('JSON.stringify(window.model)'),original)
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".acodi-member-preview")).color'), 'rgb(21, 128, 61)')
  assert.notEqual(await evaluate('getComputedStyle(document.querySelector("[data-id=producto] .uml-node-title")).color'),'rgb(21, 128, 61)')
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".acodi-canvas-bar")).backgroundColor'),'rgb(255, 255, 255)')
  assert.equal(await evaluate('document.querySelectorAll(".acodi-canvas-details").length'),0)
  assert.equal(await evaluate('localStorage.getItem("case.pending_sync.diagram")'), null)
  assert.equal(await evaluate('document.querySelectorAll("[role=dialog]").length'), 0)
  await screenshot('01-producto-clase-verde.png')
  const oldViewport=await evaluate('document.querySelector(".react-flow__viewport").style.transform')
  await button('Ver cambio');await pause()
  assert.notEqual(await evaluate('document.querySelector(".react-flow__viewport").style.transform'),oldViewport)
  const center=await evaluate('(()=>{const a=document.querySelector("[data-id=producto]").getBoundingClientRect(),b=document.querySelector(".react-flow").getBoundingClientRect();return {x:Math.abs(a.x+a.width/2-b.x-b.width/2),y:Math.abs(a.y+a.height/2-b.y-b.height/2)}})()')
  assert.ok(center.x<20&&center.y<20,JSON.stringify(center));await counts()
  await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview")');await counts()
  assert.equal(await evaluate('JSON.stringify(window.model)'),original);assert.deepEqual(await positions(),originalPositions)
  assert.equal(await evaluate('document.querySelector("[data-id=producto]").textContent.includes("clase:")'),false)
  await evaluate('window.propose("venta")');await waitFor('document.querySelector("[data-id=venta] .acodi-member-preview")?.textContent.includes("metodoPago")');await counts();await screenshot('02-venta-metodoPago-verde.png')
  await button('Aprobar');await waitFor('!document.querySelector(".acodi-preview")&&document.querySelector("[data-id=venta]").textContent.includes("metodoPago")');await counts()
  assert.equal(await evaluate('window.model.diagram.revision'), 3)
  assert.deepEqual(await positions(),originalPositions)
  const approved=JSON.parse(await evaluate('JSON.stringify(window.model)')),baseline=JSON.parse(original)
  for(const e of baseline.elements.filter(e=>e.id!=='venta'))assert.deepEqual(approved.elements.find(a=>a.id===e.id),e)
  assert.deepEqual(approved.relations,baseline.relations)
  await evaluate('window.propose("relation")');await waitFor('document.querySelector(".acodi-preview-edge")');await counts(4);await screenshot('03-relacion-verde-conserva-existentes.png')
  assert.deepEqual(await positions(),originalPositions)
  await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview-edge")');await counts()
  assert.deepEqual(JSON.parse(await evaluate('JSON.stringify(window.model)')),approved)
  await evaluate('window.propose("relation")');await waitFor('document.querySelector(".acodi-preview-edge")');await button('Aprobar');await waitFor('!document.querySelector(".acodi-preview-edge")');await counts(4)
  assert.deepEqual(JSON.parse(await evaluate('JSON.stringify(window.model.relations.slice(0,3))')),baseline.relations)
  await evaluate('window.propose("method")');await waitFor('document.querySelector(".acodi-member-preview")?.textContent.includes("Boolean")');await counts(4)
  await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview")')
  await evaluate('window.propose("remove")');await waitFor('document.querySelector("[data-id=venta] .acodi-deleted")');await counts(4);await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview")')
  const beforeDelete=await evaluate('JSON.stringify(window.model)')
  await evaluate('window.propose("delete-relation")');await waitFor('document.querySelector("[data-id=detalle].acodi-preview-edge")');await counts(4)
  assert.equal(await evaluate('JSON.stringify(window.model)'),beforeDelete)
  await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview-edge")');await counts(4);assert.equal(await evaluate('JSON.stringify(window.model)'),beforeDelete)
  await evaluate('window.propose("delete-relation")');await waitFor('document.querySelector("[data-id=detalle].acodi-preview-edge")')
  await evaluate('document.querySelector(".acodi-canvas-bar input[type=checkbox]").click()');await button('Aprobar');await waitFor('!document.querySelector("[data-id=detalle].react-flow__edge")');await counts(3)
  assert.deepEqual(await positions(),originalPositions)
  await evaluate('window.propose("method",true)');await waitFor('document.querySelector(".acodi-canvas-bar").textContent.includes("solo tú")&&document.querySelector(".acodi-member-preview")')
  await counts(3);assert.equal(await evaluate('window.calls.filter(c=>c.method==="POST"&&c.url.endsWith("/canvas")).length'),0)
  await button('Rechazar');await waitFor('!document.querySelector(".acodi-preview")');await counts(3)
  await evaluate('window.propose("method");window.conflict=true'); await waitFor('document.querySelector(".acodi-member-preview")')
  await button('Aprobar'); await waitFor('document.querySelector(".acodi-canvas-bar").textContent.includes("HTTP 409")')
  assert.equal(await evaluate('window.model.diagram.revision'), 5)
  assert.equal(await evaluate('localStorage.getItem("case.pending_sync.diagram")'), null)
  assert.equal(await evaluate('window.calls.filter(c=>c.method!=="GET"&&!c.url.endsWith("/apply")&&!c.url.endsWith("/reject")).length'), 0)
  assert.deepEqual(errors, []); assert.deepEqual(await evaluate('window.errors'), [])
  console.log('CANVAS_PREVIEW_BROWSER_OK: diagrama completo; atributos y relaciones; eliminación de relación con rechazo/aprobación; revisión privada sin publicación compartida; centrado; conflicto 409; sin persistencia provisional; 3 capturas.')
} catch (error) { console.error('CANVAS_PREVIEW_DIAGNOSTIC', errors, await evaluate('document.body.innerText.slice(0,1400)').catch(() => '')); throw error }
finally {
  if (socket) { try { await call('Browser.close') } catch {} socket.close() }
  browser.kill(); server.close()
  if (!path.resolve(profile).startsWith(path.resolve(tmpdir()) + path.sep)) throw new Error('Ruta temporal fuera del destino autorizado')
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 }).catch(() => {})
}
