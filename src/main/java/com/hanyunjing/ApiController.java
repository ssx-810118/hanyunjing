package com.hanyunjing;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final CoreService core; private final TryOnService tryon; private final OnlineAgent online;private final TraceBus bus;
    public ApiController(CoreService core,TryOnService tryon,OnlineAgent online,TraceBus bus){this.core=core;this.tryon=tryon;this.online=online;this.bus=bus;}
    private String sid(String s){String scoped=AuthService.scope(s);var user=AuthService.currentUser();if(user!=null)core.bindProfile(scoped,user.id());return scoped;}
    private String owner(String s){return AuthService.owner(s);}
    private Models.AgentReply external(Models.AgentReply r,String s){return new Models.AgentReply(s,r.status(),r.message(),r.slots(),r.missingFields(),r.funnel(),r.recommendations(),r.knowledge(),r.requirements(),r.workflowId());}
    private Models.Portrait external(Models.Portrait p,String s){return new Models.Portrait(p.id(),s,p.width(),p.height(),p.format(),p.expiresAt());}
    private Models.TryOn external(Models.TryOn t,String s){return new Models.TryOn(t.id(),s,t.productId(),t.skuId(),t.status(),t.stage(),t.attempts(),t.demo(),externalUrl(t.resultUrl(),t.sessionId(),s),t.checks(),t.expiresAt(),externalUrl(t.originalUrl(),t.sessionId(),s),t.error());}
    private String externalUrl(String url,String scoped,String original){return url==null?null:url.replace("sessionId="+scoped,"sessionId="+original);}
    private Models.TraceEvent external(Models.TraceEvent t,String s){return new Models.TraceEvent(t.id(),s,t.type(),t.summary(),t.timestamp());}
    private <T> ResponseEntity<Models.Api<T>> ok(T x){return ResponseEntity.ok(Models.Api.ok(x));}
    @PostMapping("/agent/chat") public ResponseEntity<Models.Api<Models.AgentReply>> chat(@Valid @RequestBody Models.Chat in){return ok(external(online.chat(new Models.Chat(sid(in.sessionId()),in.message(),in.requirements())),in.sessionId()));}
    @GetMapping("/agent/status") public ResponseEntity<Models.Api<OnlineAgent.Status>> agentStatus(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(online.status()));}
    @GetMapping("/suggestions") public ResponseEntity<Models.Api<List<String>>> suggestions(@RequestParam String sessionId){sid(sessionId);return ok(List.of("我第一次穿，去芙蓉园，想要唐制","城墙夜游，颜色太亮，想显瘦","书院门换宋制，轻便一些"));}
    @GetMapping("/products") public ResponseEntity<Models.Api<Collection<Models.Product>>> products(@RequestParam(required=false) String sessionId,@RequestParam(required=false) String dynasty,@RequestParam(required=false) String form,@RequestParam(required=false) String scene,@RequestParam(required=false) String size,@RequestParam(required=false) String q){sid(sessionId);return ok(core.products(dynasty,form,scene,size,q));}
    @GetMapping("/products/{id}") public ResponseEntity<Models.Api<Models.Product>> product(@PathVariable String id,@RequestParam String sessionId){sid(sessionId);return ok(core.product(id));}
    @GetMapping("/categories") public ResponseEntity<Models.Api<Map<String,List<String>>>> categories(@RequestParam String sessionId){sid(sessionId);return ok(Map.of("dynasties",Dynasty.labels(),"forms",core.products(null,null,null,null,null).stream().map(Models.Product::form).distinct().toList()));}
    @GetMapping("/scenes") public ResponseEntity<Models.Api<Collection<Models.Scene>>> scenes(@RequestParam String sessionId){sid(sessionId);return ok(core.scenes());}
    @GetMapping("/knowledge/forms") public ResponseEntity<Models.Api<List<String>>> forms(@RequestParam String sessionId){sid(sessionId);return ok(List.of("FACT","COMMON"));}
    @GetMapping("/knowledge/dynasties") public ResponseEntity<Models.Api<List<String>>> dynasties(@RequestParam String sessionId){sid(sessionId);return ok(Dynasty.labels());}
    @GetMapping("/knowledge/articles/{id}") public ResponseEntity<Models.Api<Models.Article>> article(@PathVariable String id,@RequestParam String sessionId){sid(sessionId);return ok(core.articles().stream().filter(a->a.id().equals(id)).findFirst().orElseThrow());}
    @GetMapping("/knowledge/search") public ResponseEntity<Models.Api<Models.KnowledgeResult>> search(@RequestParam String sessionId,@RequestParam String q){String scoped=sid(sessionId);var result=core.searchKnowledge(q);if(result.abstained())core.trace(scoped,"KNOWLEDGE_GAP","证据不足或冲突");if(result.humanRequired())core.trace(scoped,"HANDOFF","正式礼仪人工核验");return ok(result);}
    @PostMapping("/knowledge/add") public ResponseEntity<Models.Api<Models.Article>> add(@RequestParam String sessionId,@Valid @RequestBody Models.KnowledgeAdd in){sid(sessionId);return ok(core.add(in));}
    @GetMapping("/cart") public ResponseEntity<Models.Api<Models.Cart>> cart(@RequestParam String sessionId){return ok(core.cart(owner(sessionId)));}
    @PostMapping("/cart") public ResponseEntity<Models.Api<Models.Cart>> addCart(@RequestParam String sessionId,@Valid @RequestBody Models.CartAdd in){return ok(core.addCart(owner(sessionId),in,sid(sessionId)));}
    @PutMapping("/cart/{lineId}") public ResponseEntity<Models.Api<Models.Cart>> updateCart(@PathVariable String lineId,@RequestParam String sessionId,@Valid @RequestBody Models.Quantity q){return ok(core.updateCart(owner(sessionId),lineId,q.quantity(),sid(sessionId)));}
    @DeleteMapping("/cart/{lineId}") public ResponseEntity<Models.Api<Void>> deleteCart(@PathVariable String lineId,@RequestParam String sessionId){core.deleteCart(owner(sessionId),lineId,sid(sessionId));return ok(null);}
    @PostMapping("/orders/preview") public ResponseEntity<Models.Api<Map<String,Object>>> preview(@RequestParam String sessionId){Models.Cart c=core.cart(owner(sessionId));return ok(Map.of("cart",c,"payable",c.total(),"currency","CNY","demo",true));}
    @PostMapping("/orders") public ResponseEntity<Models.Api<Models.Order>> order(@RequestParam String sessionId,@Valid @RequestBody Models.Checkout in){return ok(core.order(owner(sessionId),sessionId,in,sid(sessionId)));}
    @GetMapping("/orders") public ResponseEntity<Models.Api<List<Models.Order>>> orders(@RequestParam String sessionId){return ok(core.orders(owner(sessionId)));}
    @GetMapping("/orders/{id}") public ResponseEntity<Models.Api<Models.Order>> orderById(@PathVariable String id,@RequestParam String sessionId){return ok(core.orderById(owner(sessionId),id));}
    @PostMapping("/orders/{id}/pay") public ResponseEntity<Models.Api<Models.Order>> payOrder(@PathVariable String id,@RequestParam String sessionId,@Valid @RequestBody Models.Payment payment){return ok(core.payOrder(owner(sessionId),id,payment,sid(sessionId)));}
    @PostMapping("/orders/{id}/cancel") public ResponseEntity<Models.Api<Models.Order>> cancelOrder(@PathVariable String id,@RequestParam String sessionId){return ok(core.cancelOrder(owner(sessionId),id,sid(sessionId)));}
    @GetMapping("/tryon/status") public ResponseEntity<Models.Api<TryOnService.Status>> tryonStatus(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(tryon.status()));}
    @PostMapping("/tryon/portrait") public ResponseEntity<Models.Api<Models.Portrait>> portrait(@RequestParam String sessionId,@RequestParam(defaultValue="false") Boolean authorized,@RequestParam(defaultValue="false") Boolean aiAuthorized,@RequestPart MultipartFile file)throws Exception{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(external(tryon.upload(sid(sessionId),file,authorized,aiAuthorized),sessionId)));}
    @GetMapping("/tryon/portrait/{id}/image") public ResponseEntity<byte[]> portraitImage(@PathVariable String id,@RequestParam String sessionId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(tryon.portrait(id,sid(sessionId)));}
    @DeleteMapping("/tryon/portrait/{id}") public ResponseEntity<Models.Api<Void>> deletePortrait(@PathVariable String id,@RequestParam String sessionId){tryon.deletePortrait(id,sid(sessionId));return ok(null);}
    @PostMapping("/tryon/generate") public ResponseEntity<Models.Api<Models.TryOn>> generate(@Valid @RequestBody Models.Generate in){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(external(tryon.generate(new Models.Generate(sid(in.sessionId()),in.portraitId(),in.productId(),in.skuId())),in.sessionId())));}
    @GetMapping("/tryon/tasks/{id}") public ResponseEntity<Models.Api<Models.TryOn>> task(@PathVariable String id,@RequestParam String sessionId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Models.Api.ok(external(tryon.get(id,sid(sessionId)),sessionId)));}
    @GetMapping({"/tryon/tasks/{id}/result","/tryon/result/{id}"}) public ResponseEntity<byte[]> result(@PathVariable String id,@RequestParam String sessionId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(tryon.result(id,sid(sessionId)));}
    @PostMapping("/tryon/tasks/{id}/cancel") public ResponseEntity<Models.Api<Void>> cancel(@PathVariable String id,@RequestParam String sessionId){tryon.cancel(id,sid(sessionId));return ok(null);}
    @GetMapping("/trace/events") public ResponseEntity<Models.Api<List<Models.TraceEvent>>> events(@RequestParam String sessionId,@RequestParam(defaultValue="0") long after){return ok(bus.events(sid(sessionId),after).stream().map(t->external(t,sessionId)).toList());}
    @GetMapping("/trace/stream") public SseEmitter stream(@RequestParam String sessionId,@RequestParam(defaultValue="0") long after){return bus.subscribe(sid(sessionId),after,sessionId);}
    @GetMapping("/trace/stream/{sessionId}") public SseEmitter streamPath(@PathVariable String sessionId,@RequestParam(defaultValue="0") long after){return bus.subscribe(sid(sessionId),after,sessionId);}
    @DeleteMapping("/trace/events") public ResponseEntity<Models.Api<Void>> clear(@RequestParam String sessionId){bus.clear(sid(sessionId));return ok(null);}
    @GetMapping("/user/body-profile") public ResponseEntity<Models.Api<Models.Body>> body(@RequestParam String sessionId){return ok(core.body(owner(sessionId)));}
    @PutMapping("/user/body-profile") public ResponseEntity<Models.Api<Void>> body(@RequestParam String sessionId,@Valid @RequestBody Models.Body body){core.body(owner(sessionId),body,sid(sessionId));return ok(null);}
    @DeleteMapping("/user/body-profile") public ResponseEntity<Models.Api<Void>> deleteBody(@RequestParam String sessionId){core.deleteBody(owner(sessionId),sid(sessionId));return ok(null);}
    @GetMapping("/ops/summary") public ResponseEntity<Models.Api<Map<String,Object>>> summary(@RequestParam String sessionId){return ok(Map.of("events",core.traces(sid(sessionId)).size(),"source","当前账号会话事件计数","prebuilt",false));}
    @PostMapping("/ops/handoff") public ResponseEntity<Models.Api<Models.TraceEvent>> handoff(@RequestParam String sessionId,@Valid @RequestBody Models.Handoff h){return ok(external(core.trace(sid(sessionId),"HUMAN_HANDOFF","用户申请人工接管，不记录自由文本"),sessionId));}
    @PostMapping(value="/agent/langchain/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter langchain(@Valid @RequestBody Models.Chat in){
        Models.Chat scoped=new Models.Chat(sid(in.sessionId()),in.message(),in.requirements());
        var user=AuthService.currentUser();String account=user==null?null:user.id();
        SseEmitter out=new SseEmitter(120000L);CompletableFuture.runAsync(()->{try{
            if(!online.available())out.send(SseEmitter.event().name("error").data(new Models.Api<Void>(503,"在线LLM未启用或缺少key/baseUrl",null)));
            else out.send(SseEmitter.event().name("message").data(Models.Api.ok(external(online.chat(scoped,account),in.sessionId()))));
            out.complete();
        }catch(Exception e){try{out.send(SseEmitter.event().name("error").data(new Models.Api<Void>(502,"模型请求失败或输入包含敏感资料",null)));out.complete();}catch(Exception ignored){out.complete();}}});return out;
    }
    @GetMapping(value="/products/{id}/image",produces="image/webp")
    public ResponseEntity<byte[]> image(@PathVariable String id) throws IOException {
        core.product(id);
        var resource = new ClassPathResource("static/images/products/" + id + ".webp");
        if (!resource.isReadable()) return ResponseEntity.notFound().build();
        try (var input = resource.getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/webp")).body(input.readAllBytes());
        }
    }
}
