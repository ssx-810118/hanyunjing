// Live provider evaluation. No fixtures are returned as AI responses.
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
const online=process.argv.includes('--online')
const limitArg=process.argv.find(v=>v.startsWith('--limit='))
const limit=limitArg?Number(limitArg.split('=')[1]):3
if(!Number.isInteger(limit)||limit<1||limit>30)throw new Error('--limit must be 1..30')
const endpoint=process.env.RETAIL_EVAL_URL||'http://127.0.0.1:8082'
if(!['127.0.0.1','localhost'].includes(new URL(endpoint).hostname))throw new Error('Evaluation supports this local prototype only')
const definitions=[]
for(const dynasty of ['汉','唐','宋','元','明']){
  definitions.push({name:dynasty+'制预算与M码',message:'想买'+dynasty+'制汉服，预算500元，M码',requirements:{budget:500,size:'M',color:null,quantity:1},expectedDynasty:dynasty,expected:'DONE'})
  definitions.push({name:dynasty+'制低预算无匹配',message:'想买'+dynasty+'制汉服，预算1元',requirements:{budget:1,size:null,color:null,quantity:1},expectedDynasty:dynasty,expected:'NO_MATCH'})
  definitions.push({name:dynasty+'制不存在尺码',message:'想看'+dynasty+'制，按填写尺码筛选',requirements:{budget:1000,size:'XXXXL',color:null,quantity:1},expectedDynasty:dynasty,expected:'NO_MATCH'})
  definitions.push({name:dynasty+'制两件总预算',message:'想买'+dynasty+'制，预算500元，买2件',requirements:{budget:500,size:'M',color:null,quantity:2},expectedDynasty:dynasty,expected:'ANY'})
  definitions.push({name:dynasty+'制颜色精确筛选',message:'想看'+dynasty+'制，按填写颜色筛选',requirements:{budget:1000,size:null,color:'不存在颜色',quantity:1},expectedDynasty:dynasty,expected:'NO_MATCH'})
  definitions.push({name:dynasty+'制礼仪转商家',message:'想买'+dynasty+'制服饰用于正式婚礼，预算500元',requirements:{budget:500,size:null,color:null,quantity:1},expectedDynasty:dynasty,expected:'HANDOFF'})
}
if(!online){console.log(JSON.stringify({source:'团队构造功能用例，非真实企业样本',cases:definitions.length,executed:0,instruction:'node scripts/evaluate-retail.mjs --online --limit=3 会真实调用已配置模型并可能计费'},null,2));process.exit(0)}
const credentials=fs.readFileSync('.local/admin-credentials.txt','utf8').split(/\r?\n/)
const username=credentials.find(v=>v.startsWith('用户名：'))?.split('：').slice(1).join('：')
const password=credentials.find(v=>v.startsWith('密码：'))?.split('：').slice(1).join('：')
if(!username||!password)throw new Error('Local administrator credentials are missing')
let cookie='',csrf=''
async function request(route,body){
  const response=await fetch(endpoint+'/api'+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','X-HYJ-Client':'admin',...(cookie?{Cookie:cookie}:{}),...(csrf?{'X-CSRF-Token':csrf}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(140000)})
  const set=response.headers.getSetCookie();if(set.length)cookie=set.map(v=>v.split(';')[0]).join('; ')
  const envelope=await response.json()
  if(!response.ok||envelope.code!==0)throw new Error('HTTP '+response.status+': '+envelope.message)
  if(envelope.data?.csrfToken)csrf=envelope.data.csrfToken
  return envelope.data
}
const results=[]
await request('/auth/session')
await request('/auth/login',{username,password})
try{
  for(const c of definitions.slice(0,limit)){
    const start=Date.now()
    try{
      const reply=await request('/admin/retail/evaluate',{sessionId:'eval-'+crypto.randomUUID(),message:c.message,requirements:c.requirements})
      const checks=[]
      checks.push({name:'结构化记录',pass:!!reply.workflowId})
      checks.push({name:'期望结果',pass:c.expected==='ANY'||reply.status===c.expected})
      checks.push({name:'朝代',pass:reply.slots.dynasty===c.expectedDynasty})
      for(const rec of reply.recommendations){
        checks.push({name:rec.product.id+'可选规格',pass:rec.eligibleSkus.length>0})
        for(const s of rec.eligibleSkus){
          checks.push({name:s.id+'约束',pass:s.stock>=c.requirements.quantity&&(!c.requirements.size||s.size===c.requirements.size)&&(!c.requirements.color||s.color===c.requirements.color)&&(!c.requirements.budget||s.price*c.requirements.quantity<=c.requirements.budget)})
        }
        checks.push({name:rec.product.id+'史料来源',pass:rec.evidence.some(a=>a.source.includes('https://'))})
      }
      results.push({name:c.name,source:'constructed',success:checks.every(v=>v.pass),elapsedMs:Date.now()-start,status:reply.status,workflowId:reply.workflowId,checks})
    }catch(e){results.push({name:c.name,source:'constructed',success:false,elapsedMs:Date.now()-start,error:String(e.message)})}
    console.log(c.name+': '+(results.at(-1).success?'PASS':'FAIL'))
  }
}finally{await request('/auth/logout',{}).catch(()=>{})}
const report={recordedAt:new Date().toISOString(),kind:'LIVE_PROVIDER_SYNTHETIC_CASES',source:'团队构造功能用例；不代表真实客户效果或人工对照实验',attempted:results.length,passed:results.filter(r=>r.success).length,failed:results.filter(r=>!r.success).length,results}
fs.mkdirSync('output/evaluation',{recursive:true})
const reportPath=path.join('output/evaluation','retail-'+Date.now()+'.json');fs.writeFileSync(reportPath,JSON.stringify(report,null,2))
console.log(reportPath)
if(report.failed)process.exitCode=1
