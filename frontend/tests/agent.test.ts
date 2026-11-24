import { beforeEach, afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia, disposePinia } from 'pinia'
import { advanceSession, SessionChangedError } from '../src/api/http.ts'
import { useAuthStore } from '../src/stores/auth.ts'
const originalFetch = globalThis.fetch
const saved = new Map<string,string>()
Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { getItem:(key:string)=>saved.get(key)??null, setItem:(key:string,value:string)=>{saved.set(key,value)}, removeItem:(key:string)=>{saved.delete(key)} } })
let pinia = createPinia()
beforeEach(()=>{saved.clear();advanceSession();pinia=createPinia();setActivePinia(pinia)})
afterEach(()=>{globalThis.fetch=originalFetch;disposePinia(pinia)})
const result = { answer:'最终回答',queriedAt:'2026-11-22T10:00:00',toolsUsed:['getTradeRules'],sources:[{id:'payment',text:'支付期限',source:'规则'}],knowledgeSources:[{id:'r1',documentId:'rules',title:'交易规则',path:'docs/rules.md',startLine:1,endLine:2,text:'规则正文',score:0.8}],modelCalls:2,toolCalls:1,totalTokens:32,durationMs:100 }
const event=(name:string,payload:unknown)=>`event: ${name}\r\ndata: ${JSON.stringify(payload)}\r\n\r\n`
function body(text:string,bytewise=false){const bytes=new TextEncoder().encode(text);return new ReadableStream<Uint8Array>({start(c){if(bytewise)for(const byte of bytes)c.enqueue(new Uint8Array([byte]));else c.enqueue(bytes);c.close()}})}
const stream=(text:string)=>new Response(body(text),{headers:{'Content-Type':'text/event-stream;charset=UTF-8'}})
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status})

test('SSE handles Chinese byte fragments CRLF reset and authoritative done',async()=>{
 const {readAgentStream}=await import('../src/utils/agentStream.ts')
 const events:string[]=[]
 const text=event('start',{})+event('delta',{round:1,text:'临时中文'})+event('reset',{round:1,text:''})+event('delta',{round:2,text:'最终'})+event('done',result)
 const final=await readAgentStream(body(text,true),item=>events.push(item.type))
 assert.equal(final.answer,result.answer);assert.deepEqual(events,['start','delta','reset','delta','done'])
})
test('SSE supports multiple data lines and comments',async()=>{
 const {readAgentStream}=await import('../src/utils/agentStream.ts')
 const text=': heartbeat\n'+event('start',{})+'event: delta\ndata: {"round":1,\ndata: "text":"中文"}\n\n'+event('done',result)
 assert.equal((await readAgentStream(body(text),()=>{})).answer,result.answer)
})
test('EOF without done never confirms a partial answer',async()=>{
 const {readAgentStream}=await import('../src/utils/agentStream.ts')
 await assert.rejects(readAgentStream(body(event('start',{})+event('delta',{round:1,text:'草稿'})),()=>{}),/尚未确认/)
})
test('SSE rejects bad JSON unknown events invalid sources and invalid UTF8',async()=>{
 const {readAgentStream}=await import('../src/utils/agentStream.ts')
 for(const text of [event('start',{})+'event: delta\ndata: {bad}\n\n',event('start',{})+event('mystery',{}),event('start',{})+event('done',{...result,knowledgeSources:[{title:'bad'}]})]) await assert.rejects(readAgentStream(body(text),()=>{}))
 const invalid=new ReadableStream<Uint8Array>({start(c){c.enqueue(new Uint8Array([255]));c.close()}})
 await assert.rejects(readAgentStream(invalid,()=>{}),/编码/)
})
test('server error event preserves code and abort cancels pending reader',async()=>{
 const {readAgentStream,AgentStreamError}=await import('../src/utils/agentStream.ts')
 await assert.rejects(readAgentStream(body(event('start',{})+event('error',{code:409,message:'会话已变化'})),()=>{}),error=>error instanceof AgentStreamError&&error.code===409)
 let canceled=false
 const waiting=new ReadableStream<Uint8Array>({cancel(){canceled=true}}),controller=new AbortController()
 const task=readAgentStream(waiting,()=>{},controller.signal);controller.abort()
 await assert.rejects(task,error=>error instanceof DOMException&&error.name==='AbortError');assert.equal(canceled,true)
})
test('stream API sends current CSRF and JSON and invalidates late identity events',async()=>{
 const {streamMessage}=await import('../src/api/agent.ts')
 const calls:{path:string;options:RequestInit}[]=[]
 globalThis.fetch=async(input,options={})=>{calls.push({path:String(input),options});return String(input).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'fresh'}):stream(event('start',{})+event('done',result))}
 await streamMessage('test-id','问题',()=>{},new AbortController().signal)
 assert.equal(calls[1]!.options.credentials,'same-origin');assert.equal(new Headers(calls[1]!.options.headers).get('X-CSRF-TOKEN'),'fresh');assert.deepEqual(JSON.parse(String(calls[1]!.options.body)),{message:'问题'})
 await assert.rejects(streamMessage('test-id','问题',item=>{if(item.type==='start')advanceSession()},new AbortController().signal),SessionChangedError)
})
test('identity change during CSRF prevents stream POST',async()=>{
 const {streamMessage}=await import('../src/api/agent.ts');let calls=0
 globalThis.fetch=async()=>{calls++;advanceSession();return json({headerName:'X-CSRF-TOKEN',token:'old'})}
 await assert.rejects(streamMessage('test-id','问题',()=>{},new AbortController().signal),SessionChangedError);assert.equal(calls,1)
})
async function store(){const {useAgentStore}=await import('../src/stores/agent.ts');const auth=useAuthStore();auth.user={id:61,username:'buyer',role:'USER'};auth.status='authenticated';return{auth,agent:useAgentStore()}}
const id='11111111-1111-4111-8111-111111111111'
const conversation = { conversationId:id, expiresAt:'2026-11-23T10:00:00' }
const history = { ...conversation, generating:false, turns:[{ question:'问题', answer:result.answer,
  sources:result.sources, knowledgeSources:result.knowledgeSources }] }
const conversationPage = { page:1, size:5, hasNext:false, items:[conversation] }
function mockComplete(text=event('start',{})+event('done',result)) {
 globalThis.fetch=async input=>{
   const path = String(input)
   if (path.endsWith('/csrf')) return json({headerName:'X-CSRF-TOKEN',token:'a'})
   if (path.endsWith('/conversations')) return json(conversation,201)
   if (path.includes('/conversations?page=')) return json(conversationPage)
   if (path.endsWith('/messages')) return json(history)
   return stream(text)
 }
}

test('agent applies reset and restores committed history from the server after refresh',async()=>{
 mockComplete(event('start',{})+event('delta',{round:1,text:'应撤回草稿'})+event('reset',{round:1,text:''})+event('delta',{round:2,text:'最终'})+event('done',result))
 const {agent}=await store();await agent.send('问题')
 assert.equal(agent.messages[1]!.text,result.answer);assert.equal(agent.messages[1]!.state,'complete');assert.equal(agent.messages[1]!.result!.knowledgeSources.length,1)
 assert.equal(agent.conversationId,id);assert.equal([...saved.values()].some(value=>value.includes('最终回答')),false)
 disposePinia(pinia);pinia=createPinia();setActivePinia(pinia)
 const refreshed=(await store()).agent
 await refreshed.open()
 assert.equal(refreshed.conversationId,id)
 assert.deepEqual(refreshed.messages.map(message=>message.text),['问题',result.answer])
 assert.equal(refreshed.messages[1]!.result!.knowledgeSources.length,1)
})
test('incomplete answer retains draft without sources and is not automatically resent',async()=>{
 let posts=0;mockComplete(event('start',{})+event('delta',{round:1,text:'未完成正文'}));const fetch=globalThis.fetch
 globalThis.fetch=async(input,options)=>{if(String(input).endsWith('/stream'))posts++;return fetch(input,options)}
 const {agent}=await store();await agent.send('问题');assert.equal(posts,1);assert.equal(agent.messages[1]!.state,'incomplete');assert.equal(agent.messages[1]!.text,'未完成正文');assert.equal(agent.messages[1]!.result,undefined)
})
test('lost done restores the exact saved turn without sending another model request',async()=>{
 let postedTurnId:string|undefined;let posts=0;let historyReads=0
 mockComplete(event('start',{})+event('delta',{round:1,text:'草稿'}))
 const fallback=globalThis.fetch
 globalThis.fetch=async(input,options)=>{
   const path=String(input)
   if(path.endsWith('/stream')){posts++;postedTurnId=JSON.parse(String(options?.body)).clientTurnId}
   if(path.endsWith('/messages')) {
     const older = {...history.turns[0],clientTurnId:'other-id',answer:'相同问题的旧回答'}
     return ++historyReads === 1
       ? json({...history,generating:true,turns:[older]})
       : json({...history,turns:[older,{...history.turns[0],clientTurnId:postedTurnId}]})
   }
   return fallback(input,options)
 }
 const {agent}=await store();await agent.send('问题')
 assert.match(postedTurnId??'',/^[0-9a-f-]{36}$/)
 assert.equal(agent.messages.at(-1)?.state,'complete')
 assert.equal(agent.messages.at(-1)?.text,result.answer)
 assert.equal(posts,1)
 assert.equal(historyReads,2)
})
test('clear only removes local messages after successful DELETE',async()=>{
 mockComplete();const {agent}=await store();await agent.send('问题')
 globalThis.fetch=async input=>String(input).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'a'}):new Response(null,{status:403})
 await agent.clear();assert.equal(agent.messages.length,2)
 globalThis.fetch=async input=>String(input).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'a'}):new Response(null,{status:204})
 await agent.clear();assert.equal(agent.messages.length,0);assert.equal(agent.conversationId,id)
})
test('identity change stops old stream and clears text; same-account history is not shown to another user',async()=>{
 let release!:(value:Response)=>void
 mockComplete();const fetch=globalThis.fetch
 globalThis.fetch=async(input,options)=>String(input).endsWith('/stream')?new Promise<Response>(resolve=>{release=resolve}):fetch(input,options)
 const {auth,agent}=await store();const task=agent.send('私有问题')
 while(!release)await new Promise(resolve=>setImmediate(resolve))
 advanceSession();auth.user={id:62,username:'other',role:'USER'}
 release(stream(event('start',{})+event('done',result)));await task
 assert.equal(agent.messages.length,0);assert.equal(agent.conversationId,null);assert.equal(agent.busy,false)
})


test('a history response after identity change cannot show the previous account messages', async()=>{
 let release!:(value:Response)=>void
 globalThis.fetch=async input=>String(input).includes('/conversations?page=')
   ?json(conversationPage):new Promise<Response>(resolve=>{release=resolve})
 const {auth,agent}=await store()
 const pending=agent.open()
 while(!release)await new Promise(resolve=>setImmediate(resolve))
 advanceSession();auth.user={id:62,username:'other',role:'USER'}
 release(json(history));await pending
 assert.equal(agent.conversationId,null)
 assert.deepEqual(agent.conversations,[])
 assert.deepEqual(agent.messages,[])
})

test('malformed conversation response produces a visible error without rejecting the send action',async()=>{
 globalThis.fetch=async input=>String(input).endsWith('/csrf')?json({headerName:'X-CSRF-TOKEN',token:'a'}):json(null,201)
 const {agent}=await store()
 await assert.doesNotReject(agent.send('问题'))
 assert.match(agent.error,/会话信息/)
 assert.equal(agent.messages[1]!.state,'incomplete');assert.equal(agent.busy,false)
})
