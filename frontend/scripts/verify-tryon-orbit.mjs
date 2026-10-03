// No provider calls. Exercises real Vue handlers and download behavior with controlled transport.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import {fileURLToPath} from 'node:url'
import {parse,compileScript} from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const writes=[],saves=[],timers=new Map();let timer=0,ready=false,current=null,saveFailure=false
const responseTask=()=>({id:'orbit-1',sourceTaskId:'photo-1',status:'RUNNING',stage:'MODELING',modelUrl:null,error:null,expiresAt:new Date(Date.now()+60000).toISOString()})
async function request(method,url,sid,body){
  if(url==='/tryon/orbit/status')return {ready,message:ready?'已配置':'腾讯混元3D尚未配置'}
  if(method==='POST'){writes.push({url,sid,body});if(url.endsWith('/cancel'))current={...current,status:'CANCELLED'};else current=responseTask()}
  return structuredClone(current)
}
const stub={setup:()=>()=>Vue.h('viewer-stub')}
function load(relative){const file=path.join(root,relative),source=fs.readFileSync(file,'utf8'),code=compileScript(parse(source).descriptor,{id:file,inlineTemplate:true}).content,module={exports:{}};
  const require=id=>{if(id==='vue')return {...Vue,vModelCheckbox:{},defineAsyncComponent:()=>stub};if(id.endsWith('/api'))return {request,errorText:e=>e.message,resourceUrl:(url,sid)=>url+'?sessionId='+sid};if(id.endsWith('/download'))return {saveLocalFile:async(...args)=>{saves.push(args);if(saveFailure)throw new Error('结果已到期')}};throw new Error('Unexpected import '+id)}
  vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{module,exports:module.exports,require,console,AbortController,Date,Error,setTimeout:(fn,delay)=>{const id=++timer;timers.set(id,{fn,delay});return id},clearTimeout:id=>timers.delete(id),setInterval:()=>++timer,clearInterval:()=>{}});return module.exports.default
}
const node=(type,text='')=>({type,text,children:[],props:{},parent:null})
function insert(n,p,anchor){if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1);const index=anchor?p.children.indexOf(anchor):-1;p.children.splice(index<0?p.children.length:index,0,n);n.parent=p}
const renderer=Vue.createRenderer({createElement:type=>node(type),createText:text=>node('text',text),createComment:text=>node('comment',text),setText:(n,t)=>n.text=t,setElementText:(n,t)=>{n.text=t;n.children=[]},parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,patchProp:(n,k,p,v)=>n.props[k]=v,insert,remove:n=>{if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1);n.parent=null},insertStaticContent:(t,p,a)=>{const n=node('static',t);insert(n,p,a);return[n,n]}})
const text=n=>n.text+n.children.map(text).join(''),all=(n,test)=>(test(n)?[n]:[]).concat(n.children.flatMap(c=>all(c,test)))
const button=(tree,label)=>all(tree,n=>n.type==='button'&&text(n)===label)[0]
async function settle(){for(let i=0;i<20;i++){await Promise.resolve();await Vue.nextTick()}}
function mount(file,props){const tree=node('root'),app=renderer.createApp(load(file),props);app.config.errorHandler=e=>{console.error(e.stack)};app.mount(tree);return{tree,app}}
const orbit=mount('src/components/TryOnOrbit.vue',{taskId:'photo-1',sessionId:'my-session'});await settle()
assert.match(text(orbit.tree),/尚未配置/);assert.equal(button(orbit.tree,'生成3D环绕').props.disabled,true)
all(orbit.tree,n=>n.type==='input')[0].props['onUpdate:modelValue'](true);await settle();assert.equal(button(orbit.tree,'生成3D环绕').props.disabled,true)
ready=true;await button(orbit.tree,'刷新状态').props.onClick();await settle();assert.equal(button(orbit.tree,'生成3D环绕').props.disabled,false)
await button(orbit.tree,'生成3D环绕').props.onClick();await settle();assert.equal(writes.length,1);assert.deepEqual(JSON.parse(JSON.stringify(writes[0])),{url:'/tryon/tasks/photo-1/orbit',sid:'my-session',body:{authorized:true,retryOf:null}})
assert.match(text(orbit.tree),/正在生成模型与纹理/);assert.equal(button(orbit.tree,'生成3D环绕'),undefined)
await button(orbit.tree,'取消本次环绕生成').props.onClick();await settle();assert.match(text(orbit.tree),/已取消本站任务/)
await button(orbit.tree,'重新生成3D环绕').props.onClick();await settle();assert.equal(writes.at(-1).body.retryOf,'orbit-1')
current={...current,status:'DONE',stage:'COMPLETE',modelUrl:'/api/tryon/orbit/tasks/orbit-1/model'}
for(const[id,item]of[...timers]){timers.delete(id);await item.fn()}await settle()
assert.equal(all(orbit.tree,n=>n.type==='viewer-stub').length,1)
await button(orbit.tree,'保存3D模型到本地').props.onClick();assert.equal(saves.at(-1)[0],'/api/tryon/orbit/tasks/orbit-1/model?sessionId=my-session');assert.equal(saves.at(-1)[2],'model/gltf-binary')
orbit.app.unmount();assert.equal(timers.size,0)
const image=mount('src/components/SaveTryOnImage.vue',{url:'/api/tryon/result/photo-1?sessionId=my-session',taskId:'photo-1'});await settle()
saveFailure=true;await button(image.tree,'保存图片到本地').props.onClick();await settle();assert.match(text(image.tree),/结果已到期/);assert.doesNotMatch(text(image.tree),/已发起下载/)
saveFailure=false;await button(image.tree,'保存图片到本地').props.onClick();await settle();assert.match(text(image.tree),/已发起下载/);assert.equal(saves.at(-1)[2],'image/png');image.app.unmount()

// The actual download helper must reject HTML/errors without triggering a download.
const source=fs.readFileSync(path.join(root,'src/download.ts'),'utf8'),module={exports:{}};let clicked=0,revoked=0,status=200,mime='image/png';const urls=[]
class LocalURL extends URL{static createObjectURL(){return'blob:download'}static revokeObjectURL(){revoked++}}
vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{module,exports:module.exports,URL:LocalURL,window:{location:{origin:'http://localhost:5173'}},fetch:async(url,options)=>{urls.push({url:url.toString(),options});return{ok:status===200,status,headers:{get:()=>mime},blob:async()=>new Blob(['image'])}},document:{body:{appendChild:()=>{}},createElement:()=>({style:{},click(){clicked++},remove(){}})},setTimeout:fn=>fn(),Blob})
const download=module.exports.saveLocalFile
await download('/api/result?id=1','image.png','image/png');assert.equal(clicked,1);assert.equal(revoked,1);assert.equal(urls[0].options.credentials,'same-origin');assert.equal(urls[0].options.cache,'no-store')
status=401;await assert.rejects(()=>download('/api/result','x','image/png'),/登录/);status=404;await assert.rejects(()=>download('/api/result','x','image/png'),/到期/);status=200;mime='text/html';await assert.rejects(()=>download('/api/result','x','image/png'),/格式/);await assert.rejects(()=>download('https://outside.invalid/model','x','image/png'),/无效/);assert.equal(clicked,1)

// Parse a real minimal GLB using the same Three.js loader, without GPU/browser claims.
const gltf={asset:{version:'2.0'},scene:0,scenes:[{nodes:[0]}],nodes:[{mesh:0}],meshes:[{primitives:[{attributes:{POSITION:0}}]}],buffers:[{byteLength:36}],bufferViews:[{buffer:0,byteLength:36}],accessors:[{bufferView:0,componentType:5126,count:3,type:'VEC3',min:[0,0,0],max:[1,1,0]}]}
let json=JSON.stringify(gltf);while(Buffer.byteLength(json)%4)json+=' ';const bytes=Buffer.alloc(28+Buffer.byteLength(json)+36);bytes.writeUInt32LE(0x46546c67,0);bytes.writeUInt32LE(2,4);bytes.writeUInt32LE(bytes.length,8);bytes.writeUInt32LE(Buffer.byteLength(json),12);bytes.writeUInt32LE(0x4e4f534a,16);bytes.write(json,20);const bin=20+Buffer.byteLength(json);bytes.writeUInt32LE(36,bin);bytes.writeUInt32LE(0x004e4942,bin+4);[0,0,0,1,0,0,0,1,0].forEach((v,i)=>bytes.writeFloatLE(v,bin+8+i*4))
const parsed=await new GLTFLoader().parseAsync(bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.length),'');let meshes=0;parsed.scene.traverse(o=>{if(o.isMesh){meshes++;o.geometry.dispose();o.material.dispose()}});assert.equal(meshes,1)
console.log('PASS: image downloads reject errors; 3D consent/configuration gates, one submit, polling, cancel, explicit retry, model download and Three.js GLB parsing work. No real provider or browser visual test.')
