package com.hanyunjing;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Persistent, account-scoped retail work. No raw prompts, body data or portraits. */
@Service
public class RetailWorkflow {
    private final CommerceStore store;
    public RetailWorkflow(CommerceStore store){this.store=store;}
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedRuns(){
        store.jdbc.update("UPDATE agent_run SET status='INTERRUPTED',summary='服务重启中断了本次接待，请重新问衣或请求商家协助',finished_at=? WHERE status='RUNNING'",Instant.now().toString());
    }
    public record Step(String kind,String detail,long elapsedMs) {}
    public record Confirm(String skuId) {}
    public record Resolve(String reply) {}
    String start(String account,String session,RetailRules.Requirements r) {
        return start(account,session,r,false);
    }
    String start(String account,String session,RetailRules.Requirements r,boolean evaluation) {
        String id=UUID.randomUUID().toString();
        store.jdbc.update("INSERT INTO agent_run(id,account_id,session_key,status,budget,size_label,color_label,quantity,created_at,origin) VALUES (?,?,?,'RUNNING',?,?,?,?,?,?)",
            id,account,session,r.budget(),r.size(),r.color(),r.quantity(),Instant.now().toString(),evaluation?"EVALUATION":"LIVE");
        return id;
    }
    void finish(String id,Models.AgentReply reply,long elapsed,int calls,int tools,Long input,Long output,List<Step> steps) {
        store.tx(()->{
            store.jdbc.update("UPDATE agent_run SET status=?,summary=?,scene=?,dynasty=?,finished_at=?,elapsed_ms=?,model_calls=?,tool_calls=?,input_tokens=?,output_tokens=? WHERE id=? AND status='RUNNING'",
                reply.status(),reply.message(),reply.slots().scene(),reply.slots().dynasty(),Instant.now().toString(),elapsed,calls,tools,input,output,id);
            for(var rec:reply.recommendations()) {
                for(var sku:rec.eligibleSkus())store.jdbc.update("INSERT INTO agent_candidate VALUES (?,?,?)",id,sku.id(),sku.price());
                for(var article:rec.evidence())store.jdbc.update("INSERT INTO agent_evidence VALUES (?,?,?,?,?,?)",id,rec.product().id(),article.id(),article.title(),article.content(),article.source());
            }
            saveSteps(id,steps);
            if(Set.of("HANDOFF","NO_MATCH").contains(reply.status())) createCase(id,reply.message());
            return null;
        });
    }
    void fail(String id,String reason,long elapsed,int calls,int tools,Long input,Long output,List<Step> steps) {
        store.tx(()->{
            int changed=store.jdbc.update("UPDATE agent_run SET status='FAILED',summary=?,finished_at=?,elapsed_ms=?,model_calls=?,tool_calls=?,input_tokens=?,output_tokens=? WHERE id=? AND status='RUNNING'",
                reason,Instant.now().toString(),elapsed,calls,tools,input,output,id);
            if(changed==0)return null;
            saveSteps(id,steps);return null;
        });
    }
    private void saveSteps(String id,List<Step> steps) {
        int n=0;for(var s:steps)store.jdbc.update("INSERT INTO agent_step VALUES (?,?,?,?,?)",id,++n,s.kind(),s.detail(),s.elapsedMs());
    }
    private void createCase(String id,String reason) {
        if(store.jdbc.queryForObject("SELECT COUNT(*) FROM agent_case WHERE run_id=?",Integer.class,id)==0)
            store.jdbc.update("INSERT INTO agent_case(run_id,state,reason,created_at) VALUES (?,'OPEN',?,?)",id,reason.length()>500?reason.substring(0,500):reason,Instant.now().toString());
    }
    private Map<String,Object> owned(String id,String account,boolean lock) {
        return store.jdbc.queryForList("SELECT * FROM agent_run WHERE id=? AND account_id=?"+(lock?" FOR UPDATE":""),id,account).stream().findFirst().orElseThrow();
    }
    Models.Cart confirm(String id,String account,Confirm input) {
        if(input==null||input.skuId()==null)throw new IllegalArgumentException("请选择一项已推荐的规格");
        return store.tx(()->{
            // Follow checkout's account -> product lock order. A retry never adds another copy.
            store.jdbc.queryForList("SELECT id FROM customer_account WHERE id=? FOR UPDATE",account);
            var run=owned(id,account,true);
            if("EVALUATION".equals(run.get("origin")))throw new AuthService.Failure(409,"评测记录仅用于验证，不执行真实加购");
            var previous=store.jdbc.queryForList("SELECT sku_id FROM agent_selection WHERE run_id=?",String.class,id);
            if(!previous.isEmpty()){
                if(!previous.get(0).equals(input.skuId()))throw new AuthService.Failure(409,"这次选购已确认其他规格，请发起新的选购");
                return store.cart(account);
            }
            if(!Set.of("DONE","HANDOFF").contains(run.get("status")))throw new AuthService.Failure(409,"这次接待没有可确认的推荐");
            var candidates=store.jdbc.queryForList("SELECT c.quoted_price,s.product_id FROM agent_candidate c JOIN product_sku s ON s.id=c.sku_id WHERE c.run_id=? AND c.sku_id=?",id,input.skuId());
            if(candidates.isEmpty())throw new IllegalArgumentException("所选规格不在本次推荐中");
            var c=candidates.get(0);String product=(String)c.get("product_id");
            store.jdbc.queryForList("SELECT id FROM product WHERE id=? FOR UPDATE",product);
            Models.Product p;
            try{p=store.product(product,false);}catch(NoSuchElementException e){throw new AuthService.Failure(409,"商品已下架，请重新问衣");}
            var requirements=new RetailRules.Requirements((BigDecimal)run.get("budget"),(String)run.get("size_label"),(String)run.get("color_label"),((Number)run.get("quantity")).intValue());
            var sku=RetailRules.eligible(p,requirements,null).stream().filter(s->s.id().equals(input.skuId())).findFirst().orElseThrow(()->new AuthService.Failure(409,"规格库存或价格已变化，请重新问衣"));
            if(sku.price().compareTo((BigDecimal)c.get("quoted_price"))!=0)throw new AuthService.Failure(409,"价格已变化，请重新问衣确认价格");
            int already=store.jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM cart_item WHERE account_id=? AND sku_id=?",Integer.class,account,sku.id());
            int quantity=already+requirements.quantity();
            if(quantity>99||quantity>sku.stock())throw new AuthService.Failure(409,"衣囊已有数量加上本次购买数量超过库存");
            var cart=store.addCart(account,new Models.CartAdd(product,sku.id(),quantity));
            store.jdbc.update("INSERT INTO agent_selection VALUES (?,?,?)",id,sku.id(),Instant.now().toString());
            store.jdbc.update("UPDATE agent_run SET status='CONFIRMED' WHERE id=?",id);
            return cart;
        });
    }
    void requestHelp(String id,String account) {
        store.tx(()->{var r=owned(id,account,true);if(r.get("status").equals("RUNNING"))throw new AuthService.Failure(409,"本次接待还在运行，请稍后刷新");createCase(id,"用户请求商家协助；请查看本次需求、候选和执行记录");return null;});
    }
    void resolve(String id,String admin,Resolve input) {
        if(input==null)throw new IllegalArgumentException("请填写处理回复");CommerceStore.text(input.reply(),1,2000,"处理回复");
        store.tx(()->{
            if(store.jdbc.update("UPDATE agent_case SET state='RESOLVED',reply=?,assigned_to=?,resolved_at=? WHERE run_id=? AND state='OPEN'",input.reply().trim(),admin,Instant.now().toString(),id)!=1)throw new AuthService.Failure(409,"待办不存在或已处理，请刷新");
            store.audit(admin,"AGENT_CASE_RESOLVE",id,"回复并完成导购待办");return null;
        });
    }
    List<Map<String,Object>> list(String account) {
        var rows=account==null?store.jdbc.queryForList("SELECT * FROM agent_run ORDER BY created_at DESC LIMIT 200"):store.jdbc.queryForList("SELECT * FROM agent_run WHERE account_id=? AND origin='LIVE' ORDER BY created_at DESC LIMIT 50",account);
        for(var row:rows){row.remove("session_key");row.remove("account_id");enrich(row);}
        return rows.stream().map(RetailWorkflow::normalize).toList();
    }
    private static Map<String,Object> normalize(Map<?,?> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value)->{
            Object v=value;
            if(value instanceof List<?> list)v=list.stream().map(item->item instanceof Map<?,?> m?normalize(m):item).toList();
            result.put(key.toString().toLowerCase(Locale.ROOT),v);
        });
        return result;
    }
    private void enrich(Map<String,Object> row) {
        String id=(String)row.get("id");
        row.put("candidates",store.jdbc.queryForList("SELECT c.sku_id,c.quoted_price,s.product_id,s.size_label,s.color,p.name,p.status,p.deleted_at FROM agent_candidate c JOIN product_sku s ON s.id=c.sku_id JOIN product p ON p.id=s.product_id WHERE c.run_id=? ORDER BY c.quoted_price,c.sku_id",id));
        row.put("evidence",store.jdbc.queryForList("SELECT product_id,title_at_query,excerpt_at_query,source_at_query FROM agent_evidence WHERE run_id=?",id));
        row.put("steps",store.jdbc.queryForList("SELECT step_number,kind,detail,elapsed_ms FROM agent_step WHERE run_id=? ORDER BY step_number",id));
        row.put("cases",store.jdbc.queryForList("SELECT state,reason,reply,created_at,resolved_at FROM agent_case WHERE run_id=?",id));
        row.put("selections",store.jdbc.queryForList("SELECT sku_id,confirmed_at FROM agent_selection WHERE run_id=?",id));
    }
    Map<String,Object> metrics() {
        var counts=store.jdbc.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failed,COALESCE(SUM(CASE WHEN status='CONFIRMED' THEN 1 ELSE 0 END),0) AS confirmed,COALESCE(SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END),0) AS running FROM agent_run WHERE origin='LIVE'");
        counts.put("openCases",store.jdbc.queryForObject("SELECT COUNT(*) FROM agent_case c JOIN agent_run r ON c.run_id=r.id WHERE c.state='OPEN' AND r.origin='LIVE'",Integer.class));
        counts.put("resolvedCases",store.jdbc.queryForObject("SELECT COUNT(*) FROM agent_case c JOIN agent_run r ON c.run_id=r.id WHERE c.state='RESOLVED' AND r.origin='LIVE'",Integer.class));
        counts.put("evaluations",store.jdbc.queryForObject("SELECT COUNT(*) FROM agent_run WHERE origin='EVALUATION'",Integer.class));
        var times=store.jdbc.queryForList("SELECT elapsed_ms FROM agent_run WHERE finished_at IS NOT NULL AND elapsed_ms IS NOT NULL AND origin='LIVE' ORDER BY elapsed_ms",Long.class);
        counts.put("p95Ms",times.isEmpty()?null:times.get((int)Math.ceil(times.size()*.95)-1));
        counts.put("averageMs",times.stream().mapToLong(Long::longValue).average().isPresent()?times.stream().mapToLong(Long::longValue).average().getAsDouble():null);
        counts.put("cost",null);counts.put("costNote","供应商计费未核验，不估算费用；Token仅记录接口返回值");
        counts.put("scope","仅统计用户接待，排除独立评测；确认数是加入衣囊次数，不是订单成交或转化率");
        // SQL aliases can be uppercased by H2; public metric keys are intentionally camelCase.
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("total","failed","confirmed","running","openCases","resolvedCases","evaluations","p95Ms","averageMs","cost","costNote","scope"))result.put(key,counts.get(key));
        return result;
    }
}
