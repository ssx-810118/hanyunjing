<script setup lang="ts">
import { computed,ref,onMounted,onBeforeUnmount,defineAsyncComponent } from 'vue'
import { request,resourceUrl,errorText } from '../api'
import { saveLocalFile } from '../download'
const OrbitViewer=defineAsyncComponent(()=>import('./OrbitViewer.vue'))
const props=defineProps<{taskId:string;sessionId:string}>()
interface OrbitTask {id:string;sourceTaskId:string;status:string;stage:string;modelUrl:string|null;error:string|null;expiresAt:string}
const service=ref<{ready:boolean;message:string}>(),task=ref<OrbitTask|null>(null),consent=ref(false),busy=ref(false),error=ref(''),notice=ref(''),saving=ref(false),now=ref(Date.now())
const active=computed(()=>!!task.value&&['QUEUED','RUNNING'].includes(task.value.status))
const expired=computed(()=>!!task.value&&new Date(task.value.expiresAt).getTime()<=now.value)
const stageNames:Record<string,string>={WAITING:'等待生成',PREPARING:'正在准备换装图片',SUBMITTING:'正在提交腾讯混元3D',REMOTE_QUEUED:'腾讯云排队中',MODELING:'正在生成模型与纹理',DOWNLOADING:'正在获取三维模型',COMPLETE:'三维模型已生成'}
let disposed=false,version=0,timer:ReturnType<typeof setTimeout>|undefined
const clock=setInterval(()=>{now.value=Date.now()},1000),downloadController=new AbortController()
async function load(){
  error.value=''
  try{const [s,t]=await Promise.all([request<{ready:boolean;message:string}>('GET','/tryon/orbit/status'),request<OrbitTask|null>('GET','/tryon/tasks/'+props.taskId+'/orbit',props.sessionId)]);if(disposed)return;service.value=s;task.value=t;schedule()}
  catch(e){if(!disposed)error.value=errorText(e)}
}
function schedule(){clearTimeout(timer);if(!disposed&&active.value&&!expired.value)timer=setTimeout(poll,4000)}
async function poll(){if(!task.value||disposed||expired.value)return;const currentVersion=version;error.value='';try{const t=await request<OrbitTask>('GET','/tryon/orbit/tasks/'+task.value.id,props.sessionId);if(disposed||version!==currentVersion)return;task.value=t;schedule()}catch(e){if(!disposed&&version===currentVersion)error.value=errorText(e)}}
async function generate(){
  if(busy.value||!consent.value||!service.value?.ready)return
  busy.value=true;error.value='';notice.value='';version++
  try{const t=await request<OrbitTask>('POST','/tryon/tasks/'+props.taskId+'/orbit',props.sessionId,{authorized:true,retryOf:task.value&&['FAILED','CANCELLED'].includes(task.value.status)?task.value.id:null});if(disposed)return;task.value=t;schedule()}
  catch(e){if(!disposed)error.value=errorText(e)}finally{busy.value=false}
}
async function cancel(){if(!task.value||busy.value)return;busy.value=true;version++;clearTimeout(timer);try{await request('POST','/tryon/orbit/tasks/'+task.value.id+'/cancel',props.sessionId,{});await poll()}catch(e){if(!disposed)error.value=errorText(e)}finally{busy.value=false}}
async function download(){if(!task.value?.modelUrl||saving.value)return;saving.value=true;error.value='';try{await saveLocalFile(resourceUrl(task.value.modelUrl,props.sessionId),'汉韵镜-3D-'+task.value.id+'.glb','model/gltf-binary',downloadController.signal);if(!disposed)notice.value='已发起模型下载，可在支持GLB的软件中打开。'}catch(e){if(!disposed)error.value=e instanceof Error?e.message:'模型下载失败'}finally{saving.value=false}}
onMounted(load)
onBeforeUnmount(()=>{disposed=true;clearTimeout(timer);clearInterval(clock);downloadController.abort()})
</script>
<template>
  <section class="panel tryon-orbit">
    <p class="eyebrow">第三步 · 转身见全貌</p><h2>3D 环绕试衣</h2>
    <p class="intro">用这张换装效果生成带纹理的三维模型，拖动查看正面、背面与左右两侧。</p>
    <p class="caption">单张照片未展示的部位由 AI 推测补全，侧后方衣纹和人物可能有偏差；不能作为真实服装结构或合身证明。</p>
    <template v-if="task?.status==='DONE'&&task.modelUrl&&!expired">
      <OrbitViewer :key="task.id" :url="resourceUrl(task.modelUrl,sessionId)"/>
      <button :disabled="saving" @click="download">{{ saving?'正在保存…':'保存3D模型到本地' }}</button>
    </template>
    <div v-else-if="active&&!expired" class="orbit-progress" role="status"><span class="loading-mark">◇</span><h3>{{ stageNames[task?.stage||'']||'正在处理' }}</h3><p>只跟踪本次任务，不会重复提交生成。</p><button :disabled="busy" @click="cancel">取消本次环绕生成</button></div>
    <template v-else>
      <p v-if="expired" class="notice">本次模型已到期，请重新上传照片开始试穿。</p>
      <p v-else-if="task?.status==='FAILED'" class="error" role="alert">{{ task.error }}</p>
      <p v-else-if="task?.status==='CANCELLED'" class="caption">已取消本站任务。腾讯云已受理的请求仍可能继续处理或计费。</p>
      <p v-if="!service?.ready" class="notice">{{ service?.message||'正在读取3D服务状态…' }}<button @click="load">刷新状态</button></p>
      <label class="check orbit-consent"><input v-model="consent" type="checkbox" :disabled="busy||expired">我同意将本次换装效果图发送至腾讯云混元3D，生成三维预览。</label>
      <button class="primary" :disabled="busy||!consent||!service?.ready||expired" @click="generate">{{ busy?'正在提交…':task?'重新生成3D环绕':'生成3D环绕' }}</button>
      <p class="caption">此操作会发起一次独立的腾讯云生成请求，可能产生费用。图片与3D预览同时到期；删除原人像会清理本站关联结果。</p>
    </template>
    <p v-if="error" class="error" role="alert">{{ error }}<button v-if="active" @click="poll">只刷新任务状态</button></p><p v-if="notice" class="caption" role="status">{{ notice }}</p>
  </section>
</template>
<style scoped>.tryon-orbit{margin-top:24px;padding:26px}.tryon-orbit h2{font-size:27px;margin:8px 0 16px}.intro{font-size:14px;line-height:1.9}.orbit-consent{align-items:flex-start;margin:20px 0;font-size:13px;line-height:1.8}.orbit-consent input{margin-top:5px}.orbit-progress{text-align:center;padding:40px 20px;border:1px dashed var(--gold);border-radius:16px;margin:22px 0}.orbit-progress h3{font-size:23px}.orbit-progress p{font-size:13px}.orbit-progress>.loading-mark{font-size:35px;color:var(--gold)}.notice button,.error button{font-size:12px;margin-left:10px}</style>
