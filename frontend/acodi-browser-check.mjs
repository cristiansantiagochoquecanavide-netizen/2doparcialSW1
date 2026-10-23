// Isolated DOM checks with the real panel; all HTTP, WebSocket and speech data are explicit fixtures.
import { build } from 'esbuild'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'

const fixture = `
import React from 'react'; import {createRoot} from 'react-dom/client';
import {LiveTeamChatPanel} from './src/uml/LiveTeamChatPanel';
import {AgentSpeechProvider,AgentSpeechControls} from './src/uml/AgentSpeechUI';
import './src/styles.css'; import './src/uml/team-collaboration.css';
const sockets=[];window.sent=0;window.spoken=[];
class FakeSocket{static OPEN=1;readyState=1;constructor(){sockets.push(this);setTimeout(()=>this.onopen?.(),30)}close(){this.readyState=3}send(){}}
window.WebSocket=FakeSocket;
Object.defineProperty(window,'speechSynthesis',{value:{getVoices:()=>[{lang:'es-BO',localService:true}],addEventListener(){},removeEventListener(){},resume(){},cancel(){},speak(u){window.spoken.push(u.text);u.onstart?.();setTimeout(()=>u.onend?.(),100)}}});
window.SpeechSynthesisUtterance=class{constructor(text){this.text=text}};
const question={id:'question',channelId:'general',senderUserId:'ana',senderName:'Ana',senderEmail:'ana@test',senderRole:'EDITOR',authorType:'USER',clientMessageId:'key',content:'',createdAt:new Date().toISOString(),replyToMessageId:null};
window.fetch=async(url,options={})=>{let data=[];
if(url.endsWith('/chat/channels'))data=[{id:'general',slug:'general',name:'general'},{id:'design',slug:'diseno-uml',name:'diseño-uml'}];
else if(url.endsWith('/ai/conversations'))data=[{id:'private-chat',scope:'PRIVATE',title:'Privado de Ana',diagramId:'diagram',updatedAt:new Date().toISOString(),archivedAt:null},{id:'group-chat',scope:'GROUP',title:'Historial del equipo',diagramId:'diagram',updatedAt:new Date().toISOString(),archivedAt:null}];
else if(url.includes('/ai/conversations/private-chat/messages')){const human={id:'private-question',clientMessageId:'private-key',senderType:'USER',senderName:'Ana',recipientName:null,content:'Consulta privada',status:'COMPLETED',errorCode:null,createdAt:new Date().toISOString(),replyToMessageId:null};const answer={...human,id:'private-answer',senderType:'AGENT',senderName:'ACODI',recipientName:'Ana',content:'SECRETO SOLO ANA',replyToMessageId:human.id};data=options.method==='POST'?{question:human,answer}:{messages:[human,answer],hasMore:false,nextCursor:null};}
else if(url.includes('/messages')&&options.method==='POST'){window.sent++;const input=JSON.parse(options.body);data={...question,content:input.content,clientMessageId:input.clientMessageId,acodi:{status:'PENDIENTE',attempts:0}};
setTimeout(()=>{const answer={...question,id:'answer',senderUserId:null,senderName:'ACODI',senderEmail:null,senderRole:'AGENTE',authorType:'AGENT',content:'Ana, revisa la multiplicidad de Venta.',replyToMessageId:'question'};for(const ws of sockets)ws.onmessage?.({data:JSON.stringify({type:'CHAT_MESSAGE',projectId:'project',channelId:'general',message:answer})})},800);
}else if(url.includes('/messages'))data={messages:[],hasMore:false,nextCursor:null};
return {ok:true,status:200,json:async()=>data};};
createRoot(document.getElementById('root')).render(<AgentSpeechProvider projectId="project" userKey="ana@test" sessionKey="fixture"><div style={{height:'100vh',display:'flex',flexDirection:'column'}}><div style={{flex:1,minHeight:0}}><LiveTeamChatPanel projectId="project" diagramId="diagram" projectName="Fixture ACODI" diagramName="Ventas" token="fixture" members={[]} currentUser={{name:'Ana',email:'ana@test',role:'EDITOR'}} isWide={false} onToggleWide={()=>{}} onClose={()=>{}} onSwitchToInspector={()=>{}} /></div><AgentSpeechControls/></div></AgentSpeechProvider>);
`
const bundle=await build({stdin:{contents:fixture,loader:'jsx',resolveDir:process.cwd()},bundle:true,write:false,outdir:'fixture',format:'iife',define:{'import.meta.env.VITE_API_BASE_URL':'""'},jsx:'automatic'})
const js=bundle.outputFiles.find(file=>file.path.endsWith('.js')).text,css=bundle.outputFiles.find(file=>file.path.endsWith('.css')).text
const server=createServer((req,res)=>{res.setHeader('Content-Type','text/html; charset=utf-8');res.end('<!doctype html><html><head><style>html,body,#root{height:100%;margin:0}'+css+'</style></head><body><div id="root"></div><script>'+js.replaceAll('</script','<\\/script')+'</script></body></html>')})
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const url='http://127.0.0.1:'+server.address().port
const profile=await mkdtemp(path.join(tmpdir(),'acodi-ui-'))
const browser=spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--remote-allow-origins=*','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:'ignore'})
let socket;const callbacks=new Map();let id=0;const browserErrors=[]
const call=(method,params={})=>new Promise((resolve,reject)=>{const token=++id;const timer=setTimeout(()=>{callbacks.delete(token);reject(new Error('CDP timeout '+method))},15000);callbacks.set(token,{resolve:value=>{clearTimeout(timer);resolve(value)},reject});socket.send(JSON.stringify({id:token,method,params}))})
const evaluate=async(expression)=>{const result=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.text);return result.result.value}
const waitFor=expression=>evaluate('new Promise((resolve,reject)=>{let count=0;const timer=setInterval(()=>{if('+expression+'){clearInterval(timer);resolve(true)}else if(++count>100){clearInterval(timer);reject(new Error("DOM timeout"))}},100)})')
try{
  let debugPort
  for(let attempt=0;attempt<100;attempt++){try{debugPort=(await readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0];break}catch{await new Promise(resolve=>setTimeout(resolve,200))}}
  assert.ok(debugPort,'Edge headless no pudo iniciarse')
  const pages=await(await fetch('http://127.0.0.1:'+debugPort+'/json/list')).json();socket=new WebSocket(pages.find(page=>page.type==='page').webSocketDebuggerUrl)
  socket.addEventListener('message',event=>{const message=JSON.parse(event.data);if(message.method==='Runtime.exceptionThrown')browserErrors.push(message.params.exceptionDetails.exception?.description||message.params.exceptionDetails.text);const pending=callbacks.get(message.id);if(pending){callbacks.delete(message.id);message.error?pending.reject(new Error(message.error.message)):pending.resolve(message.result)}})
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true})})
  await call('Page.enable');await call('Runtime.enable');await call('Page.navigate',{url});await waitFor('document.querySelector(".team-input-bar textarea")&&!document.querySelector(".team-input-bar textarea").disabled')
  for(const width of [480,340,280]){
    await call('Emulation.setDeviceMetricsOverride',{width,height:760,deviceScaleFactor:1,mobile:false})
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))')
    const bounds=await evaluate('Array.from(document.querySelectorAll(".team-input-bar textarea,.team-acodi-invoke,.team-send-btn")).map(el=>{const r=el.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width}})')
    assert.equal(bounds.length,3);for(const rect of bounds){assert.ok(rect.width>0);assert.ok(rect.left>=-1&&rect.right<=width+1,JSON.stringify({width,rect}));assert.ok(rect.top>=0&&rect.bottom<=760,JSON.stringify({width,rect}))}
  }
  await evaluate('(()=>{const t=document.querySelector(".team-input-bar textarea");Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,"value").set.call(t,"Hola revisa");t.dispatchEvent(new Event("input",{bubbles:true}));t.setSelectionRange(5,5)})()')
  await evaluate('new Promise(resolve=>requestAnimationFrame(resolve))');await evaluate('document.querySelector(".team-acodi-invoke").click()');await waitFor('document.querySelector(".team-input-bar textarea").value==="Hola @ACODI revisa"')
  assert.equal(await evaluate('document.activeElement===document.querySelector(".team-input-bar textarea")'),true)
  await evaluate('document.querySelector(".team-input-form").requestSubmit()');await waitFor('document.querySelector("[role=status]")?.textContent.includes("cola")||document.querySelector(".team-msg-body [role=status]")')
  await waitFor('document.querySelector(".team-msg-acodi")');assert.equal(await evaluate('window.sent'),1)
  assert.ok((await evaluate('document.querySelector(".team-msg-acodi").textContent')).includes('Ana, revisa'))
  await evaluate('Array.from(document.querySelectorAll(".team-msg-acodi button")).find(el=>el.textContent==="Escuchar").click()');await waitFor('window.spoken.length===1')
  assert.ok((await evaluate('window.spoken[0]')).includes('Ana, revisa'))
  const type=async(text)=>{await evaluate('(()=>{const t=document.querySelector(".team-input-bar textarea");Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,"value").set.call(t,'+JSON.stringify(text)+');t.dispatchEvent(new Event("input",{bubbles:true}))})()');await evaluate('new Promise(resolve=>requestAnimationFrame(resolve))')}
  await type('Borrador general')
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes("diseño-uml")).click()')
  await waitFor('document.querySelector(".team-input-bar textarea").value===""')
  await type('Borrador diseño')
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.trim()==="general").click()')
  await waitFor('document.querySelector(".team-input-bar textarea").value==="Borrador general"')
  assert.ok((await evaluate('document.querySelector(".team-msg-acodi").textContent')).includes('Ana, revisa'))
  assert.equal(await evaluate('document.querySelectorAll(".team-subtabs button").length'),2)
  assert.equal(await evaluate('document.querySelector(".team-subtabs").textContent.includes("Herramientas")'),false)
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes("Historiales y chats privados")).click()')
  await waitFor('document.querySelector(".team-chat-area:not([hidden]) select[aria-label=\\"Ámbito de conversación\\"]")')
  await evaluate('(()=>{const select=document.querySelector("select[aria-label=\\"Ámbito de conversación\\"]");select.value="PRIVATE";select.dispatchEvent(new Event("change",{bubbles:true}))})()')
  await waitFor('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")')
  const privateBounds=await evaluate('Array.from(document.querySelector(".team-chat-area:not([hidden])").querySelectorAll("select,.ai-compose textarea,.ai-compose button")).map(e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,width:r.width,bottom:r.bottom}})')
  for(const rect of privateBounds){assert.ok(rect.width>0&&rect.left>=-1&&rect.right<=281&&rect.bottom<=761,JSON.stringify(rect))}
  await evaluate('(()=>{const t=document.querySelector(".ai-compose textarea");Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,"value").set.call(t,"BORRADOR PRIVADO");t.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await evaluate('new Promise(resolve=>requestAnimationFrame(resolve))')
  await evaluate('(()=>{const s=document.querySelector("select[aria-label=\\"Ámbito de conversación\\"]");s.value="GROUP";s.dispatchEvent(new Event("change",{bubbles:true}))})()')
  await waitFor('!document.querySelector(".ai-compose textarea").disabled&&document.querySelector(".ai-compose textarea").value===""')
  assert.equal(await evaluate('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")'),false)
  await evaluate('(()=>{const s=document.querySelector("select[aria-label=\\"Ámbito de conversación\\"]");s.value="PRIVATE";s.dispatchEvent(new Event("change",{bubbles:true}))})()')
  await waitFor('document.querySelector(".ai-compose textarea").value==="BORRADOR PRIVADO"&&document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")')
  assert.equal(await evaluate('document.querySelector(".team-chat-area:not([hidden])").querySelectorAll(".ai-proposals,.ai-proactive-panel,.ai-memory-editor").length'),0)
  await evaluate('Array.from(document.querySelectorAll(".team-subtabs button")).find(el=>el.textContent.includes("Participantes")).click()')
  await evaluate('Array.from(document.querySelectorAll(".team-subtabs button")).find(el=>el.textContent.includes("Canales y Chat")).click()')
  await waitFor('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")')
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.trim()==="general").click()')
  await waitFor('document.querySelector(".team-chat-area:not([hidden]) .team-input-bar textarea")')
  assert.equal(await evaluate('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")'),false)
  await evaluate('Array.from(document.querySelectorAll(".team-msg-acodi button")).find(el=>el.textContent==="Escuchar").click()')
  await waitFor('window.spoken.length===2')
  console.log('ACODI_BROWSER_OK: dos pestañas; navegación privada aislada; controles públicos/privados visibles; mención en cursor; envío único; respuesta WebSocket; Escuchar; borradores e historial conservados.')
}catch(error){console.error('ACODI_BROWSER_DIAGNOSTIC',browserErrors,await evaluate('document.body.innerText.slice(0,1600)').catch(()=>''));throw error}finally{
  if(socket){try{await call('Browser.close')}catch{}socket.close()}
  browser.kill();server.close();const absolute=path.resolve(profile),base=path.resolve(tmpdir())+path.sep
  if(!absolute.startsWith(base))throw new Error('Directorio temporal fuera del destino permitido')
  await rm(absolute,{recursive:true,force:true,maxRetries:5,retryDelay:300}).catch(()=>{})
}
