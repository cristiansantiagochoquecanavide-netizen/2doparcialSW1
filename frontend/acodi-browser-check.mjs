// Isolated DOM checks with the real panel; all HTTP, WebSocket and speech data are explicit fixtures.
import { build } from 'esbuild'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'

const fixture = `
import React from 'react'; import {createRoot} from 'react-dom/client';
import {LiveTeamChatPanel} from './src/uml/LiveTeamChatPanel';
import {AgentSpeechProvider,AgentSpeechControls} from './src/uml/AgentSpeechUI';
import './src/styles.css'; import './src/uml/team-collaboration.css';
const sockets=[];window.sent=0;window.spoken=[];window.fixtureSockets=sockets;
class FakeSocket{static OPEN=1;readyState=1;constructor(){sockets.push(this);setTimeout(()=>this.onopen?.(),30)}close(){this.readyState=3}send(){}}
window.WebSocket=FakeSocket;
Object.defineProperty(window,'speechSynthesis',{value:{getVoices:()=>[{lang:'es-BO',localService:true}],addEventListener(){},removeEventListener(){},resume(){},cancel(){},speak(u){window.spoken.push(u.text);u.onstart?.();setTimeout(()=>u.onend?.(),100)}}});
window.SpeechSynthesisUtterance=class{constructor(text){this.text=text}};
const question={id:'question',channelId:'general',senderUserId:'ana',senderName:'Ana',senderEmail:'ana@test',senderRole:'EDITOR',authorType:'USER',clientMessageId:'key',content:'',createdAt:new Date().toISOString(),replyToMessageId:null};
const textChannels=[{id:'general',slug:'general',name:'general'},{id:'design',slug:'diseno-uml',name:'diseño-uml'}],voiceRooms=[{id:'voice',slug:'voz-diseno',name:'Sala de diseño'}];
const conversations=[{id:'private-chat',scope:'PRIVATE',title:'Privado de Ana',diagramId:'diagram',updatedAt:new Date().toISOString(),archivedAt:null},{id:'group-chat',scope:'GROUP',title:'Historial del equipo',diagramId:'diagram',updatedAt:new Date().toISOString(),archivedAt:null}];
window.fixtureChannels=textChannels;
window.fixtureRequests=[];window.confirm=()=>true;window.prompt=()=>window.nextName||'Nueva conversación';
window.fetch=async(url,options={})=>{let data=[];window.fixtureRequests.push({url,method:options.method||'GET'});
const collection=url.includes('/ai/conversations')?conversations:url.includes('/chat/voice-rooms')?voiceRooms:textChannels;
if(options.method==='PATCH'){const id=url.split('/').pop();const value=JSON.parse(options.body);Object.assign(collection.find(item=>item.id===id),value);data={...collection.find(item=>item.id===id)};}
else if(options.method==='DELETE'){const id=url.split('/').pop().split('?')[0];const index=collection.findIndex(item=>item.id===id);if(index>=0)collection.splice(index,1);return {ok:true,status:204};}
else if(options.method==='POST'&&!url.includes('/messages')){const body=JSON.parse(options.body);const value={id:'new-'+crypto.randomUUID(),slug:'custom',...body,updatedAt:new Date().toISOString()};collection.push(value);data={...value};}
else if(url.endsWith('/chat/channels'))data=textChannels.map(item=>({...item}));
else if(url.endsWith('/chat/voice-rooms'))data=voiceRooms.map(item=>({...item}));
else if(url.endsWith('/ai/conversations'))data=conversations.map(item=>({...item}));
else if(url.includes('/ai/conversations/private-chat/messages')){const human={id:'private-question',clientMessageId:'private-key',senderType:'USER',senderName:'Ana',recipientName:null,content:'Consulta privada',status:'COMPLETED',errorCode:null,createdAt:new Date().toISOString(),replyToMessageId:null};const answer={...human,id:'private-answer',senderType:'AGENT',senderName:'ACODI',recipientName:'Ana',content:'SECRETO SOLO ANA',replyToMessageId:human.id};data=options.method==='POST'?{question:human,answer}:{messages:[human,answer],hasMore:false,nextCursor:null};}
else if(url.includes('/messages')&&options.method==='POST'){window.sent++;const input=JSON.parse(options.body);data={...question,content:input.content,clientMessageId:input.clientMessageId,acodi:{status:'PENDIENTE',attempts:0}};
setTimeout(()=>{const answer={...question,id:'answer',senderUserId:null,senderName:'ACODI',senderEmail:null,senderRole:'AGENTE',authorType:'AGENT',content:'Ana, revisa la multiplicidad de Venta.',replyToMessageId:'question'};for(const ws of sockets)ws.onmessage?.({data:JSON.stringify({type:'CHAT_MESSAGE',projectId:'project',channelId:'general',message:answer})})},800);
}else if(url.includes('/messages'))data={messages:[],hasMore:false,nextCursor:null};
return {ok:true,status:200,json:async()=>data};};
function FixturePanel(){const [wide,setWide]=React.useState(false);return <LiveTeamChatPanel projectId="project" diagramId="diagram" projectName="Fixture ACODI" diagramName="Ventas" token="fixture" members={[]} currentUser={{name:'Ana',email:'ana@test',role:'EDITOR'}} isWide={wide} onToggleWide={()=>setWide(!wide)} onClose={()=>{}} onSwitchToInspector={()=>{}} />}
createRoot(document.getElementById('root')).render(<AgentSpeechProvider projectId="project" userKey="ana@test" sessionKey="fixture"><div style={{height:'100vh',display:'flex',flexDirection:'column'}}><div style={{flex:1,minHeight:0}}><FixturePanel /></div><AgentSpeechControls/></div></AgentSpeechProvider>);
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
  const socketsBeforeResize=await evaluate('window.fixtureSockets.length')
  await evaluate('document.querySelector(".team-panel-actions button").click()');await waitFor('document.querySelector(".team-panel-wide")')
  assert.equal(await evaluate('document.querySelector(".team-input-bar textarea").value'),'Borrador general')
  assert.equal(await evaluate('window.fixtureSockets.length'),socketsBeforeResize)
  await evaluate('document.querySelector(".team-panel-actions button").click()');await waitFor('!document.querySelector(".team-panel-wide")')
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes("diseño-uml")).click()')
  await waitFor('document.querySelector(".team-input-bar textarea").value===""')
  await type('Borrador diseño')
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.trim()==="general").click()')
  await waitFor('document.querySelector(".team-input-bar textarea").value==="Borrador general"')
  assert.ok((await evaluate('document.querySelector(".team-msg-acodi").textContent')).includes('Ana, revisa'))
  assert.equal(await evaluate('document.querySelectorAll(".team-subtabs button").length'),2)
  assert.equal(await evaluate('document.querySelector(".team-subtabs").textContent.includes("Herramientas")'),false)
  assert.equal(await evaluate('document.querySelectorAll(".team-nav-section").length'),3)
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes("Privado de Ana")).click()')
  await waitFor('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")')
  const privateBounds=await evaluate('Array.from(document.querySelector(".team-chat-area:not([hidden])").querySelectorAll("select,.ai-compose textarea,.ai-compose button")).map(e=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,width:r.width,bottom:r.bottom}})')
  for(const rect of privateBounds){assert.ok(rect.width>0&&rect.left>=-1&&rect.right<=281&&rect.bottom<=761,JSON.stringify(rect))}
  await evaluate('(()=>{const t=document.querySelector(".ai-compose textarea");Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,"value").set.call(t,"BORRADOR PRIVADO");t.dispatchEvent(new Event("input",{bubbles:true}))})()')
  await evaluate('new Promise(resolve=>requestAnimationFrame(resolve))')
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes("Historial del equipo")).click()')
  await waitFor('!document.querySelector(".ai-compose textarea").disabled&&document.querySelector(".ai-compose textarea").value===""')
  assert.equal(await evaluate('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")'),false)
  await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes("Privado de Ana")).click()')
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
  const navClick=async name=>evaluate('Array.from(document.querySelectorAll(".team-channel-link")).find(el=>el.textContent.includes('+JSON.stringify(name)+')).click()')
  const optionsClick=async name=>evaluate('document.querySelector('+JSON.stringify('[aria-label="Opciones de '+name+'"]')+').click()')
  const menuClick=async name=>evaluate('Array.from(document.querySelectorAll(".team-nav-menu button")).find(el=>el.textContent==='+JSON.stringify(name)+').click()')
  for(const [label,name] of [['Crear canal de texto','canal-nuevo'],['Crear sala de voz','Sala nueva'],['Crear chat privado','New Chat']]){
    await evaluate('window.nextName='+JSON.stringify(name))
    await evaluate('document.querySelector('+JSON.stringify('[aria-label="'+label+'"]')+').click()')
    await waitFor('Array.from(document.querySelectorAll(".team-channel-link")).some(el=>el.textContent.includes('+JSON.stringify(name)+'))')
    if(label==='Crear chat privado'){
      assert.equal(await evaluate('window.fixtureRequests.filter(r=>r.method==="POST"&&r.url.endsWith("/ai/conversations")).length'),1)
      await waitFor('!document.querySelector(".ai-compose textarea").disabled')
      assert.equal(await evaluate('document.querySelector(".ai-compose textarea").value'), '')
      assert.equal(await evaluate('document.querySelector(".team-chat-area:not([hidden])").textContent.includes("SECRETO SOLO ANA")'),false)
      await evaluate('(()=>{const t=document.querySelector(".ai-compose textarea");Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,"value").set.call(t,"BORRADOR DOS");t.dispatchEvent(new Event("input",{bubbles:true}))})()')
      await navClick('Privado de Ana');await waitFor('document.querySelector(".ai-compose textarea").value==="BORRADOR PRIVADO"')
      await navClick('New Chat');await waitFor('document.querySelector(".ai-compose textarea").value==="BORRADOR DOS"')
    }
    await evaluate('window.nextName='+JSON.stringify(name+' editado'))
    await optionsClick(name)
    assert.equal(await evaluate('getComputedStyle(document.querySelector(".team-nav-menu button")).color'), 'rgb(30, 41, 59)')
    await menuClick('Cambiar nombre')
    await waitFor('Array.from(document.querySelectorAll(".team-channel-link")).some(el=>el.textContent.includes('+JSON.stringify(name+' editado')+'))')
    await evaluate('window.confirm=()=>false');await optionsClick(name+' editado');await menuClick(label==='Crear chat privado'?'Eliminar chat':'Eliminar')
    assert.equal(await evaluate('Array.from(document.querySelectorAll(".team-channel-link")).some(el=>el.textContent.includes('+JSON.stringify(name+' editado')+'))'),true)
    await evaluate('window.confirm=()=>true');await optionsClick(name+' editado');await menuClick(label==='Crear chat privado'?'Eliminar chat':'Eliminar')
    await waitFor('!Array.from(document.querySelectorAll(".team-channel-link")).some(el=>el.textContent.includes('+JSON.stringify(name+' editado')+'))')
  }
  await optionsClick('general');assert.equal(await evaluate('Array.from(document.querySelectorAll(".team-nav-menu button")).find(el=>el.textContent==="Eliminar").disabled'),true);await optionsClick('general')
  await navClick('general')
  await evaluate('(()=>{for(let i=0;i<55;i++){const message={id:"long-"+i,channelId:"general",senderName:"ACODI",authorType:"AGENT",senderRole:"AGENTE",content:"Mensaje extenso ".repeat(160)+" FINAL_"+i,createdAt:new Date(Date.now()+i).toISOString()};for(const ws of window.fixtureSockets)if(ws.readyState===1)ws.onmessage?.({data:JSON.stringify({type:"CHAT_MESSAGE",projectId:"project",channelId:"general",message})})}})()')
  await waitFor('document.querySelectorAll(".team-expand-message").length===55')
  const bounds=await evaluate('(()=>{const el=document.querySelector(".team-send-btn");const r=el.getBoundingClientRect();return {top:r.top,bottom:r.bottom,width:r.width}})()')
  assert.ok(bounds.width>0&&bounds.top>=0&&bounds.bottom<=760,JSON.stringify(bounds))
  await evaluate('(()=>{const el=document.querySelector(".team-messages-scroll");el.scrollTop=120;el.dispatchEvent(new Event("scroll"));window.scrollBefore=el.scrollTop})()')
  await evaluate('(()=>{const message={id:"latest",channelId:"general",senderName:"Ana",authorType:"USER",senderRole:"EDITOR",content:"No mover el scroll",createdAt:new Date(Date.now()+100).toISOString()};for(const ws of window.fixtureSockets)if(ws.readyState===1)ws.onmessage?.({data:JSON.stringify({type:"CHAT_MESSAGE",projectId:"project",channelId:"general",message})})})()')
  await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))')
  assert.equal(await evaluate('document.querySelector(".team-messages-scroll").scrollTop'),await evaluate('window.scrollBefore'))
  await evaluate('document.querySelector(".team-expand-message").click()');assert.equal(await evaluate('document.querySelector(".team-expand-message").parentElement.textContent.includes("FINAL_")'),true)
  await evaluate('document.querySelector(".team-messages-scroll").scrollTop=0')
  await call('Emulation.setDeviceMetricsOverride',{width:680,height:760,deviceScaleFactor:1,mobile:false})
  const screenshot=await call('Page.captureScreenshot',{format:'png'})
  await mkdir('../docs/evidencias/acodi-canales',{recursive:true});await writeFile('../docs/evidencias/acodi-canales/01-navegacion-unificada.png',Buffer.from(screenshot.data,'base64'))
  await evaluate('(()=>{for(let i=0;i<35;i++)window.fixtureChannels.push({id:"extra-"+i,slug:"extra-"+i,name:"Canal "+i});for(const ws of window.fixtureSockets)if(ws.readyState===1)ws.onmessage?.({data:JSON.stringify({type:"PROJECT_CHANNELS_CHANGED",projectId:"project"})})})()')
  await waitFor('document.querySelectorAll(".team-nav-row").length>35')
  assert.equal(await evaluate('(()=>{const nav=document.querySelector(".team-channel-sidebar");return nav.scrollHeight>nav.clientHeight})()'),true)
  const messageScroll=await evaluate('document.querySelector(".team-messages-scroll").scrollTop')
  await evaluate('document.querySelector(".team-channel-sidebar").scrollTop=100')
  assert.equal(await evaluate('document.querySelector(".team-messages-scroll").scrollTop'),messageScroll)
  await evaluate('window.fixtureSockets[0].close();window.fixtureSockets[0].onclose({code:1006})')
  await waitFor('window.fixtureSockets.length>=2&&document.querySelector(".team-online-pill").textContent==="CHAT EN VIVO"')
  assert.equal(await evaluate('document.querySelector(".team-input-bar textarea").value'),'Borrador general')
  assert.equal(await evaluate('window.spoken.length'),2)
  assert.equal(await evaluate('window.sent'),1)
  assert.equal(await evaluate('document.querySelectorAll(".team-chat-area:not([hidden]) select").length'),0)
  assert.equal(browserErrors.length,0,JSON.stringify(browserErrors))
  console.log('ACODI_BROWSER_OK: tres secciones, CRUD texto/voz/privado, confirmación y cancelación, #general protegido, historial y borradores aislados, scroll estable con 55 respuestas extensas, compositor a 280/340/480px, @ACODI y voz conservados. HTTP/WebSocket simulados.')
}catch(error){console.error('ACODI_BROWSER_DIAGNOSTIC',browserErrors,await evaluate('document.body.innerText.slice(0,1600)').catch(()=>''));throw error}finally{
  if(socket){try{await call('Browser.close')}catch{}socket.close()}
  browser.kill();server.close();const absolute=path.resolve(profile),base=path.resolve(tmpdir())+path.sep
  if(!absolute.startsWith(base))throw new Error('Directorio temporal fuera del destino permitido')
  await rm(absolute,{recursive:true,force:true,maxRetries:5,retryDelay:300}).catch(()=>{})
}
