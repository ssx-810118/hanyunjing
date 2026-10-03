<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import * as THREE from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
const props=defineProps<{url:string}>()
const host=ref<HTMLDivElement>(),loading=ref(true),error=ref(''),auto=ref(false),front=ref(0),ready=ref(false)
let renderer:THREE.WebGLRenderer|undefined,controls:OrbitControls|undefined,camera:THREE.PerspectiveCamera|undefined,scene:THREE.Scene|undefined,observer:ResizeObserver|undefined,frame=0,disposed=false
let distance=4,targetHeight=1
const abort=new AbortController()
function disposeModel(object:THREE.Object3D){object.traverse(o=>{const mesh=o as THREE.Mesh;if(mesh.geometry)mesh.geometry.dispose();if(mesh.material){for(const m of Array.isArray(mesh.material)?mesh.material:[mesh.material]){for(const value of Object.values(m))if(value instanceof THREE.Texture){value.dispose();if(value.image instanceof ImageBitmap)value.image.close()}m.dispose()}}})}
function view(angle:number){if(!camera||!controls)return;auto.value=false;controls.autoRotate=false;const theta=front.value+angle;camera.position.set(Math.sin(theta)*distance,targetHeight+.05,Math.cos(theta)*distance);controls.target.set(0,targetHeight,0);controls.update()}
function setFront(){if(!camera||!controls)return;front.value=Math.atan2(camera.position.x-controls.target.x,camera.position.z-controls.target.z);view(0)}
function toggle(){auto.value=!auto.value;if(controls)controls.autoRotate=auto.value}
function resize(){if(!host.value||!camera||!renderer)return;const w=host.value.clientWidth,h=host.value.clientHeight;camera.aspect=w/Math.max(1,h);camera.updateProjectionMatrix();renderer.setSize(w,h)}
onMounted(async()=>{
  try{
    if(!host.value)return
    renderer=new THREE.WebGLRenderer({antialias:true,alpha:true});renderer.setPixelRatio(Math.min(devicePixelRatio,2));renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.15
    host.value.appendChild(renderer.domElement);renderer.domElement.setAttribute('aria-label','3D汉服模型，拖动旋转，滚轮缩放');renderer.domElement.tabIndex=0
    scene=new THREE.Scene();camera=new THREE.PerspectiveCamera(36,1,.01,100);controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;controls.enablePan=false;controls.minDistance=1.5;controls.maxDistance=9;controls.autoRotateSpeed=1;controls.minPolarAngle=.05;controls.maxPolarAngle=Math.PI-.05
    scene.add(new THREE.HemisphereLight(0xffffff,0x85735e,2.7));const key=new THREE.DirectionalLight(0xfff3de,3);key.position.set(3,5,5);scene.add(key);const fill=new THREE.DirectionalLight(0xffffff,2);fill.position.set(-4,2,-4);scene.add(fill)
    observer=new ResizeObserver(resize);observer.observe(host.value);resize()
    const response=await fetch(props.url,{credentials:'same-origin',cache:'no-store',signal:abort.signal});if(!response.ok)throw new Error('模型已到期、被删除或登录失效，请刷新结果。')
    const data=await response.arrayBuffer();if(disposed)return
    // Server accepts only GLB with embedded buffers and textures; loader allows local blob textures only.
    const manager=new THREE.LoadingManager();manager.setURLModifier(url=>{if(url.startsWith('blob:')||url.startsWith('data:'))return url;throw new Error('模型包含外部资源，无法展示。')})
    const gltf=await new GLTFLoader(manager).parseAsync(data,'');if(disposed){disposeModel(gltf.scene);return}
    const box=new THREE.Box3().setFromObject(gltf.scene),size=box.getSize(new THREE.Vector3()),center=box.getCenter(new THREE.Vector3());if(!Number.isFinite(size.length())||size.y<=0)throw new Error('模型没有可展示的几何内容。')
    const scale=2/Math.max(size.x,size.y,size.z);gltf.scene.scale.setScalar(scale);gltf.scene.position.set(-center.x*scale,-box.min.y*scale,-center.z*scale);scene.add(gltf.scene)
    targetHeight=size.y*scale/2;distance=Math.max(4.5,1.3/Math.tan(THREE.MathUtils.degToRad(camera.fov/2))/Math.min(1,camera.aspect));controls.maxDistance=Math.max(9,distance*2);view(0);ready.value=true
    const animate=()=>{if(disposed)return;controls?.update();if(scene&&camera)renderer?.render(scene,camera);frame=requestAnimationFrame(animate)};animate()
  }catch(e){if(!disposed)error.value=e instanceof Error?e.message:'无法初始化3D，请检查浏览器WebGL支持。'}finally{if(!disposed)loading.value=false}
})
onBeforeUnmount(()=>{disposed=true;abort.abort();cancelAnimationFrame(frame);observer?.disconnect();controls?.dispose();if(scene)disposeModel(scene);renderer?.dispose();renderer?.forceContextLoss();renderer?.domElement.remove()})
</script>
<template>
  <div class="orbit-viewer">
    <div ref="host" class="orbit-canvas"><p v-if="loading" class="canvas-message" role="status">正在加载三维模型…</p><p v-if="error" class="canvas-message error" role="alert">{{ error }}</p></div>
    <div v-if="ready" class="orbit-controls" role="group" aria-label="三维模型视角"><button @click="view(0)">正面</button><button @click="view(Math.PI/2)">左面</button><button @click="view(Math.PI)">背面</button><button @click="view(-Math.PI/2)">右面</button><button :aria-pressed="auto" @click="toggle">{{ auto?'暂停环绕':'自动环绕' }}</button><button @click="view(0)">全貌 / 重置</button><button @click="setFront">设当前为正面</button></div>
    <p class="caption">拖动旋转 · 滚轮或双指缩放。若初始朝向不对，转至人物正面后点击“设当前为正面”。</p>
  </div>
</template>
<style scoped>.orbit-canvas{height:520px;position:relative;border:1px solid var(--line);border-radius:18px;background:radial-gradient(ellipse at center,#fffcf5,#e7dfd1);overflow:hidden}.orbit-canvas :deep(canvas){display:block;touch-action:none}.canvas-message{position:absolute;inset:45% 15px auto;text-align:center;font-size:13px;pointer-events:none}.orbit-controls{display:flex;flex-wrap:wrap;gap:8px;margin:15px 0}.orbit-controls button{font-size:12px;padding:9px 14px}@media(max-width:600px){.orbit-canvas{height:400px}}</style>
